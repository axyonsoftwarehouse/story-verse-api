package com.storyverse.api.admin;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;

import tools.jackson.databind.JsonNode;

/**
 * Web Vitals (p75 de LCP, INP e CLS) lidos do Umami no servidor, a partir dos envios de performance
 * do tracker ({@code data-performance}). Só superadmin, período das métricas (datas de São Paulo,
 * máximo de 90 dias) e cache de 5 minutos; falha fica em cache por 1 minuto.
 */
@Service
public class AdminVitalsService {

	private static final Logger log = LoggerFactory.getLogger(AdminVitalsService.class);

	private record Result(AdminVitals.Vitals vitals, String error) {
	}

	private final AuthService auth;
	private final AuthzService authz;
	private final UmamiApiClient umami;
	private final TransactionTemplate readOnly;
	private final ShortCache ok = new ShortCache(Duration.ofMinutes(5));
	private final ShortCache failed = new ShortCache(Duration.ofMinutes(1));

	public AdminVitalsService(AuthService auth, AuthzService authz, UmamiApiClient umami,
			PlatformTransactionManager transactions) {
		this.auth = auth;
		this.authz = authz;
		this.umami = umami;
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	/** A permissão é checada numa transação curta; a chamada ao Umami acontece fora dela. */
	public AdminVitals vitals(String token, String fromRaw, String toRaw) {
		AdminRange range = readOnly.execute(status -> {
			UUID actor = auth.authenticate(token);
			authz.requireRole(actor, AuthzService.SUPERADMIN);
			return AdminRange.parse(fromRaw, toRaw);
		});
		Result result = result(range);
		return new AdminVitals(range.from(), range.to(), result.vitals(), result.error());
	}

	private Result result(AdminRange range) {
		if (!umami.configured()) {
			return new Result(null, "Umami não configurado.");
		}
		String key = range.from() + "|" + range.to();
		Result cached = ok.get(key);
		if (cached == null) cached = failed.get(key);
		if (cached != null) return cached;
		Result result;
		try {
			result = new Result(fetch(range), null);
			ok.put(key, result);
		} catch (RuntimeException exception) {
			log.warn("Umami indisponível para os Web Vitals: {}", exception.getMessage());
			result = new Result(null, "Umami indisponível no momento.");
			failed.put(key, result);
		}
		return result;
	}

	/** {@code /performance/stats} do Umami 3: p50/p75/p95 de cada métrica e {@code count} de envios. */
	AdminVitals.Vitals fetch(AdminRange range) {
		long startAt = range.from().atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli();
		long endAt = range.to().plusDays(1).atStartOfDay(AdminRange.SAO_PAULO).toInstant().toEpochMilli() - 1;
		JsonNode body = umami.website("/performance/stats?startAt=" + startAt + "&endAt=" + endAt);
		if (!body.isObject()) throw new UmamiApiClient.UmamiUnavailable("resposta inesperada");
		long samples = Math.max(0, Math.round(number(body.path("count"))));
		if (samples == 0) return new AdminVitals.Vitals(null, null, null, 0);
		// O Umami troca "sem medida" por 0. LCP e INP reais nunca são 0 ms (o tracker só observa
		// interações a partir de 40 ms), então 0 ali é ausência; CLS 0 é um valor legítimo.
		return new AdminVitals.Vitals(
				positive(p75(body, "lcp")),
				positive(p75(body, "inp")),
				p75(body, "cls"),
				samples);
	}

	private static double p75(JsonNode body, String metric) {
		return number(body.path(metric).path("p75"));
	}

	private static Double positive(double value) {
		return value > 0 ? value : null;
	}

	private static double number(JsonNode node) {
		if (node.isNumber()) return node.asDouble();
		if (!node.isValueNode() || node.isNull()) return 0;
		try {
			return Double.parseDouble(node.asText());
		} catch (NumberFormatException exception) {
			return 0;
		}
	}
}
