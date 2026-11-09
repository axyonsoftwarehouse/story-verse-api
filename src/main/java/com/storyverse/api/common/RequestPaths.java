package com.storyverse.api.common;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

/**
 * Caminho da requisição do jeito que o Spring MVC enxerga para casar a rota: decodificado, sem o
 * conteúdo de matriz (`;x=1`) e com barras repetidas reduzidas. Os filtros de segurança precisam
 * decidir sobre este valor, nunca sobre {@link HttpServletRequest#getRequestURI()} (cru): com o cru,
 * `/%61pi/v1/...` e `/text;x=1` passavam pelos filtros e o MVC atendia como `/api/v1/.../text`.
 */
public final class RequestPaths {

	private static final UrlPathHelper HELPER = new UrlPathHelper();

	static {
		HELPER.setUrlDecode(true);
		HELPER.setRemoveSemicolonContent(true);
	}

	private RequestPaths() {
	}

	public static String normalized(HttpServletRequest request) {
		return HELPER.getPathWithinApplication(request);
	}
}
