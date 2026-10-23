-- Uma só fonte de papéis: user_roles. public.admins fica congelada (removida numa versão futura).
insert into public.user_roles (user_id, role)
select user_id, 'superadmin' from public.admins
on conflict do nothing;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin'); $$;

create or replace function public.is_staff()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin') or public.has_role('moderator') or public.has_role('support'); $$;

create or replace function public.can_moderate()
returns boolean language sql stable security definer set search_path = public
as $$ select public.has_role('superadmin') or public.has_role('moderator'); $$;
grant execute on function public.can_moderate() to anon, authenticated;

-- As políticas de book_submissions vêm do schema base (database/schema.sql) e usavam is_admin().
drop policy if exists "ver envios" on public.book_submissions;
create policy "ver envios" on public.book_submissions
  for select using (status = 'approved' or auth.uid() = user_id or public.can_moderate());

drop policy if exists "admin revisa" on public.book_submissions;
create policy "admin revisa" on public.book_submissions
  for update using (public.can_moderate()) with check (public.can_moderate());

drop policy if exists "remover envio" on public.book_submissions;
create policy "remover envio" on public.book_submissions
  for delete using (public.can_moderate() or (auth.uid() = user_id and status = 'pending'));

-- O suporte vê papéis e features de qualquer conta (painel de contas). Só leitura: as políticas
-- de escrita continuam só para superadmin.
drop policy if exists "cada um ve seus papeis" on public.user_roles;
create policy "cada um ve seus papeis" on public.user_roles
  for select using (user_id = auth.uid() or public.has_role('superadmin') or public.has_role('support'));

drop policy if exists "cada um ve suas features" on public.feature_access;
create policy "cada um ve suas features" on public.feature_access
  for select using (user_id = auth.uid() or public.has_role('superadmin') or public.has_role('support'));
