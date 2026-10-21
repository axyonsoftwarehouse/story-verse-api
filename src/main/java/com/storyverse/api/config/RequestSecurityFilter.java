package com.storyverse.api.config;

import java.io.IOException;
import java.time.Duration;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.RateLimitGate;

@Component
public class RequestSecurityFilter extends OncePerRequestFilter {
  private final RateLimitGate limits;
  public RequestSecurityFilter(RateLimitGate limits) { this.limits = limits; }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Cache-Control", "no-store");
    String path = request.getRequestURI();
    try {
      if (!"OPTIONS".equals(request.getMethod()) && path.startsWith("/api/")) {
        limits.check("ip:" + request.getRemoteAddr(), 300, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/auth/") && "POST".equals(request.getMethod()))
          limits.check("auth:" + request.getRemoteAddr(), 30, Duration.ofMinutes(1));
        if (path.equals("/api/v1/auth/signup"))
          limits.check("signup:" + request.getRemoteAddr(), 5, Duration.ofHours(1));
      }
      if (!path.startsWith("/api/v1/storage/")) {
        // O estado de leitura carrega o snapshot inteiro da conta (livros, conversas, destaques).
        long limit = path.equals("/api/v1/me/reading-state") ? 5L * 1024 * 1024 : 1_048_576;
        if (request.getContentLengthLong() > limit) throw new AuthException(413, "Requisição grande demais.");
        request = new LimitedRequest(request, limit);
      }
      chain.doFilter(request, response);
    } catch (AuthException error) {
      if (!response.isCommitted()) {
        response.setStatus(error.status());
        if (error.status() == 429) response.setHeader("Retry-After", "60");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error.getMessage() + "\"}");
      }
    }
  }

  private static class LimitedRequest extends HttpServletRequestWrapper {
    private ServletInputStream stream;
    private final long limit;
    LimitedRequest(HttpServletRequest request, long limit) { super(request); this.limit = limit; }
    @Override public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        ServletInputStream input = super.getInputStream();
        stream = new ServletInputStream() {
          long remaining = limit;
          public int read() throws IOException {
            int value = input.read();
            if (value != -1 && --remaining < 0) throw new AuthException(413, "Requisição grande demais.");
            return value;
          }
          public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = input.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0) throw new AuthException(413, "Requisição grande demais.");
            return count;
          }
          public boolean isFinished() { return input.isFinished(); }
          public boolean isReady() { return input.isReady(); }
          public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
        };
      }
      return stream;
    }
  }
}
