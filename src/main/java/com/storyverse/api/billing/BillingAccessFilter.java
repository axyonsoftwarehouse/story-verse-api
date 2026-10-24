package com.storyverse.api.billing;

import java.io.IOException;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Paywall total no servidor. Roda depois do rate limit/segurança; rotas públicas e de conta ficam
 * de fora. Staff passa direto pela regra do {@link BillingService}. Com `BILLING_ENABLED=false`
 * o filtro não faz nada (fase de beta).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BillingAccessFilter extends OncePerRequestFilter {

	private final BillingService billing;
	private final AuthService auth;
	private final boolean enabled;

	public BillingAccessFilter(BillingService billing, AuthService auth,
			@Value("${app.billing.enabled:false}") boolean enabled) {
		this.billing = billing;
		this.auth = auth;
		this.enabled = enabled;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		if (!enabled || !path.startsWith("/api/") || "OPTIONS".equals(request.getMethod()) || exempt(request.getMethod(), path)) {
			chain.doFilter(request, response);
			return;
		}
		try {
			String token = AuthTokens.bearer(request.getHeader("Authorization"));
			if (token == null || token.isBlank()) throw new AuthException(401, "Não autenticado.");
			UUID user = auth.authenticate(token);
			billing.requireAccess(user);
			chain.doFilter(request, response);
		} catch (AuthException error) {
			if (!response.isCommitted()) {
				response.setStatus(error.status());
				response.setContentType("application/json;charset=UTF-8");
				String code = error.code() == null ? "" : ",\"code\":\"" + error.code() + "\"";
				response.getWriter().write("{\"error\":\"" + escape(error.getMessage()) + "\"" + code + "}");
			}
		}
	}

	static boolean exempt(String method, String path) {
		if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/billing/")
				|| path.startsWith("/api/v1/account") || path.startsWith("/api/v1/health")
				|| path.startsWith("/api/v1/presence") || path.startsWith("/api/v1/storage/public/")
				|| path.startsWith("/api/v1/admin/") || path.equals("/api/v1/ai/status")) {
			return true;
		}
		if ("GET".equals(method) && path.startsWith("/api/v1/community/submissions")) {
			return true;
		}
		if ("GET".equals(method) && path.startsWith("/api/v1/catalog/") && !path.endsWith("/text")) {
			return true;
		}
		return false;
	}

	private static String escape(String message) {
		return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
