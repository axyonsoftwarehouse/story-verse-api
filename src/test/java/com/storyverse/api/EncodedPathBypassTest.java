package com.storyverse.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.billing.BillingService;
import com.storyverse.api.catalog.CatalogCollectionService;
import com.storyverse.api.catalog.CatalogService;

/**
 * S1 da revisão de 09/11/2026: os filtros decidiam pela URL crua e o MVC atendia a URL
 * decodificada. `/%61pi/...`, `/text;x` e `/tex%74` passavam sem paywall e sem rate limit.
 *
 * <p>O {@link CatalogService} é simulado e devolve o texto sem conferir nada: se o
 * {@code BillingAccessFilter} deixar passar, a resposta é 200. Só o filtro produz o 402 aqui.
 */
@SpringBootTest(properties = "app.billing.enabled=true")
@AutoConfigureMockMvc
class EncodedPathBypassTest {

	private static final UUID SEM_PLANO = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final String TOKEN = "Bearer token-sem-plano";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private AuthService auth;

	@MockitoBean
	private BillingService billing;

	@MockitoBean
	private CatalogService catalog;

	@MockitoBean
	private CatalogCollectionService collections;

	@BeforeEach
	void contaSemAssinatura() {
		when(auth.authenticate("token-sem-plano")).thenReturn(SEM_PLANO);
		doThrow(new AuthException(402, "Assinatura necessária.", "SUBSCRIPTION_REQUIRED"))
				.when(billing).requireAccess(SEM_PLANO);
		when(catalog.text(anyString(), anyString())).thenReturn("texto integral");
		when(catalog.get(anyString())).thenReturn(new HashMap<>(Map.of("book_key", "cat:1")));
		when(collections.collectionsForBookKey(anyString())).thenReturn(List.of());
	}

	private static MockHttpServletRequestBuilder cru(String rawPath) {
		return get(URI.create(rawPath)).header("Authorization", TOKEN);
	}

	@Test
	void prefixoApiCodificadoPassaPeloPaywall() throws Exception {
		mvc.perform(cru("/%61pi/v1/groups")).andExpect(status().isPaymentRequired());
	}

	@Test
	void textoComParametroDeMatrizPassaPeloPaywall() throws Exception {
		mvc.perform(cru("/api/v1/catalog/books/cat:1/text;x")).andExpect(status().isPaymentRequired());
		mvc.perform(cru("/api/v1/catalog/books/cat:1/text;x=1")).andExpect(status().isPaymentRequired());
	}

	@Test
	void textoComLetraCodificadaPassaPeloPaywall() throws Exception {
		mvc.perform(cru("/api/v1/catalog/books/cat:1/tex%74")).andExpect(status().isPaymentRequired());
		mvc.perform(cru("/%61pi/v1/catalog/books/cat:1/text")).andExpect(status().isPaymentRequired());
	}

	@Test
	void textoNormalContinuaProtegido() throws Exception {
		mvc.perform(cru("/api/v1/catalog/books/cat:1/text")).andExpect(status().isPaymentRequired());
		mvc.perform(cru("/api/v1/catalog/books/cat%3A1/text")).andExpect(status().isPaymentRequired());
	}

	/** O site codifica a chave do livro (`cat:1` vira `cat%3A1`): a ficha pública não pode quebrar. */
	@Test
	void fichaPublicaComChaveCodificadaContinuaAberta() throws Exception {
		mvc.perform(get(URI.create("/api/v1/catalog/books/cat%3A1"))).andExpect(status().isOk());
	}

	@Test
	void limiteDeCadastroPorIpValeParaPrefixoCodificado() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(cadastro("/%61pi/v1/auth/signup"));
		}
		mvc.perform(cadastro("/%61pi/v1/auth/signup")).andExpect(status().isTooManyRequests());
	}

	private static MockHttpServletRequestBuilder cadastro(String rawPath) {
		return post(URI.create(rawPath)).contentType(MediaType.APPLICATION_JSON).content("{}")
				.with(request -> {
					request.setRemoteAddr("198.51.100.77");
					return request;
				});
	}
}
