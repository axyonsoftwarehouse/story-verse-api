package com.storyverse.api.admin;

import java.util.List;

/** Página da lista de contas. {@code nextCursor} é nulo quando acabou. */
public record AdminUserPage(List<AdminUserSummary> items, String nextCursor) {
}
