-- Roleplay: o personagem de cada pessoa tem foto. Dentro do grupo, a foto e o nome do personagem
-- aparecem no lugar dos da pessoa. A API só aceita arquivo da pasta da própria pessoa no Storage.
alter table public.chat_members
  add column if not exists rp_photo text check (rp_photo is null or char_length(rp_photo) <= 500);
