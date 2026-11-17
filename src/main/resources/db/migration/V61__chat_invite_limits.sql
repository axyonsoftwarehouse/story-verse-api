-- Link de convite com prazo e limite de entradas.
--
-- Antes o código do grupo era único, reutilizável e sem prazo: quem tivesse o link entrava
-- para sempre, inclusive quem já tinha saído ou sido removido. Agora o link pode ter prazo e
-- limite de usos, e o consumo é atômico (duas pessoas com o mesmo link no mesmo segundo não
-- passam do limite).

alter table public.chat_groups
  add column if not exists invite_expires_at timestamptz,
  add column if not exists invite_max_uses integer,
  add column if not exists invite_uses integer not null default 0;

-- Link de uso único esgotado deixa o grupo sem código (até o dono gerar outro): a coluna
-- nasceu not null, então precisa aceitar null.
alter table public.chat_groups alter column invite_code drop not null;

comment on column public.chat_groups.invite_expires_at is 'Fim da validade do link de convite; null = sem prazo.';
comment on column public.chat_groups.invite_max_uses is 'Quantas entradas o link aceita; null = sem limite.';
comment on column public.chat_groups.invite_uses is 'Entradas já feitas com o link atual (reinicia ao rotacionar).';

alter table public.chat_groups
  add constraint chat_groups_invite_max_uses_range
    check (invite_max_uses is null or invite_max_uses between 1 and 500),
  add constraint chat_groups_invite_uses_positive
    check (invite_uses >= 0);

-- Consumo atômico do link: incrementa o contador só quando o link ainda vale e devolve o
-- grupo. Vazio = expirado, esgotado ou inexistente. Quando o limite é atingido, o código é
-- apagado (o link morre); rotacionar gera outro, com contador zerado.
create or replace function public.chat_consume_invite(p_code text)
returns table (id uuid, visibility text)
language sql
volatile
security definer
set search_path = public
as $$
  with used as (
    update public.chat_groups g
       set invite_uses = g.invite_uses + 1,
           invite_code = case
                           when g.invite_max_uses is not null and g.invite_uses + 1 >= g.invite_max_uses
                             then null
                           else g.invite_code
                         end,
           updated_at = now()
     where g.invite_code = p_code
       and g.deleted_at is null
       and (g.invite_expires_at is null or g.invite_expires_at > now())
       and (g.invite_max_uses is null or g.invite_uses < g.invite_max_uses)
    returning g.id, g.visibility
  )
  select * from used;
$$;

revoke execute on function public.chat_consume_invite(text) from public, anon;
grant execute on function public.chat_consume_invite(text) to authenticated;
