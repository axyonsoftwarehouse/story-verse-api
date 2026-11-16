-- Tema visual do perfil público: auth.users.raw_user_meta_data->>'profile_theme'
-- Valores permitidos: dourado (padrão), esmeralda, rosa, oceano, lavanda.

update auth.users
   set raw_user_meta_data = raw_user_meta_data - 'profile_theme'
 where raw_user_meta_data ? 'profile_theme'
   and trim(raw_user_meta_data->>'profile_theme') not in ('dourado', 'esmeralda', 'rosa', 'oceano', 'lavanda');