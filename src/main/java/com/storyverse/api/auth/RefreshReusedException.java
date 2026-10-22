package com.storyverse.api.auth;

/**
 * Token de refresh antigo reapareceu. A revogação da família precisa ser commitada mesmo
 * lançando 401, por isso AuthService.refresh usa noRollbackFor para esta exceção.
 */
public class RefreshReusedException extends AuthException {

	public RefreshReusedException() {
		super(401, "Sessão encerrada por segurança. Entre novamente.", "AUTH_REFRESH_REUSED");
	}
}
