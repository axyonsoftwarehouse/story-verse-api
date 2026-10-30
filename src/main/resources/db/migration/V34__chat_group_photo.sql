-- Foto do grupo: arquivo enviado por quem edita para a própria pasta do Storage
-- (profile-media/<conta>/grupos/<uuid>); a API só aceita esse endereço.
alter table public.chat_groups
  add column if not exists photo_url text check (photo_url is null or char_length(photo_url) <= 500);
