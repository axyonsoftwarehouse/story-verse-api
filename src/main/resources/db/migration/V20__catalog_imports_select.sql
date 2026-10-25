-- A importacao em lote usa INSERT ... RETURNING *; o papel authenticated precisava de SELECT
-- em catalog_imports/catalog_import_items para o RETURNING e para o historico.
grant select on public.catalog_imports, public.catalog_import_items to authenticated;
