package com.storyverse.api.community;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.common.DbErrors;
import com.storyverse.api.common.DbSession;

/**
 * Acervo da comunidade. Como a API conecta como dono do banco (sem RLS), as regras de quem vê,
 * envia, revisa e apaga ficam aqui — espelhando as policies de supabase/community-books.sql.
 */
@Service
public class CommunityService {

	private final CommunityRepository repository;
	private final AuthService auth;
	private final JdbcClient jdbc;

	public CommunityService(CommunityRepository repository, AuthService auth, JdbcClient jdbc) {
		this.repository = repository;
		this.auth = auth;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(String accessToken, String scope) {
		return switch (scope) {
			case "approved" -> repository.listApproved();
			case "mine" -> repository.listMine(auth.authenticate(accessToken));
			case "pending" -> {
				UUID userId = auth.authenticate(accessToken);
				yield isAdmin(userId) ? repository.listPending() : repository.listPendingFor(userId);
			}
			default -> throw new AuthException(400, "Consulta inválida.");
		};
	}

	@Transactional
	public Map<String, Object> create(String accessToken, Map<String, Object> body) {
		UUID userId = auth.authenticate(accessToken);
		String title = text(body.get("title"));
		String textPath = text(body.get("text_path"));
		if (title == null) {
			throw new AuthException(400, "Informe o título do livro.");
		}
		if (textPath == null) {
			throw new AuthException(400, "O arquivo do livro está faltando.");
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("uid", userId);
		params.put("submitter_name", limit(text(body.get("submitter_name")), 60));
		params.put("title", limit(title, 120));
		params.put("author", limit(text(body.get("author")), 120));
		params.put("language", "en".equals(body.get("language")) ? "en" : "pt");
		params.put("rights", text(body.get("rights")));
		params.put("rights_note", limit(text(body.get("rights_note")), 500));
		params.put("characters", limit(text(body.get("characters")), 600));
		params.put("text_path", textPath);
		params.put("char_count", body.get("char_count"));
		params.put("cover_url", limit(text(body.get("cover_url")), 300));
		try {
			return repository.insert(params).orElseThrow(() -> new AuthException(400, "Não foi possível enviar o livro."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> review(String accessToken, UUID id, Map<String, Object> body) {
		UUID userId = auth.authenticate(accessToken);
		if (!isAdmin(userId)) {
			throw new AuthException(403, "Sem permissão para revisar envios.");
		}
		String status = text(body.get("status"));
		if (!"approved".equals(status) && !"rejected".equals(status)) {
			throw new AuthException(400, "Situação inválida.");
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("status", status);
		params.put("note", limit(text(body.get("review_note")), 500));
		params.put("cover", limit(text(body.get("cover_url")), 300));
		try {
			return repository.review(params).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> updateCover(String accessToken, UUID id, Map<String, Object> body) {
		UUID userId = auth.authenticate(accessToken);
		Map<String, Object> row = repository.find(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		if (!isOwner(row, userId) && !isAdmin(userId)) {
			throw new AuthException(403, "Sem permissão para trocar a capa.");
		}
		String cover = limit(text(body.get("cover_url")), 300);
		if (cover == null) {
			throw new AuthException(400, "Capa ausente.");
		}
		return repository.updateCover(id, cover).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
	}

	@Transactional
	public void delete(String accessToken, UUID id) {
		UUID userId = auth.authenticate(accessToken);
		Map<String, Object> row = repository.find(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		boolean pending = "pending".equals(row.get("status"));
		if (!isAdmin(userId) && !(isOwner(row, userId) && pending)) {
			throw new AuthException(403, "Sem permissão para remover este envio.");
		}
		repository.delete(id);
	}

	private boolean isAdmin(UUID userId) {
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("select public.is_admin()")
				.query((rs, rowNum) -> rs.getObject(1, Boolean.class))
				.optional()
				.orElse(false));
	}

	private static boolean isOwner(Map<String, Object> row, UUID userId) {
		return userId.toString().equals(String.valueOf(row.get("user_id")));
	}

	private static String text(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return text.isEmpty() ? null : text;
	}

	private static String limit(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() > max ? value.substring(0, max) : value;
	}
}
