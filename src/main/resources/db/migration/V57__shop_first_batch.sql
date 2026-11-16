-- Primeiro lote da loja de itens de perfil (20 itens). Fonte: design/shop-items/README.md do site.
-- As imagens /shop/<arquivo> são servidas pelo próprio site. Ids fixos: reaplicar não duplica.

insert into public.shop_items
  (id, category, tier, name, description, price, status, image_url, animated_url, colors, sort_order)
values
  ('80380854-c5f9-4632-a343-31599b76e5af', 'frame', 'common', 'Anel dourado', 'Um anel liso cor de ouro em volta da sua foto.', 80, 'on_sale', '/shop/moldura-anel-dourado.webp', null, null, 10),
  ('a3e84547-7422-4925-858a-11f46fccde72', 'frame', 'common', 'Anel azul-noite', 'Um anel liso azul-claro, calmo como uma noite de leitura.', 80, 'on_sale', '/shop/moldura-anel-azul.webp', null, null, 20),
  ('cd3d40c3-b85e-40e1-b890-abe1cf13f2e6', 'frame', 'common', 'Anel esmeralda', 'Um anel liso verde-esmeralda em volta da sua foto.', 100, 'on_sale', '/shop/moldura-anel-esmeralda.webp', null, null, 30),
  ('ed44a84c-f6e1-457f-8209-e234910f6fa0', 'frame', 'rare', 'Coroa de folhas', 'Ramos de folhas sobem dos dois lados até uma pequena coroa dourada.', 400, 'on_sale', '/shop/moldura-coroa-de-folhas.webp', null, null, 40),
  ('94bae956-0baa-4ce8-869f-d32adb66f4ab', 'frame', 'rare', 'Noite de estrelas', 'Uma faixa de céu noturno com estrelas douradas e a lua crescente no alto.', 450, 'on_sale', '/shop/moldura-noite-de-estrelas.webp', null, null, 50),
  ('578bbbcd-ff6c-4f3d-85ef-0f832bd4f026', 'frame', 'epic', 'Aurora encantada', 'Um anel brilhante com as cores da aurora que gira devagar no seu perfil.', 900, 'on_sale', '/shop/moldura-aurora.webp', '/shop/moldura-aurora-animada.webp', null, 60),
  ('65df34fb-9adf-422a-98d1-a7cec2b1e262', 'badge', 'common', 'Estrela', 'Uma estrela dourada ao lado do seu nome.', 50, 'on_sale', '/shop/insignia-estrela.webp', null, null, 70),
  ('8fb84dff-d473-48d4-8dc2-467c4f5d66da', 'badge', 'common', 'Lua crescente', 'A lua crescente e duas estrelas, para quem lê até tarde.', 60, 'on_sale', '/shop/insignia-lua.webp', null, null, 80),
  ('0d0eb37f-bde2-4a02-8b23-3299552d5312', 'badge', 'common', 'Livro aberto', 'Um livro aberto, o símbolo de quem nunca larga uma história.', 60, 'on_sale', '/shop/insignia-livro.webp', null, null, 90),
  ('8d56b735-800a-4f4a-a1d2-535a7394c781', 'badge', 'common', 'Chave antiga', 'Uma chave antiga que abre portas para outros mundos.', 80, 'on_sale', '/shop/insignia-chave.webp', null, null, 100),
  ('2ba24177-6b08-4bcf-b325-3d615e02828a', 'badge', 'common', 'Pena de escrever', 'A pena de quem também escreve as próprias histórias.', 100, 'on_sale', '/shop/insignia-pena.webp', null, null, 110),
  ('0b9bbcf4-d431-44c3-893b-37520e99a99a', 'badge', 'rare', 'Coruja sábia', 'Uma coruja de olhos atentos, guardiã das bibliotecas.', 300, 'on_sale', '/shop/insignia-coruja.webp', null, null, 120),
  ('2e6653dd-58d8-45b4-9556-a06dc60eeb71', 'badge', 'rare', 'Rosa encantada', 'Uma rosa saída de um conto de fadas, ao lado do seu nome.', 350, 'on_sale', '/shop/insignia-rosa.webp', null, null, 130),
  ('67bf68e9-5406-427a-a293-1731b7cea013', 'badge', 'rare', 'Dragão', 'Um dragão dourado para quem vive grandes aventuras de fantasia.', 450, 'on_sale', '/shop/insignia-dragao.webp', null, null, 140),
  ('76c6d89e-4ee2-49a7-9c9f-5c45eaacd1aa', 'cover', 'rare', 'Noite estrelada', 'Um céu cheio de estrelas, a lua crescente e uma torre de biblioteca ao longe.', 400, 'on_sale', '/shop/capa-noite-estrelada.webp', null, null, 150),
  ('78ff14b4-6384-465c-bbfe-8237d05bd006', 'cover', 'rare', 'Folhas de outono', 'Folhas douradas e vermelhas caindo num fim de tarde de outono.', 400, 'on_sale', '/shop/capa-folhas-de-outono.webp', null, null, 160),
  ('a2cab125-f8ae-4891-a837-8d6d522dd84f', 'cover', 'rare', 'Biblioteca antiga', 'Estantes cheias de livros antigos sob a luz quente de um lampião.', 500, 'on_sale', '/shop/capa-biblioteca-antiga.webp', null, null, 170),
  ('9560f7bb-ad32-4614-9cc0-5fe67cb74d88', 'name_color', 'common', 'Nome dourado', 'Seu nome em dourado no chat, no ranking e nas listas.', 120, 'on_sale', null, null, array['#e4b86a'], 180),
  ('8930fe69-2156-44fb-b451-ec187326c052', 'name_color', 'rare', 'Nome aurora', 'Seu nome num degradê de dourado, rosa e azul.', 450, 'on_sale', null, null, array['#f1cf88','#e88ab4','#8fb8e8'], 190),
  ('01179c8a-79bf-4f6b-9eda-959db5a0fc7c', 'name_color', 'rare', 'Nome brasa', 'Seu nome num degradê quente, da cor da brasa de uma lareira.', 350, 'on_sale', null, null, array['#f7c873','#f08a5d'], 200)
on conflict (id) do nothing;
