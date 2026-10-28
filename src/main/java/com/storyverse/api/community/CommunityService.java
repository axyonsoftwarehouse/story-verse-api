package com.storyverse.api.community;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.storyverse.api.auth.AuthException;
import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthzService;
import com.storyverse.api.catalog.CatalogRepository;
import com.storyverse.api.common.DbErrors;
import com.storyverse.api.common.DbSession;
import com.storyverse.api.storage.StorageService;
import com.storyverse.api.storage.StorageBucket;
import com.storyverse.api.storage.StorageAccess;

/**
 * Acervo da comunidade. O runtime usa o papel `storyverse_api` (sem ownership), então a RLS
 * continua valendo; as regras explícitas abaixo são a primeira barreira, espelhando as policies
 * de book_submissions redefinidas na V16 (V16__admin_roles.sql), que usam public.can_moderate().
 */
@Service
public class CommunityService {

	private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

	private final CommunityRepository repository;
	private final AuthService auth;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final StorageService storage;
	private final StorageAccess access;
	private final CoverUrlPolicy covers;
	private final CatalogRepository catalog;

	public CommunityService(CommunityRepository repository, AuthService auth, AuthzService authz, JdbcClient jdbc,
			StorageService storage, StorageAccess access, CoverUrlPolicy covers, CatalogRepository catalog) {
		this.repository = repository;
		this.auth = auth;
		this.authz = authz;
		this.jdbc = jdbc;
		this.storage = storage;
		this.access = access;
		this.covers = covers;
		this.catalog = catalog;
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(String accessToken, String scope) {
		return switch (scope) {
			case "approved" -> repository.listApproved();
			case "mine" -> repository.listMine(requireUser(accessToken));
			case "pending" -> {
				UUID userId = requireUser(accessToken);
				yield canModerate(userId) ? repository.listPending() : repository.listPendingFor(userId);
			}
			default -> throw new AuthException(400, "Consulta inválida.");
		};
	}

	@Transactional
	public Map<String, Object> create(String accessToken, Map<String, Object> body) {
		UUID userId = requireUser(accessToken);
		String title = text(body.get("title"));
		String textPath = text(body.get("text_path"));
		if (title == null) {
			throw new AuthException(400, "Informe o título do livro.");
		}
		if (textPath == null) {
			throw new AuthException(400, "O arquivo do livro está faltando.");
		}
		textPath = StorageService.requireOwn(userId, textPath);
		access.lockCommunity(textPath);
		access.assertWritable(StorageBucket.COMMUNITY_BOOKS, textPath);
		if (!storage.exists(StorageBucket.COMMUNITY_BOOKS, textPath)) throw new AuthException(400, "Arquivo não encontrado.");
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
		params.put("cover_url", covers.require(text(body.get("cover_url"))));
		try {
			return repository.insert(params).orElseThrow(() -> new AuthException(400, "Não foi possível enviar o livro."));
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> review(String accessToken, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(accessToken);
		if (!canModerate(userId)) {
			throw new AuthException(403, "Sem permissão para revisar envios.");
		}
		Map<String, Object> candidate = repository.find(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		String textPath = String.valueOf(candidate.get("text_path"));
		access.lockCommunity(textPath);
		Map<String, Object> row = repository.findForUpdate(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		textPath = String.valueOf(row.get("text_path"));
		String authorId = String.valueOf(row.get("user_id"));
		// Linhas antigas podiam apontar para a pasta de outra pessoa; não aprove um arquivo alheio.
		if (!textPath.startsWith(authorId + "/"))
			throw new AuthException(409, "O envio aponta para um arquivo fora da pasta do autor.");
		if (!storage.exists(StorageBucket.COMMUNITY_BOOKS, textPath)) throw new AuthException(409, "O texto do livro não está disponível.");
		String status = text(body.get("status"));
		if (!"approved".equals(status) && !"rejected".equals(status)) {
			throw new AuthException(400, "Situação inválida.");
		}
		String note = limit(text(body.get("review_note")), 500);
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("id", id);
		params.put("status", status);
		params.put("note", note);
		params.put("cover", covers.require(text(body.get("cover_url"))));
		params.put("reviewed_by", userId);
		try {
			Map<String, Object> reviewed = repository.review(params)
					.orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
			if ("approved".equals(status)) {
				catalog.promoteApproved(id);
			}
			authz.audit(userId, "book_submission.review", "book_submission", id.toString(),
					Map.of("status", status, "note", note == null ? "" : note));
			return reviewed;
		} catch (DataAccessException exception) {
			throw new AuthException(400, DbErrors.message(exception));
		}
	}

	@Transactional
	public Map<String, Object> updateCover(String accessToken, UUID id, Map<String, Object> body) {
		UUID userId = requireUser(accessToken);
		Map<String, Object> row = repository.findForUpdate(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		boolean owner = isOwner(row, userId);
		if (!owner && !canModerate(userId)) {
			throw new AuthException(403, "Sem permissão para trocar a capa.");
		}
		String cover = covers.require(text(body.get("cover_url")));
		if (cover == null) {
			throw new AuthException(400, "Capa ausente.");
		}
		Map<String, Object> updated = repository.updateCover(id, cover)
				.orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		// O dono não é auditado: a policy de insert da auditoria só aceita equipe.
		if (!owner) {
			authz.audit(userId, "book_submission.cover", "book_submission", id.toString(), Map.of("cover_url", cover));
		}
		return updated;
	}

	@Transactional
	public void delete(String accessToken, UUID id) {
		UUID userId = requireUser(accessToken);
		// Mesma ordem de locks de StorageService (dono do arquivo → comunidade → linha), para não
		// cruzar com review/write e evitar deadlock. Lê sem lock só para saber o caminho.
		Map<String, Object> candidate = repository.find(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		String textPath = String.valueOf(candidate.get("text_path"));
		String fileOwner = textPath.contains("/") ? textPath.split("/", 2)[0] : textPath;
		access.lock("storage-owner:" + fileOwner);
		access.lockCommunity(textPath);
		Map<String, Object> row = repository.findForUpdate(id).orElseThrow(() -> new AuthException(404, "Envio não encontrado."));
		boolean pending = "pending".equals(row.get("status"));
		boolean owner = isOwner(row, userId);
		if (!canModerate(userId) && !(owner && pending)) {
			throw new AuthException(403, "Sem permissão para remover este envio.");
		}
		String authorId = String.valueOf(row.get("user_id"));
		String coverPath = uploadedCoverPath(String.valueOf(row.get("cover_url")));
		repository.delete(id);
		// O dono não é auditado: a policy de insert da auditoria só aceita equipe.
		if (!owner) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("title", String.valueOf(row.get("title")));
			metadata.put("status", String.valueOf(row.get("status")));
			authz.audit(userId, "book_submission.delete", "book_submission", id.toString(), metadata);
		}
		// Arquivos não voltam com rollback: só saem do disco depois do commit da linha e da auditoria.
		// A permissão já foi conferida acima e os caminhos vêm da linha; só apaga o que é da pasta do autor.
		if (textPath.startsWith(authorId + "/")) {
			storage.removeAfterCommit(StorageBucket.COMMUNITY_BOOKS, textPath);
		} else {
			log.warn("Texto do envio {} fora da pasta do autor; arquivo mantido.", id);
		}
		if (coverPath != null) {
			if (coverPath.startsWith(authorId + "/")) {
				storage.removeAfterCommit(StorageBucket.PROFILE_MEDIA, coverPath);
			} else {
				log.warn("Capa do envio {} fora da pasta do autor; arquivo mantido.", id);
			}
		}
	}

	private boolean canModerate(UUID userId) {
		DbSession.setUser(jdbc, userId);
		return Boolean.TRUE.equals(jdbc.sql("select public.can_moderate()")
				.query((rs, rowNum) -> rs.getObject(1, Boolean.class))
				.optional()
				.orElse(false));
	}

	private static boolean isOwner(Map<String, Object> row, UUID userId) {
		return userId.toString().equals(String.valueOf(row.get("user_id")));
	}
	private UUID requireUser(String token) {
		UUID id = auth.authenticate(token);
		DbSession.setUser(jdbc, id);
		return id;
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

	/** Caminho local de uma capa enviada pelo app; null para capas externas (Open Library). */
	private static String uploadedCoverPath(String cover) {
		if (cover == null) return null;
		for (String prefix : new String[]{
				"/api/v1/storage/public/profile-media/",
				"/storage/v1/object/public/profile-media/"}) {
			int at = cover.indexOf(prefix);
			if (at >= 0) {
				String path = cover.substring(at + prefix.length());
				int query = path.indexOf('?');
				return query >= 0 ? path.substring(0, query) : path;
			}
		}
		return null;
	}
}
