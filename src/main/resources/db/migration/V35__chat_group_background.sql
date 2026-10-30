-- Fundo da conversa do grupo, escolhido pelo dono: um tema pronto ('preset:<nome>') ou uma
-- imagem enviada para a própria pasta do Storage (profile-media/<conta>/grupos/<uuid>).
alter table public.chat_groups
  add column if not exists background text check (background is null or char_length(background) <= 500);
