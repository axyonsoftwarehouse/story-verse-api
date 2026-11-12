-- Loja de diamantes, primeira categoria: itens de perfil (moldura, insígnia, capa, cor do nome).
-- Catálogo genérico + posse + equipado. Compra no padrão de buy_ai_pack (V10): idempotente por ref,
-- débito via apply_diamonds(..., 'purchase', ref). O motivo 'purchase' já existe no extrato (V10).

create table if not exists public.shop_items (
  id uuid primary key default gen_random_uuid(),
  category text not null check (category in ('frame', 'badge', 'cover', 'name_color')),
  tier text not null check (tier in ('common', 'rare', 'epic')),
  name text not null check (char_length(name) between 1 and 60),
  description text not null default '' check (char_length(description) <= 200),
  price integer not null check (price > 0),
  status text not null default 'draft' check (status in ('draft', 'on_sale', 'off_sale')),
  image_url text,
  animated_url text,
  colors text[],
  sort_order integer not null default 0,
  -- Previstos para os limitados: nulos = sem janela / sem limite de estoque.
  available_from timestamptz,
  available_until timestamptz,
  stock integer check (stock is null or stock >= 0),
  sold integer not null default 0 check (sold >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- Cores vão parar em CSS: de 1 a 3, só no formato #rrggbb, sem nulos.
  constraint shop_items_colors_check check (
    colors is null or (
      array_ndims(colors) = 1
      and cardinality(colors) between 1 and 3
      and array_position(colors, null) is null
      and array_to_string(colors, ',') ~ '^#[0-9a-fA-F]{6}(,#[0-9a-fA-F]{6}){0,2}$'
    )
  ),
  constraint shop_items_name_color_needs_colors check (category <> 'name_color' or colors is not null),
  -- Moldura épica é animada: as duas imagens sobem antes de salvar.
  constraint shop_items_epic_frame_images check (
    not (category = 'frame' and tier = 'epic') or (image_url is not null and animated_url is not null)
  )
);

create index if not exists shop_items_on_sale
  on public.shop_items (sort_order, price) where status = 'on_sale';

create table if not exists public.shop_ownership (
  user_id uuid not null references auth.users (id) on delete cascade,
  item_id uuid not null references public.shop_items (id),
  price_paid integer not null check (price_paid > 0),
  ref text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, item_id),
  unique (user_id, ref)
);

create table if not exists public.shop_equipped (
  user_id uuid not null,
  category text not null check (category in ('frame', 'badge', 'cover', 'name_color')),
  item_id uuid not null,
  primary key (user_id, category),
  foreign key (user_id, item_id) references public.shop_ownership (user_id, item_id) on delete cascade
);

alter table public.shop_items enable row level security;
alter table public.shop_ownership enable row level security;
alter table public.shop_equipped enable row level security;
-- Ninguém mexe nas tabelas direto: só as funções abaixo (e o admin, pela API).
revoke all on public.shop_items, public.shop_ownership, public.shop_equipped
  from public, anon, authenticated;

-- O que a pessoa tem equipado, num formato só para todas as telas que mostram pessoas.
-- Item equipado que saiu de venda continua valendo.
create or replace function public.user_cosmetics(p_user uuid)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select jsonb_build_object(
    'frame', (
      select jsonb_build_object('image', i.image_url, 'animated', i.animated_url, 'tier', i.tier)
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'frame'
    ),
    'badge', (
      select jsonb_build_object('image', i.image_url, 'name', i.name)
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'badge'
    ),
    'name_color', (
      select to_jsonb(i.colors)
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'name_color'
    )
  );
$$;

create or replace function public.shop_catalog()
returns table (
  id uuid,
  category text,
  tier text,
  name text,
  description text,
  price integer,
  image_url text,
  animated_url text,
  colors text[],
  owned boolean,
  equipped boolean,
  available_until timestamptz,
  remaining integer
)
language sql
stable
security definer
set search_path = public
as $$
  select
    i.id, i.category, i.tier, i.name, i.description, i.price,
    i.image_url, i.animated_url, i.colors,
    exists (select 1 from public.shop_ownership o where o.user_id = auth.uid() and o.item_id = i.id),
    exists (select 1 from public.shop_equipped e where e.user_id = auth.uid() and e.item_id = i.id),
    i.available_until,
    case when i.stock is null then null else greatest(i.stock - i.sold, 0) end
  from public.shop_items i
  where i.status = 'on_sale'
    and (i.available_from is null or i.available_from <= now())
    and (i.available_until is null or i.available_until > now())
  order by i.category, i.sort_order, i.price, i.name;
$$;

create or replace function public.buy_shop_item(p_item uuid, p_ref text)
returns table (balance integer, item_id uuid, duplicate boolean)
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  item public.shop_items%rowtype;
  existing public.shop_ownership%rowtype;
  bal integer;
begin
  if uid is null then
    raise exception 'Entre na sua conta para comprar na loja.';
  end if;
  if p_ref is null or char_length(p_ref) < 8 or char_length(p_ref) > 80 then
    raise exception 'Pedido de compra inválido.';
  end if;

  select * into existing from public.shop_ownership o where o.user_id = uid and o.ref = p_ref;
  if found then
    return query select
      coalesce((select w.balance from public.diamond_wallets w where w.user_id = uid), 0),
      existing.item_id, true;
    return;
  end if;

  -- A trava do item serializa compras simultâneas (estoque e posse).
  select * into item from public.shop_items i where i.id = p_item for update;
  if not found then
    raise exception 'Item indisponível.';
  end if;

  -- Mesmo ref enviado duas vezes ao mesmo tempo: o segundo vê a posse depois da trava.
  select * into existing from public.shop_ownership o where o.user_id = uid and o.ref = p_ref;
  if found then
    return query select
      coalesce((select w.balance from public.diamond_wallets w where w.user_id = uid), 0),
      existing.item_id, true;
    return;
  end if;

  if exists (select 1 from public.shop_ownership o where o.user_id = uid and o.item_id = item.id) then
    raise exception 'Você já tem este item.';
  end if;

  if item.status <> 'on_sale'
     or (item.available_from is not null and item.available_from > now())
     or (item.available_until is not null and item.available_until <= now())
     or (item.stock is not null and item.sold >= item.stock) then
    raise exception 'Item indisponível.';
  end if;

  bal := public.apply_diamonds(uid, -item.price, 'purchase', p_ref);
  if bal is null then
    raise exception 'Pedido de compra repetido.';
  end if;

  insert into public.shop_ownership (user_id, item_id, price_paid, ref)
  values (uid, item.id, item.price, p_ref);

  update public.shop_items set sold = sold + 1 where id = item.id;

  return query select bal, item.id, false;
end;
$$;

create or replace function public.my_shop_items()
returns table (
  item_id uuid,
  category text,
  tier text,
  name text,
  image_url text,
  animated_url text,
  colors text[],
  equipped boolean,
  price_paid integer,
  bought_at timestamptz
)
language sql
stable
security definer
set search_path = public
as $$
  select
    i.id, i.category, i.tier, i.name, i.image_url, i.animated_url, i.colors,
    exists (select 1 from public.shop_equipped e where e.user_id = o.user_id and e.item_id = i.id),
    o.price_paid, o.created_at
  from public.shop_ownership o
  join public.shop_items i on i.id = o.item_id
  where o.user_id = auth.uid()
  order by o.created_at desc, i.name;
$$;

create or replace function public.equip_shop_item(p_category text, p_item uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  uid uuid := auth.uid();
  item_category text;
begin
  if uid is null then
    raise exception 'Entre na sua conta para equipar itens.';
  end if;
  if p_category is null or p_category not in ('frame', 'badge', 'cover', 'name_color') then
    raise exception 'Tipo de item inválido.';
  end if;

  if p_item is null then
    delete from public.shop_equipped e where e.user_id = uid and e.category = p_category;
    return public.user_cosmetics(uid);
  end if;

  select i.category into item_category
  from public.shop_ownership o
  join public.shop_items i on i.id = o.item_id
  where o.user_id = uid and o.item_id = p_item;
  if not found then
    raise exception 'Você não tem este item.';
  end if;
  if item_category <> p_category then
    raise exception 'Este item não é desse tipo.';
  end if;

  insert into public.shop_equipped (user_id, category, item_id)
  values (uid, p_category, p_item)
  on conflict (user_id, category) do update set item_id = excluded.item_id;

  return public.user_cosmetics(uid);
end;
$$;

-- Extrato: compras da loja passam a dizer o nome do item.
drop function if exists public.my_diamond_history(integer);
create function public.my_diamond_history(p_limit integer default 30)
returns table (amount integer, balance_after integer, reason text, ref text, created_at timestamptz, item_name text)
language sql
stable
security definer
set search_path = public
as $$
  select l.amount, l.balance_after, l.reason, l.ref, l.created_at, i.name
  from public.diamond_ledger l
  left join public.shop_ownership o
    on l.reason = 'purchase' and o.user_id = l.user_id and o.ref = l.ref
  left join public.shop_items i on i.id = o.item_id
  where l.user_id = auth.uid()
  order by l.created_at desc, l.id desc
  limit greatest(least(p_limit, 100), 1);
$$;

revoke execute on function public.user_cosmetics(uuid) from public, anon;
revoke execute on function public.shop_catalog() from public, anon;
revoke execute on function public.buy_shop_item(uuid, text) from public, anon;
revoke execute on function public.my_shop_items() from public, anon;
revoke execute on function public.equip_shop_item(text, uuid) from public, anon;
revoke execute on function public.my_diamond_history(integer) from public, anon;
grant execute on function public.user_cosmetics(uuid) to authenticated;
grant execute on function public.shop_catalog() to authenticated;
grant execute on function public.buy_shop_item(uuid, text) to authenticated;
grant execute on function public.my_shop_items() to authenticated;
grant execute on function public.equip_shop_item(text, uuid) to authenticated;
grant execute on function public.my_diamond_history(integer) to authenticated;
