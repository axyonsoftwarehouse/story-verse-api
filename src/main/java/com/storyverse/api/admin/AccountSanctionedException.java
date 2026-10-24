package com.storyverse.api.admin;

/** Conta suspensa ou banida: o login/refresh/API autenticada devolve 403 com o motivo. */
public class AccountSanctionedException extends RuntimeException {

	private final Sanction sanction;

	public AccountSanctionedException(Sanction sanction) {
		super("ban".equals(sanction.kind()) ? "Conta banida." : "Conta suspensa.");
		this.sanction = sanction;
	}

	public Sanction sanction() {
		return sanction;
	}
}
