package com.storyverse.api.library;

import java.math.BigDecimal;

/** Concluir: data (`yyyy-MM-dd`, padrão hoje), nota e resenha opcionais (null mantém as atuais). */
public record FinishRequest(String finishedOn, BigDecimal rating, String review) {
}
