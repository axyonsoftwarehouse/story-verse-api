-- Segundo lote da loja (15 itens). Fonte: design/shop-items/README.md do site.
-- Ids fixos: reaplicar não duplica.

insert into public.shop_items
  (id, category, tier, name, description, price, status, image_url, animated_url, colors, sort_order)
values
  ('55da46f9-48d2-4169-a08c-d08a96be72d6', 'frame', 'common', 'Anel de cobre', 'Um anel cor de cobre, quente como a capa de um livro antigo.', 90, 'on_sale', '/shop/moldura-anel-cobre.webp', null, null, 35),
  ('c55fd200-1ba5-4e6e-a574-b649267da579', 'frame', 'rare', 'Vinhas', 'Folhas em volta da foto, como um jardim de conto.', 450, 'on_sale', '/shop/moldura-vinhas.webp', null, null, 55),
  ('1daddf1d-cfca-4a78-80d6-00a7ddb83eb2', 'frame', 'epic', 'Anel de runas', 'Runas douradas que giram devagar em volta da sua foto.', 1200, 'on_sale', '/shop/moldura-runas.webp', '/shop/moldura-runas-animada.webp', null, 65),
  ('f6d45c6e-ded2-4bac-8e5d-8905725bb876', 'badge', 'common', 'Xícara de chá', 'Uma xícara fumegante, para quem lê com calma.', 70, 'on_sale', '/shop/insignia-xicara.webp', null, null, 115),
  ('a8b5277c-499c-41f0-aea7-235ae8f5311a', 'badge', 'common', 'Vela', 'A vela que ilumina a página na hora tardia.', 90, 'on_sale', '/shop/insignia-vela.webp', null, null, 116),
  ('c608235a-b2ae-4536-9ad9-8ed8046b30be', 'badge', 'rare', 'Gato da biblioteca', 'O gato que dorme entre as estantes e guarda o seu nome.', 400, 'on_sale', '/shop/insignia-gato.webp', null, null, 135),
  ('24f77184-434d-4ef2-b36a-80a51fa612ba', 'badge', 'rare', 'Bússola', 'Uma bússola para quem segue a história sem mapa.', 350, 'on_sale', '/shop/insignia-bussola.webp', null, null, 136),
  ('19bd533e-b735-4e93-b5b8-bd2b7e9a98a1', 'badge', 'epic', 'Fênix', 'Uma fênix em chamas, para quem sempre volta à próxima página.', 900, 'on_sale', '/shop/insignia-fenix.webp', null, null, 145),
  ('00e96c1f-3e93-4b15-9270-e703dbaa50ef', 'cover', 'common', 'Pergaminho', 'Um pergaminho claro, como a primeira página de um caderno.', 150, 'on_sale', '/shop/capa-pergaminho.webp', null, null, 155),
  ('99da01f9-2120-4a9b-8df5-82d39e74fba9', 'cover', 'rare', 'Floresta encantada', 'Pinheiros escuros sob uma lua dourada.', 480, 'on_sale', '/shop/capa-floresta.webp', null, null, 165),
  ('3931f6d1-ff54-46cd-8e72-10c6e63fe981', 'cover', 'rare', 'Chuva na janela', 'Vidro molhado, um livro aberto e uma xícara na mesa.', 550, 'on_sale', '/shop/capa-chuva-na-janela.webp', null, null, 166),
  ('0e7b4192-3f08-497a-93e6-85b1a402505d', 'cover', 'epic', 'Biblioteca infinita', 'Estantes que somem no escuro, com um lampião no centro.', 1000, 'on_sale', '/shop/capa-biblioteca-infinita.webp', null, null, 175),
  ('7c829960-ca35-4545-af7b-ba23c58df1b1', 'name_color', 'common', 'Nome luar', 'Seu nome na cor clara da lua.', 100, 'on_sale', null, null, array['#d7dee8'], 185),
  ('0fe5a5ae-93d9-4225-aed3-27b731d78824', 'name_color', 'rare', 'Nome floresta', 'Seu nome num degradê de verde-claro, como folha nova.', 400, 'on_sale', null, null, array['#d8ffe8','#8ee0b0'], 195),
  ('2da33550-1023-47bc-97a1-478fb9294eeb', 'name_color', 'epic', 'Nome constelação', 'Seu nome num degradê de prata, azul e violeta.', 800, 'on_sale', null, null, array['#f4f7fb','#9ecbff','#e4c8ff'], 205)
on conflict (id) do nothing;
