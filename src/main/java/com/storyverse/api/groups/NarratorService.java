package com.storyverse.api.groups;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import com.storyverse.api.common.DbSession;

/**
 * Narrador dos grupos de roleplay: a IA descreve o ambiente e as consequências das ações, sem
 * falar pelos personagens das pessoas. Entra a cada {@link #EVERY} mensagens, quando quem
 * administra o grupo toca em "Narrar" ou escreve "narrador" numa mensagem, e na abertura. Usa a cota de IA de quem criou o grupo, com teto diário.
 */
@Service
public class NarratorService {

	private static final Logger log = LoggerFactory.getLogger(NarratorService.class);

	/** Mensagens das pessoas entre uma narração automática e outra. */
	static final int EVERY = 6;
	/** Intervalo mínimo entre duas narrações do mesmo grupo. */
	static final int COOLDOWN_SECONDS = 20;
	/** Narrações por grupo por dia (horário de Brasília). */
	static final int DAILY_CAP = 50;
	static final int HISTORY = 30;
	static final int MAX_TOKENS = 380;
	static final int MAX_CHARS = 1800;

	/** CALLED: quem administra escreveu "narrador" na mensagem; o narrador responde a ela. */
	enum Trigger { AUTO, MANUAL, OPENING, CALLED }

	/** "narrador" como palavra solta, em qualquer caixa ("Narrador,", "NARRADOR?"), não "narradora". */
	private static final java.util.regex.Pattern CALL = java.util.regex.Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])narrador(?![\\p{L}\\p{N}_])");

	/** A mensagem chama o narrador pelo nome? */
	public static boolean mentionsNarrator(String text) {
		return text != null && CALL.matcher(text).find();
	}

	private final GroupRepository repository;
	private final GroupStream stream;
	private final AiService ai;
	private final AiBudget budget;
	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Set<UUID> running = ConcurrentHashMap.newKeySet();
	/** Quem escreve o texto nos testes (sem isso, a IA de verdade). */
	private volatile BiFunction<String, List<AiMessage>, String> writer;

	public NarratorService(GroupRepository repository, GroupStream stream, AiService ai, AiBudget budget, JdbcClient jdbc,
			PlatformTransactionManager transactions) {
		this.repository = repository;
		this.stream = stream;
		this.ai = ai;
		this.budget = budget;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactions);
	}

	void useWriter(BiFunction<String, List<AiMessage>, String> writer) {
		this.writer = writer;
	}

	/** Depois do commit da mensagem de alguém: talvez seja a vez do narrador. */
	public void afterPlayerMessage(UUID groupId, UUID author) {
		later(() -> run(groupId, author, Trigger.AUTO));
	}

	/** Alguém tocou em "Narrar". */
	public void requestNow(UUID groupId, UUID member) {
		later(() -> run(groupId, member, Trigger.MANUAL));
	}

	/** Quem administra escreveu "narrador": responde logo, sem esperar a vez (respeita intervalo e teto). */
	public void called(UUID groupId, UUID admin) {
		later(() -> run(groupId, admin, Trigger.CALLED));
	}

	/** Grupo de roleplay recém-criado: a cena de abertura. */
	public void opening(UUID groupId, UUID owner) {
		later(() -> run(groupId, owner, Trigger.OPENING));
	}

	private void later(Runnable job) {
		Runnable submit = () -> executor.submit(() -> {
			try {
				job.run();
			} catch (RuntimeException exception) {
				log.warn("Narrador falhou: {}", exception.getMessage());
			}
		});
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					submit.run();
				}
			});
		} else {
			submit.run();
		}
	}

	/** O trabalho todo; síncrono (os testes chamam direto). Devolve o id da narração ou null. */
	Long run(UUID groupId, UUID actor, Trigger trigger) {
		BiFunction<String, List<AiMessage>, String> write = writer != null ? writer
				: ai.live() ? (system, messages) -> ai.reply(system, messages, MAX_TOKENS).text() : null;
		if (write == null) return null;
		if (!running.add(groupId)) return null;
		try {
			// Lê como quem pediu (é membro); paga e escreve como o dono do grupo.
			Map<String, Object> info = transactions.execute(status -> {
				DbSession.setUser(jdbc, actor);
				return repository.roleplayInfo(groupId).orElse(null);
			});
			if (info == null || !"roleplay".equals(info.get("mode"))) return null;
			UUID owner = UUID.fromString(String.valueOf(info.get("owner_id")));
			if (trigger == Trigger.AUTO) {
				Integer since = transactions.execute(status -> {
					DbSession.setUser(jdbc, actor);
					return repository.playerMessagesSinceNarration(groupId);
				});
				if (since == null || since < EVERY) return null;
			}
			Boolean claimed = transactions.execute(status -> {
				DbSession.setUser(jdbc, owner);
				return repository.claimNarration(groupId, COOLDOWN_SECONDS, DAILY_CAP);
			});
			if (!Boolean.TRUE.equals(claimed)) {
				if (trigger == Trigger.MANUAL || trigger == Trigger.CALLED) notice(groupId, owner, "cooldown");
				return null;
			}
			List<Map<String, Object>> story = transactions.execute(status -> {
				DbSession.setUser(jdbc, owner);
				return repository.storySoFar(groupId, HISTORY);
			});
			List<Map<String, Object>> players = transactions.execute(status -> {
				DbSession.setUser(jdbc, owner);
				return repository.players(groupId);
			});
			List<UUID> members = transactions.execute(status -> {
				DbSession.setUser(jdbc, owner);
				return repository.activeMemberIds(groupId);
			});
			if (members == null) return null;
			stream.publish(members, "typing", Map.of("groupId", groupId, "userId", "narrator"));

			try {
				budget.reserve(owner, "narr-" + UUID.randomUUID());
			} catch (AuthException quota) {
				notice(groupId, owner, "quota");
				return null;
			}
			String text;
			budget.acquire();
			try {
				text = clean(write.apply(system(info, players), history(story == null ? List.of() : story, trigger)));
			} finally {
				budget.release();
			}
			if (text.isBlank()) return null;
			return transactions.execute(status -> {
				DbSession.setUser(jdbc, owner);
				long id = repository.insertNarration(groupId, owner, text);
				repository.message(owner, id).ifPresent(message -> {
					Map<String, Object> event = new java.util.LinkedHashMap<>(message);
					event.put("reactions", List.of());
					stream.publish(members, "message", event);
				});
				return id;
			});
		} finally {
			running.remove(groupId);
		}
	}

	/** Aviso para o grupo: narrador em pausa (cota do dono acabou) ou aguardando o intervalo. */
	private void notice(UUID groupId, UUID owner, String reason) {
		List<UUID> members = transactions.execute(status -> {
			DbSession.setUser(jdbc, owner);
			return repository.activeMemberIds(groupId);
		});
		if (members != null) stream.publish(members, "group", Map.of("groupId", groupId, "type", "narrator_" + reason));
	}

	/** As regras do narrador, com a premissa e quem joga. */
	static String system(Map<String, Object> info, List<Map<String, Object>> players) {
		StringBuilder cast = new StringBuilder();
		for (Map<String, Object> p : players == null ? List.<Map<String, Object>>of() : players) {
			Object character = p.get("rp_character");
			cast.append("- ").append(character == null ? p.get("name") + " (ainda sem personagem)" : character + " (jogado por " + p.get("name") + ")")
					.append('\n');
		}
		Object book = info.get("book_title");
		return """
				Você é o NARRADOR de um roleplay coletivo em português do Brasil, num grupo chamado "%s".
				Premissa da história: %s
				%sPersonagens das pessoas que jogam:
				%s
				Regras:
				- Narre em terceira pessoa, no presente, com no máximo dois parágrafos curtos (até 120 palavras).
				- Descreva o ambiente, as consequências das ações e o que os personagens não-jogadores fazem;
				  traga tensão, mistério ou reviravoltas para manter a história andando.
				- NUNCA fale, pense ou decida pelos personagens das pessoas: termine abrindo espaço para elas agirem.
				- Ações de personagens não-jogadores vão entre *asteriscos*; falas deles, entre aspas.
				- Mantenha o tom da premissa. Nada de sexo explícito, violência gráfica ou discurso de ódio.
				- Responda só com a narração, sem títulos, rótulos ou comentários fora da história.
				""".formatted(info.get("name"), info.get("rp_premise"), book == null ? "" : "Universo inspirado no livro \"" + book + "\".\n",
				cast.isEmpty() ? "- (ninguém escolheu personagem ainda)\n" : cast.toString());
	}

	/** A história até aqui no formato de conversa: pessoas como "user", narrador como "assistant". */
	static List<AiMessage> history(List<Map<String, Object>> story, Trigger trigger) {
		List<AiMessage> out = new ArrayList<>();
		StringBuilder pending = new StringBuilder();
		for (Map<String, Object> m : story) {
			boolean narrator = "narrator".equals(m.get("kind"));
			String body = m.get("body") == null ? (m.get("media_kind") == null ? "" : "[enviou uma imagem]") : String.valueOf(m.get("body"));
			if (narrator) {
				if (!pending.isEmpty()) {
					out.add(new AiMessage("user", pending.toString().trim()));
					pending.setLength(0);
				}
				out.add(new AiMessage("assistant", body));
			} else {
				Object character = m.get("rp_character");
				String who = character == null ? String.valueOf(m.get("author_name")) : character + " (" + m.get("author_name") + ")";
				pending.append(who).append(": ").append(body).append('\n');
			}
		}
		String ask = switch (trigger) {
			case OPENING -> "Abra a história: apresente o cenário da premissa e convide os personagens a agir.";
			case MANUAL -> "Narre o que acontece agora, a partir das últimas ações.";
			case AUTO -> "Continue a narração a partir das últimas ações.";
			case CALLED -> "Quem conduz a história falou com você, o narrador, na última mensagem. Atenda ao que foi "
					+ "pedido ou perguntado, como narrador e dentro da história (sem sair do personagem nem falar pelos jogadores).";
		};
		pending.append('\n').append("(").append(ask).append(")");
		out.add(new AiMessage("user", pending.toString().trim()));
		// O provedor pede que a conversa comece pela pessoa: sem descartar a abertura do narrador.
		if (!out.isEmpty() && "assistant".equals(out.get(0).role())) out.add(0, new AiMessage("user", "(A história começa.)"));
		return out;
	}

	/** Tira o rótulo que a IA às vezes põe ("Narrador:", "**Narrador:**") e limita o tamanho. */
	static String clean(String text) {
		if (text == null) return "";
		// Só o par ** do rótulo: o *asterisco* de uma ação logo depois fica.
		String t = text.trim().replaceAll("(?i)^(\\*\\*)?\\s*narrador\\s*(\\*\\*)?\\s*:\\s*(\\*\\*)?\\s*", "");
		return t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS - 1) + "…" : t;
	}
}
