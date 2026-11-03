-- Apagar um grupo (marcar deleted_at) falhava em produção: a linha atualizada deixa de passar na
-- política de leitura de chat_groups ("deleted_at is null ..."), e o Postgres recusa o UPDATE
-- ("new row violates row-level security policy"). Esta função apaga só se quem pede é o dono
-- do grupo ou da equipe.
create or replace function public.chat_delete_group(p_group uuid)
returns boolean
language sql
security definer
set search_path = public
as $$
  with gone as (
    update public.chat_groups
       set deleted_at = now(), updated_at = now()
     where id = p_group
       and deleted_at is null
       and (owner_id = auth.uid() or public.is_staff())
    returning 1
  )
  select exists (select 1 from gone);
$$;

revoke execute on function public.chat_delete_group(uuid) from public, anon;
grant execute on function public.chat_delete_group(uuid) to authenticated;
