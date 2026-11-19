-- Conceder (ou tirar) diamantes pelo painel, sem SQL à mão na VPS. Só superadmin, conferido aqui
-- dentro com has_role (a função é security definer e passa pela lista branca do RpcService).
-- A conta é achada pelo e-mail (sem diferença de maiúsculas). O lançamento entra no extrato como
-- 'adjustment' com a ref 'admin:<uuid>', única por concessão; quem concedeu, a nota, o valor, a ref
-- e o saldo novo ficam em admin_audit_log (ação 'diamonds.grant', aparece na Auditoria).
-- A nota é interna: o extrato da pessoa mostra só "Ajuste".

create or replace function public.admin_grant_diamonds(p_email text, p_amount integer, p_note text)
returns table (user_id uuid, email text, amount integer, balance integer)
language plpgsql
security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  actor uuid := auth.uid();
  target uuid;
  target_email text;
  matches integer;
  note text := btrim(coalesce(p_note, ''));
  ref text;
  bal integer;
begin
  if actor is null or not public.has_role('superadmin') then
    raise exception 'Só superadmin pode conceder diamantes.';
  end if;
  if p_amount is null or p_amount = 0 then
    raise exception 'Informe uma quantidade diferente de zero.';
  end if;
  if abs(p_amount) > 100000 then
    raise exception 'Quantidade grande demais (máximo de 100.000 por lançamento).';
  end if;
  if char_length(note) < 3 or char_length(note) > 200 then
    raise exception 'Escreva o motivo (de 3 a 200 caracteres).';
  end if;

  select count(*)::integer, min(u.id::text)::uuid, min(u.email)
    into matches, target, target_email
    from auth.users u
   where lower(u.email) = lower(btrim(coalesce(p_email, '')));
  if matches = 0 then
    raise exception 'Nenhuma conta com esse e-mail.';
  end if;
  if matches > 1 then
    raise exception 'Mais de uma conta com esse e-mail; resolva pelo banco.';
  end if;

  -- Débito maior que o saldo: apply_diamonds recusa ('Diamantes insuficientes.') e nada fica gravado.
  ref := 'admin:' || gen_random_uuid()::text;
  bal := public.apply_diamonds(target, p_amount, 'adjustment', ref);

  insert into public.admin_audit_log (actor_id, action, target_type, target_id, metadata)
  values (actor, 'diamonds.grant', 'user', target::text,
          jsonb_build_object('amount', p_amount, 'note', note, 'ref', ref, 'balance_after', bal));

  return query select target, target_email, p_amount, bal;
end;
$$;

revoke execute on function public.admin_grant_diamonds(text, integer, text) from public, anon;
grant execute on function public.admin_grant_diamonds(text, integer, text) to authenticated;
