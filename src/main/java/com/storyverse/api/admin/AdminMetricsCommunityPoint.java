package com.storyverse.api.admin;

import java.time.LocalDate;

/**
 * Um ponto da atividade do fórum (dia ou semana): tópicos, respostas e denúncias criados no
 * bucket, contando também o que a moderação escondeu ou apagou depois.
 */
public record AdminMetricsCommunityPoint(LocalDate bucket, long topics, long replies, long reports) {
}
