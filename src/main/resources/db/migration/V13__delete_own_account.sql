-- Exclusão da própria conta sem dar DELETE amplo em auth.users ao papel de runtime.
-- A API remove os arquivos antes e chama esta função na mesma transação.
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Entre na sua conta para excluir.';
  end if;
  delete from auth.users where id = uid;
end;
$$;

revoke execute on function public.delete_my_account() from public, anon, authenticated;
grant execute on function public.delete_my_account() to storyverse_api;
