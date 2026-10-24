-- Fase 2 do admin: lista de contas por criação e lista de sanções por criação.
-- Métricas ficam na V19 (funções de agregação) para não misturar as entregas.
create index if not exists users_created_at_id_idx
  on auth.users (created_at desc, id);

create index if not exists users_last_sign_in_idx
  on auth.users (last_sign_in_at desc);

create index if not exists user_sanctions_created_idx
  on public.user_sanctions (created_at desc, id);
