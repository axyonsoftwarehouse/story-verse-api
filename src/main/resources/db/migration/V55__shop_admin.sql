-- Admin da loja (painel). A API roda como storyverse_api (sem bypass de RLS) e o V54 tirou tudo de
-- public/anon/authenticated: aqui a API ganha leitura e escrita no catálogo, e a RLS só deixa passar
-- superadmin. A posse é só lida (contagem de donos e a trava de "item já vendido não se apaga").
-- As compras continuam pelas funções security definer do V54, que não dependem destas permissões.

grant select, insert, update, delete on public.shop_items to storyverse_api;
grant select on public.shop_ownership to storyverse_api;

drop policy if exists shop_items_superadmin on public.shop_items;
create policy shop_items_superadmin on public.shop_items for all to storyverse_api
  using (public.has_role('superadmin')) with check (public.has_role('superadmin'));

drop policy if exists shop_ownership_superadmin_read on public.shop_ownership;
create policy shop_ownership_superadmin_read on public.shop_ownership for select to storyverse_api
  using (public.has_role('superadmin'));

-- Regras simples de tipo × campos também na tabela (a API confere antes e explica ao admin).
alter table public.shop_items
  add constraint shop_items_colors_only_name_color check (category = 'name_color' or colors is null),
  add constraint shop_items_name_color_no_images check (
    category <> 'name_color' or (image_url is null and animated_url is null)
  ),
  add constraint shop_items_animated_only_epic_frame check (
    animated_url is null or (category = 'frame' and tier = 'epic')
  ),
  add constraint shop_items_on_sale_needs_image check (
    status <> 'on_sale' or category = 'name_color' or image_url is not null
  ),
  add constraint shop_items_window_order check (
    available_from is null or available_until is null or available_from < available_until
  );
