package com.storyverse.api.reading;

/** Linha de reading_state como JSON cru (o serviço converte para JSON na resposta). */
public record StateRow(String data, String updatedAt) {
}
