package com.storyverse.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.billing.BillingService;
import com.storyverse.api.storage.StorageService;

/** S1 (defesa em profundidade): o texto integral confere a assinatura sem depender do filtro. */
class CatalogServiceTextAccessTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

	private final CatalogRepository repository = mock(CatalogRepository.class);
	private final AuthService auth = mock(AuthService.class);
	private final StorageService storage = mock(StorageService.class);
	private final BillingService billing = mock(BillingService.class);
	private final CatalogService service = new CatalogService(repository, auth, mock(AuthzService.class),
			mock(JdbcClient.class), storage, mock(CatalogTextExtractor.class), billing);

	@Test
	void semAssinaturaRecebe402ESemLerOTexto() {
		when(auth.authenticate("t")).thenReturn(USER);
		doThrow(new AuthException(402, "Assinatura necessária.", "SUBSCRIPTION_REQUIRED"))
				.when(billing).requireAccess(USER);

		AuthException error = assertThrows(AuthException.class, () -> service.text("cat:1", "t"));

		assertEquals(402, error.status());
		verifyNoInteractions(repository, storage);
	}

	@Test
	void comAssinaturaEntregaOTexto() {
		when(auth.authenticate("t")).thenReturn(USER);
		when(repository.findTextByKey("cat:1"))
				.thenReturn(Optional.of(Map.of("storage_bucket", "catalog-books", "text_path", "a/b.txt")));
		when(storage.read(any(), any())).thenReturn(Optional.of("Era uma vez".getBytes(StandardCharsets.UTF_8)));

		assertEquals("Era uma vez", service.text("cat:1", "t"));
		verify(billing).requireAccess(USER);
	}
}
