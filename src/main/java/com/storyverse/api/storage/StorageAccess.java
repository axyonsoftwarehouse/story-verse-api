package com.storyverse.api.storage;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.storyverse.api.auth.AuthException;

/** Database locks also serialize writers across application instances. */
@Component
public class StorageAccess {
  private final JdbcClient jdbc;
  public StorageAccess(JdbcClient jdbc) { this.jdbc = jdbc; }

  public void lock(String key) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", key).query((rs, n) -> 1).single();
  }

  public void lockCommunity(String path) { lock("storage:community-books:" + path); }
  public void setUser(UUID user) { com.storyverse.api.common.DbSession.setUser(jdbc, user); }

  /** Superadmin ou moderador (public.can_moderate(), V16). */
  public boolean canModerate(UUID user) {
    com.storyverse.api.common.DbSession.setUser(jdbc, user);
    return Boolean.TRUE.equals(jdbc.sql("select public.can_moderate()").query(Boolean.class).single());
  }

  public boolean superadmin(UUID user) {
    com.storyverse.api.common.DbSession.setUser(jdbc, user);
    return Boolean.TRUE.equals(jdbc.sql("select public.has_role('superadmin')").query(Boolean.class).single());
  }

  public boolean readable(UUID user, StorageBucket bucket, String path) {
    com.storyverse.api.common.DbSession.setUser(jdbc, user);
    if (bucket == StorageBucket.CATALOG_BOOKS) return canModerate(user);
    if (bucket.publiclyReadable() || path.startsWith(user + "/")) return true;
    return bucket == StorageBucket.COMMUNITY_BOOKS && (canModerate(user) || approvedOwnedByAuthor(path));
  }

  /** Aprovado E o caminho pertence à pasta do autor do envio (bloqueia linhas legadas cruzadas). */
  private boolean approvedOwnedByAuthor(String path) {
    return jdbc.sql("""
        select exists(select 1 from public.book_submissions
                       where text_path = :path and status = 'approved'
                         and text_path like user_id::text || '/%')
        """).param("path", path).query(Boolean.class).single();
  }

  public void assertWritable(StorageBucket bucket, String path) {
    if (bucket == StorageBucket.COMMUNITY_BOOKS && exists(path, "true"))
      throw new AuthException(409, "O texto enviado para revisão é imutável. Envie uma nova versão.");
  }

  public boolean deletable(UUID user, StorageBucket bucket, String path) {
    com.storyverse.api.common.DbSession.setUser(jdbc, user);
    if (bucket == StorageBucket.CATALOG_BOOKS) return superadmin(user);
    if (bucket == StorageBucket.COMMUNITY_BOOKS) {
      if (exists(path, "status = 'approved'")) return false;
      return path.startsWith(user + "/") || canModerate(user);
    }
    return path.startsWith(user + "/") || (bucket == StorageBucket.PROFILE_MEDIA
        && path.matches("[^/]+/livros/.+") && superadmin(user));
  }

  private boolean exists(String path, String predicate) {
    // predicate is a server-owned constant, never request input.
    return jdbc.sql("select exists(select 1 from public.book_submissions where text_path = :path and " + predicate + ")")
        .param("path", path).query(Boolean.class).single();
  }
}
