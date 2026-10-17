package com.storyverse.api.ai;

import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.RateLimits;
import java.time.Duration;

@Component
public class AiBudget {
  private final JdbcClient jdbc;
  private final RateLimits limits;
  private final Semaphore concurrent = new Semaphore(4);
  public AiBudget(JdbcClient jdbc, RateLimits limits) { this.jdbc = jdbc; this.limits = limits; }
  @Transactional
  public void reserve(UUID user) {
    com.storyverse.api.common.DbSession.setUser(jdbc, user);
    limits.check("ai:" + user, 6, Duration.ofMinutes(1));
    var count = jdbc.sql("""
        insert into public.api_daily_usage(user_id, day, feature, used)
        values (:uid, (now() at time zone 'America/Sao_Paulo')::date, 'ai', 1)
        on conflict (user_id, day, feature) do update set used = api_daily_usage.used + 1
        where api_daily_usage.used < 50 returning used
        """).param("uid", user).query(Integer.class).optional();
    if (count.isEmpty()) throw new AuthException(429, "Limite diário de mensagens atingido.");
  }
  public void acquire() { if (!concurrent.tryAcquire()) throw new AuthException(429, "Serviço ocupado. Tente em instantes."); }
  public void release() { concurrent.release(); }
}
