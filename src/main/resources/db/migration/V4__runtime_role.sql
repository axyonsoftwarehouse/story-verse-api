-- Run as migration/admin role after schema and migrations. Assign the password via \password,
-- never by committing it to this script. Runtime must not own tables or be a superuser.
do $$ begin
  if not exists(select 1 from pg_roles where rolname='storyverse_api') then
    create role storyverse_api login nosuperuser nocreatedb nocreaterole nobypassrls;
  end if;
end $$;
alter role storyverse_api nosuperuser nocreatedb nocreaterole nobypassrls;
revoke create on schema public from public;
do $$ begin
  if exists(select 1 from pg_tables where schemaname in ('auth','public') and tableowner='storyverse_api') then
    raise exception 'Runtime role must not own application tables';
  end if;
end $$;
grant authenticated to storyverse_api;
grant usage on schema auth,public to storyverse_api;
grant select,insert on auth.users to storyverse_api;
grant update(last_sign_in_at,updated_at) on auth.users to storyverse_api;
grant select,insert,update,delete on auth.sessions,auth.session_token_history to storyverse_api;
grant select,insert,update on public.api_daily_usage to storyverse_api;
drop policy if exists api_usage_owner on public.api_daily_usage;
create policy api_usage_owner on public.api_daily_usage for all to storyverse_api
  using(user_id=auth.uid()) with check(user_id=auth.uid());
-- No direct wallet/ledger/ranking writes and no grants on internal award functions.
