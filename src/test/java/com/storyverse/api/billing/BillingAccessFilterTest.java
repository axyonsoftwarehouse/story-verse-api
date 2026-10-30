package com.storyverse.api.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.storyverse.api.auth.AuthService;

class BillingAccessFilterTest {

	private static final String ORIGEM = "https://qa.example.test";

	private BillingAccessFilter filtroLigado() {
		return new BillingAccessFilter(mock(BillingService.class), mock(AuthService.class), true,
				new String[] { ORIGEM });
	}

	@Test
	void isentaRotasPublicasEConta() {
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/collections"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books/cat:1"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/community/submissions"));
		assertTrue(BillingAccessFilter.exempt("POST", "/api/v1/billing/checkout"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/account"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/admin/contas"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/storage/public/profile-media/a"));
		assertTrue(BillingAccessFilter.exempt("GET", "/api/v1/push/key"));
		assertTrue(BillingAccessFilter.exempt("POST", "/api/v1/push/subscriptions"));
	}

	@Test
	void protegeOTextoEDemaisRotasDoProduto() {
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/catalog/books/cat:1/text"));
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/me/library"));
		assertFalse(BillingAccessFilter.exempt("POST", "/api/v1/community/submissions"));
		assertFalse(BillingAccessFilter.exempt("GET", "/api/v1/ai/status/extra"));
	}

	/**
	 * O filtro responde antes do MVC, então precisa repetir o CORS do CorsConfig: sem isso o
	 * navegador esconde o 401/402 e o app mostra "sem conexão" no lugar de "entre na sua conta".
	 */
	@Test
	void bloqueioTrazCorsDaOrigemPermitida() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/forum/topics");
		request.addHeader("Origin", ORIGEM);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean seguiu = new AtomicBoolean(false);

		filtroLigado().doFilter(request, response, (req, res) -> seguiu.set(true));

		assertEquals(401, response.getStatus());
		assertEquals(ORIGEM, response.getHeader("Access-Control-Allow-Origin"));
		assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
		assertTrue(response.getHeaders("Vary").contains("Origin"));
		assertFalse(seguiu.get(), "sem token a requisição não pode seguir");
	}

	@Test
	void origemForaDaListaNaoGanhaCors() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/forum/topics");
		request.addHeader("Origin", "https://invasor.example");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filtroLigado().doFilter(request, response, (req, res) -> { });

		assertEquals(401, response.getStatus());
		assertNull(response.getHeader("Access-Control-Allow-Origin"));
	}

	@Test
	void rotaIsentaPassaDireto() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/catalog/books");
		request.addHeader("Origin", ORIGEM);
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean seguiu = new AtomicBoolean(false);

		filtroLigado().doFilter(request, response, (req, res) -> seguiu.set(true));

		assertTrue(seguiu.get(), "catálogo é público");
	}

	@Test
	void desligadoNaoBloqueiaNada() throws Exception {
		BillingAccessFilter filtro = new BillingAccessFilter(mock(BillingService.class),
				mock(AuthService.class), false, new String[] { ORIGEM });
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/forum/topics");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean seguiu = new AtomicBoolean(false);

		filtro.doFilter(request, response, (req, res) -> seguiu.set(true));

		assertTrue(seguiu.get(), "com BILLING_ENABLED=false o filtro não faz nada");
	}
}
