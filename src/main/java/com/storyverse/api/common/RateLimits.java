package com.storyverse.api.common;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.storyverse.api.auth.AuthException;

/** Bounded local protection; persistent paid-use budgets are enforced separately in Postgres. */
@Component
public class RateLimits {
  private record Window(long expires, int count) {}
  private final Map<String, Window> windows = new HashMap<>();
  public synchronized void check(String key, int limit, Duration period) {
    long now = System.currentTimeMillis();
    Window previous = windows.get(key);
    if (previous == null || previous.expires <= now) {
      if (windows.size() >= 10_000) windows.entrySet().removeIf(e -> e.getValue().expires <= now);
      if (windows.size() >= 10_000 && previous == null) throw new AuthException(429, "Muitas requisições. Tente mais tarde.");
      windows.put(key, new Window(now + period.toMillis(), 1));
    } else {
      if (previous.count >= limit) throw new AuthException(429, "Muitas tentativas. Aguarde e tente novamente.");
      windows.put(key, new Window(previous.expires, previous.count + 1));
    }
  }
}
