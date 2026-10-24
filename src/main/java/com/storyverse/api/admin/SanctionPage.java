package com.storyverse.api.admin;

import java.util.List;

public record SanctionPage(List<Sanction> items, String nextCursor) {
}
