-- Até quatro insígnias equipadas ao mesmo tempo. Moldura, capa e cor de nome continuam uma por categoria.
-- `badge` segue sendo a primeira (telas antigas). `badges` é a lista inteira.

alter table public.shop_equipped drop constraint shop_equipped_pkey;
alter table public.shop_equipped add primary key (user_id, item_id);

create unique index shop_equipped_one_non_badge
  on public.shop_equipped (user_id, category)
  where category <> 'badge';

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
      order by i.name
      limit 1
    ),
    'badges', coalesce((
      select jsonb_agg(jsonb_build_object('image', i.image_url, 'name', i.name) order by i.name)
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'badge'
    ), '[]'::jsonb),
    'name_color', (
      select to_jsonb(i.colors)
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'name_color'
    )
  );
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
  badge_count integer;
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

  if p_category = 'badge' then
    if exists (select 1 from public.shop_equipped e where e.user_id = uid and e.item_id = p_item) then
      delete from public.shop_equipped e where e.user_id = uid and e.item_id = p_item;
      return public.user_cosmetics(uid);
    end if;
    select count(*) into badge_count
    from public.shop_equipped e
    where e.user_id = uid and e.category = 'badge';
    if badge_count >= 4 then
      raise exception 'Dá para usar até 4 insígnias.';
    end if;
    insert into public.shop_equipped (user_id, category, item_id)
    values (uid, 'badge', p_item);
    return public.user_cosmetics(uid);
  end if;

  delete from public.shop_equipped e where e.user_id = uid and e.category = p_category;
  insert into public.shop_equipped (user_id, category, item_id)
  values (uid, p_category, p_item);

  return public.user_cosmetics(uid);
end;
$$;
