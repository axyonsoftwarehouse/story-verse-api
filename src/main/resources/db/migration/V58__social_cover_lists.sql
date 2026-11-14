-- Capa da loja nas listas de pessoas (busca, seguidores, seguindo, sugestões e membros de grupo).
--
-- Era a peça que faltava do cartão "Exibir a foto de capa nos cards de perfil": a página do perfil já
-- recebe `shop_cover` + `cover` (enviada) e o site desenha com `profileCoverUrl(shop_cover, cover)`,
-- mas o `PERSON` — fonte de todas as listas — não devolvia nenhum dos dois.
--
-- Aqui só a capa da loja (caminho relativo, que o site resolve contra a própria origem). A capa
-- enviada continua sendo lida direto do metadado da conta na consulta, porque ela precisa da
-- validação do prefixo de mídia que só o Java conhece (`:media`). Perfil privado de quem não segue
-- não recebe nada, igual à página do perfil.
create or replace function public.user_cover(p_user uuid)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select case when public.social_content_visible(p_user) then (
           select i.image_url
             from public.shop_equipped e
             join public.shop_items i on i.id = e.item_id
            where e.user_id = p_user and e.category = 'cover'
         ) end;
$$;

revoke execute on function public.user_cover(uuid) from public, anon;
grant execute on function public.user_cover(uuid) to authenticated;
