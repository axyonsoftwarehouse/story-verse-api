package com.storyverse.api.admin;

/**
 * Um livro do ranking de mais lidos. {@code readers}: contas com o livro em leitura ou lido e
 * atividade no período; {@code finished}: contas que terminaram no período.
 */
public record AdminTopBook(String bookKey, String title, String author, long readers, long finished) {
}
