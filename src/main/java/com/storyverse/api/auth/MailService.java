package com.storyverse.api.auth;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Envio dos links de confirmação de cadastro e recuperação de senha. */
@Service
public class MailService {

	private static final Logger log = LoggerFactory.getLogger(MailService.class);

	private final ObjectProvider<JavaMailSender> senders;
	private final String host;
	private final String from;
	private final String fromName;
	private final String publicUrl;

	public MailService(
			ObjectProvider<JavaMailSender> senders,
			@Value("${spring.mail.host:}") String host,
			@Value("${app.mail.from:no-reply@storyverse.local}") String from,
			@Value("${app.mail.from-name:Storyverse}") String fromName,
			@Value("${app.public-url:https://storyverse.2.29.42.104.sslip.io}") String publicUrl) {
		this.senders = senders;
		this.host = host == null ? "" : host.trim();
		this.from = from;
		this.fromName = fromName;
		this.publicUrl = publicUrl.replaceAll("/+$", "");
	}

	public boolean configured() {
		return !host.isBlank() && senders.getIfAvailable() != null;
	}

	public void requireConfigured() {
		if (!configured()) {
			throw new AuthException(503, "O envio de e-mail ainda não está configurado. Tente mais tarde.");
		}
	}

	public void sendConfirmation(String email, String token) {
		// Fragmento (#) não é enviado ao servidor/proxy nem aparece em logs de acesso.
		String link = publicUrl + "/confirmar-email#token=" + token;
		send(email, "Confirme seu e-mail no Storyverse", """
				Confirme seu e-mail para ativar sua conta no Storyverse:
				%s

				O link vale por 24 horas. Se você não criou esta conta, ignore esta mensagem.
				""".formatted(link), """
				<p>Confirme seu e-mail para ativar sua conta no <strong>Storyverse</strong>.</p>
				<p><a href="%s">Confirmar e-mail</a></p>
				<p>O link vale por 24 horas. Se você não criou esta conta, ignore esta mensagem.</p>
				""".formatted(link));
	}

	public void sendPasswordReset(String email, String token) {
		String link = publicUrl + "/redefinir-senha#token=" + token;
		send(email, "Redefinir sua senha no Storyverse", """
				Recebemos um pedido para redefinir a senha da sua conta no Storyverse:
				%s

				O link vale por 1 hora. Se você não pediu, ignore esta mensagem.
				""".formatted(link), """
				<p>Recebemos um pedido para redefinir a senha da sua conta no <strong>Storyverse</strong>.</p>
				<p><a href="%s">Criar uma senha nova</a></p>
				<p>O link vale por 1 hora. Se você não pediu, ignore esta mensagem.</p>
				""".formatted(link));
	}

	/** Aviso para quem tentou cadastrar um e-mail que já tem conta (sem revelar isso na API). */
	public void sendExistingAccount(String email) {
		String link = publicUrl + "/";
		send(email, "Sua conta no Storyverse já existe", """
				Você (ou alguém) tentou criar uma conta com este e-mail, mas ela já existe.
				Entre normalmente em %s; se esqueceu a senha, use "Esqueci minha senha" na tela de entrada.
				""".formatted(link), """
				<p>Você (ou alguém) tentou criar uma conta com este e-mail, mas ela já existe.</p>
				<p><a href="%s">Entrar no Storyverse</a></p>
				<p>Se esqueceu a senha, use <strong>Esqueci minha senha</strong> na tela de entrada.</p>
				""".formatted(link));
	}

	/** Pix pré-pago confirmado. */
	public void sendPrepaidConfirmation(String email, Instant until) {
		String date = day(until);
		String link = publicUrl + "/conta/assinatura";
		send(email, "Pagamento recebido — Storyverse", """
				Recebemos o seu pagamento com Pix. Seu acesso ao Storyverse vale até %s.

				O Pix não renova sozinho: perto do fim, mandamos um lembrete para você pagar mais um período.
				Sua assinatura: %s
				""".formatted(date, link), """
				<p>Recebemos o seu pagamento com Pix. Seu acesso ao <strong>Storyverse</strong> vale até <strong>%s</strong>.</p>
				<p>O Pix não renova sozinho: perto do fim, mandamos um lembrete para você pagar mais um período.</p>
				<p><a href="%s">Ver minha assinatura</a></p>
				""".formatted(date, link));
	}

	/** Fim próximo do período pago com Pix. */
	public void sendPrepaidEnding(String email, Instant until) {
		String date = day(until);
		String link = publicUrl + "/assinar";
		send(email, "Seu acesso ao Storyverse termina em " + date, """
				Seu acesso pago com Pix termina em %s.
				Para continuar lendo, pague mais um período com Pix ou assine no cartão: %s
				""".formatted(date, link), """
				<p>Seu acesso pago com Pix termina em <strong>%s</strong>.</p>
				<p><a href="%s">Continuar no Storyverse</a>: pague mais um período com Pix ou assine no cartão.</p>
				""".formatted(date, link));
	}

	private static String day(Instant instant) {
		return DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("America/Sao_Paulo")).format(instant);
	}

	private void send(String to, String subject, String text, String html) {
		requireConfigured();
		JavaMailSender sender = senders.getIfAvailable();
		try {
			MimeMessage message = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(from, fromName);
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(text, html);
			sender.send(message);
		} catch (MessagingException | MailException | UnsupportedEncodingException error) {
			log.error("Falha ao enviar e-mail para {}: {}", to, error.getMessage());
			throw new AuthException(503, "Não foi possível enviar o e-mail agora. Tente de novo em instantes.");
		}
	}
}
