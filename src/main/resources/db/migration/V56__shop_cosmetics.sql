-- Loja: o equipado aparece em todo lugar que mostra pessoas. As listas da API (Java) chamam
-- public.user_cosmetics (V54) por pessoa; aqui ficam o extra do perfil (capa da loja e estante de
-- insígnias) e os rankings, que passam a devolver a coluna cosmetics.
-- As tabelas da loja não têm leitura para o runtime (V54/V55): tudo por funções security definer.

-- Capa da loja equipada e estante com todas as insígnias da pessoa. Só para quem pode ver o perfil
-- (perfil privado: quem não segue não vê capa nem estante; moldura, insígnia e cor continuam).
create or replace function public.user_profile_extras(p_user uuid)
returns jsonb
language sql
stable
security definer
set search_path = public
as $$
  select case when public.social_content_visible(p_user) then jsonb_build_object(
    'shop_cover', (
      select i.image_url
      from public.shop_equipped e join public.shop_items i on i.id = e.item_id
      where e.user_id = p_user and e.category = 'cover'
    ),
    'badge_shelf', coalesce((
      select jsonb_agg(jsonb_build_object('id', i.id, 'name', i.name, 'image', i.image_url)
                       order by o.created_at, i.id)
      from public.shop_ownership o join public.shop_items i on i.id = o.item_id
      where o.user_id = p_user and i.category = 'badge'
    ), '[]'::jsonb)
  ) else jsonb_build_object('shop_cover', null, 'badge_shelf', '[]'::jsonb) end;
$$;

revoke execute on function public.user_profile_extras(uuid) from public, anon;
grant execute on function public.user_profile_extras(uuid) to authenticated;

-- Rankings com o equipado de cada pessoa. Mudar o "returns table" exige drop + create; nenhuma
-- outra função depende destas duas (só a API, pela lista branca do rpc). Mesmas permissões de antes.
drop function if exists public.weekly_ranking(integer, integer);
create function public.weekly_ranking(p_offset integer default 0, p_limit integer default 50)
returns table (place integer, user_id uuid, name text, avatar text, photo text, minutes integer, is_me boolean,
               cosmetics jsonb)
language sql
stable
security definer
set search_path = public
as $$
  select r.place, r.user_id, r.name, r.avatar, r.photo, r.minutes, r.is_me, public.user_cosmetics(r.user_id)
  from public.ranking_for_week(
    public.ranking_week_start(greatest(least(p_offset, 0), -1)),
    greatest(least(p_limit, 100), 3),
    auth.uid()
  ) r
  order by r.place;
$$;

revoke execute on function public.weekly_ranking(integer, integer) from public, anon;
grant execute on function public.weekly_ranking(integer, integer) to authenticated;

drop function if exists public.alltime_ranking(integer);
create function public.alltime_ranking(p_limit integer default 50)
returns table (place integer, user_id uuid, name text, avatar text, photo text, minutes integer, is_me boolean,
               cosmetics jsonb)
language sql
stable
security definer
set search_path = public
as $$
  with totals as (
    select d.user_id, (sum(d.seconds) / 60)::integer as minutes, max(d.updated_at) as last_update
    from public.reading_days d
    group by d.user_id
  ),
  ranked as (
    select t.user_id, t.minutes,
           (row_number() over (order by t.minutes desc, t.last_update asc, t.user_id))::integer as place
    from totals t
    join public.ranking_profiles p on p.user_id = t.user_id
    where t.minutes >= 1 and not p.hidden
  )
  select r.place, r.user_id, p.name, p.avatar, p.photo, r.minutes, r.user_id = auth.uid(),
         public.user_cosmetics(r.user_id)
  from ranked r
  join public.ranking_profiles p on p.user_id = r.user_id
  where r.place <= greatest(least(p_limit, 100), 3) or r.user_id = auth.uid()
  order by r.place;
$$;

revoke execute on function public.alltime_ranking(integer) from public, anon;
grant execute on function public.alltime_ranking(integer) to authenticated;
