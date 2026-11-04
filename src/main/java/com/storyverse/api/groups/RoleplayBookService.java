package com.storyverse.api.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.storyverse.api.ai.AiBudget;
import com.storyverse.api.ai.AiMessage;
import com.storyverse.api.ai.AiService;
import com.storyverse.api.auth.AuthException;
import com.storyverse.api.common.DbRows;
import com.storyverse.api.common.DbSession;

/**
 * Roleplay que vira livro: a IA reescreve a história do grupo em capítulos de prosa (um pedido
 * por capítulo, na cota de IA de quem criou o grupo). O texto sai no formato do acervo, com
 * "Capítulo N — Título" em linha própria, para o leitor do app dividir os capítulos.
 */
@Service
public class RoleplayBookService {

	private static final Logger log = LoggerFactory.getLogger(RoleplayBookService.class);

	/** No mínimo isto de mensagens para valer um livro. */
	static final int MIN_MESSAGES = 6;
	static final int MAX_MESSAGES = 600;
	static final int MAX_CHAPTERS = 12;
	static final int MIN_PER_CHAPTER = 25;
	static final int MAX_TOKENS = 1600;

	private final JdbcClient jdbc;
	private final GroupRepository repository;
	private final GroupStream stream;
	private final AiService ai;
	private final AiBudget budget;
	private final TransactionTemplate transactions;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	/** Quem escreve nos testes (sem isso, a IA de verdade). */
	private volatile BiFunction<String, List<AiMessage>, String> writer;

	public RoleplayBookService(JdbcClient jdbc, GroupRepository repository, GroupStream stream, AiService ai, AiBudget budget,
			PlatformTransactionManager transactions) {
		this.jdbc = jdbc;
		this.repository = repository;
		this.stream = stream;
		this.ai = ai;
		this.budget = budget;
		this.transactions = new TransactionTemplate(transactions);
	}

	void useWriter(BiFunction<String, List<AiMessage>, String> writer) {
		this.writer = writer;
	}

	/** Livro do grupo (status, título, texto) para quem é membro. */
	public Optional<Map<String, Object>> book(UUID groupId) {
		return DbRows.one(jdbc, """
				select group_id, status, title, text, chapters, error, created_at, updated_at from public.chat_books where group_id = :group
				""", Map.of("group", groupId));
	}

	/**
	 * Encomenda o livro (só o dono; chamado dentro da transação dele). Marca "escrevendo" e a IA
	 * trabalha depois do commit, em segundo plano.
	 */
	public void start(UUID groupId, UUID owner, String groupName) {
		if (writer == null && !ai.live()) throw new AuthException(503, "A IA não está disponível agora.", "AI_UNAVAILABLE");
		book(groupId).filter(b -> "writing".equals(b.get("status"))).ifPresent(b -> {
			throw new AuthException(409, "O livro já está sendo escrito.");
		});
		int total = jdbc.sql("select count(*) from public.chat_messages where group_id = :group and deleted_at is null and body is not null")
				.param("group", groupId).query(Integer.class).single();
		if (total < MIN_MESSAGES) throw new AuthException(400, "A história ainda é curta demais para virar livro.");
		String title = groupName.length() > 120 ? groupName.substring(0, 120) : groupName;
		jdbc.sql("""
				insert into public.chat_books (group_id, status, title, created_by) values (:group, 'writing', :title, :owner)
				on conflict (group_id) do update set status = 'writing', title = excluded.title, text = null, chapters = 0,
				       error = null, created_by = excluded.created_by, updated_at = now()
				""").param("group", groupId).param("title", title).param("owner", owner).update();
		Runnable job = () -> executor.submit(() -> write(groupId, owner));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					job.run();
				}
			});
		} else {
			job.run();
		}
	}

	/** Escreve o livro inteiro (síncrono; os testes chamam direto). */
	void write(UUID groupId, UUID owner) {
		try {
			Map<String, Object> info = inOwner(owner, () -> repository.roleplayInfo(groupId).orElseThrow());
			List<Map<String, Object>> story = inOwner(owner, () -> repository.storySoFar(groupId, MAX_MESSAGES));
			List<Map<String, Object>> players = inOwner(owner, () -> repository.players(groupId));
			List<List<Map<String, Object>>> parts = chapters(story);
			BiFunction<String, List<AiMessage>, String> write = writer != null ? writer
					: (system, messages) -> ai.reply(system, messages, MAX_TOKENS).text();
			StringBuilder text = new StringBuilder();
			String previous = "";
			for (int i = 0; i < parts.size(); i++) {
				budget.reserve(owner, "book-" + UUID.randomUUID());
				String chapter;
				budget.acquire();
				try {
					chapter = write.apply(system(info, players, i + 1, parts.size(), previous), List.of(new AiMessage("user", transcript(parts.get(i)))));
				} finally {
					budget.release();
				}
				Chapter c = chapter(chapter, i + 1);
				text.append("Capítulo ").append(i + 1).append(" — ").append(c.title()).append("\n\n").append(c.body()).append("\n\n");
				previous = c.title();
			}
			String done = text.toString().trim();
			inOwner(owner, () -> jdbc.sql("""
					update public.chat_books set status = 'ready', text = :text, chapters = :chapters, error = null, updated_at = now()
					 where group_id = :group
					""").param("group", groupId).param("text", done).param("chapters", parts.size()).update());
			publish(groupId, owner, "book_ready");
		} catch (RuntimeException failure) {
			String reason = failure instanceof AuthException auth && "AI_QUOTA_EXHAUSTED".equals(auth.code())
					? "A cota de IA acabou antes de terminar o livro. Tente amanhã."
					: "Não foi possível escrever o livro agora. Tente de novo mais tarde.";
			log.warn("Livro do roleplay falhou: {}", failure.getMessage());
			try {
				inOwner(owner, () -> jdbc.sql("update public.chat_books set status = 'failed', error = :error, updated_at = now() where group_id = :group")
						.param("group", groupId).param("error", reason).update());
				publish(groupId, owner, "book_failed");
			} catch (RuntimeException ignored) {
				// O status fica "escrevendo"; dá para encomendar de novo depois.
			}
		}
	}

	private void publish(UUID groupId, UUID owner, String type) {
		List<UUID> members = inOwner(owner, () -> repository.activeMemberIds(groupId));
		stream.publish(members, "group", Map.of("groupId", groupId, "type", type));
	}

	private <T> T inOwner(UUID owner, java.util.function.Supplier<T> action) {
		return transactions.execute(status -> {
			DbSession.setUser(jdbc, owner);
			return action.get();
		});
	}

	/** Divide a história em até {@link #MAX_CHAPTERS} capítulos de tamanho parecido. */
	static List<List<Map<String, Object>>> chapters(List<Map<String, Object>> story) {
		List<Map<String, Object>> lines = story.stream().filter(m -> m.get("body") != null).toList();
		int count = Math.max(1, Math.min(MAX_CHAPTERS, (int) Math.ceil(lines.size() / (double) Math.max(MIN_PER_CHAPTER, 1))));
		int size = (int) Math.ceil(lines.size() / (double) count);
		List<List<Map<String, Object>>> out = new ArrayList<>();
		for (int i = 0; i < lines.size(); i += size) out.add(lines.subList(i, Math.min(lines.size(), i + size)));
		return out;
	}

	/** O trecho como roteiro: narrador e personagens. */
	static String transcript(List<Map<String, Object>> part) {
		StringBuilder out = new StringBuilder();
		for (Map<String, Object> m : part) {
			Object character = m.get("rp_character");
			String who = "narrator".equals(m.get("kind")) ? "NARRADOR" : character != null ? String.valueOf(character) : String.valueOf(m.get("author_name"));
			out.append(who).append(": ").append(m.get("body")).append('\n');
		}
		return out.toString().trim();
	}

	static String system(Map<String, Object> info, List<Map<String, Object>> players, int number, int total, String previous) {
		StringBuilder cast = new StringBuilder();
		for (Map<String, Object> p : players) {
			if (p.get("rp_character") != null) cast.append(p.get("rp_character")).append(", ");
		}
		return """
				Você é um escritor de romances. Reescreva o trecho de um roleplay coletivo como o capítulo %d de %d de um livro
				em português do Brasil, em prosa literária na terceira pessoa e no passado.
				História: "%s". Premissa: %s
				Personagens principais: %s
				%s
				Regras:
				- Mantenha todos os acontecimentos e decisões do trecho, na mesma ordem; não invente finais nem mude quem fez o quê.
				- As falas dos personagens viram diálogos com travessão (—); ações em *asteriscos* viram narração.
				- Linha 1: só o título do capítulo (curto, sem a palavra "Capítulo"). Depois, o texto em parágrafos.
				- Nada de comentários sobre o roleplay, as pessoas ou a IA.
				""".formatted(number, total, info.get("name"), info.get("rp_premise"), cast.isEmpty() ? "os do trecho" : cast.substring(0, cast.length() - 2),
				previous.isBlank() ? "" : "O capítulo anterior se chamou \"" + previous + "\": continue dali.");
	}

	record Chapter(String title, String body) {
	}

	/** Separa o título (primeira linha) do texto; limpa rótulos como "Capítulo 3:" e marcações. */
	static Chapter chapter(String raw, int number) {
		String text = raw == null ? "" : raw.trim();
		int nl = text.indexOf('\n');
		String first = (nl < 0 ? text : text.substring(0, nl)).replaceAll("[#*_]", "").trim();
		String body = nl < 0 ? "" : text.substring(nl + 1).trim();
		first = first.replaceAll("(?i)^cap[íi]tulo\\s*\\d*\\s*[:\\-—–.]*\\s*", "").trim();
		if (first.isBlank() || first.length() > 90) {
			if (body.isBlank()) body = text;
			first = "Parte " + number;
		}
		return new Chapter(first, body.replaceAll("\n{3,}", "\n\n"));
	}
}
