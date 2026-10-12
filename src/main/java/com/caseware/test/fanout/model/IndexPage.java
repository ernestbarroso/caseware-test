package com.caseware.test.fanout.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record IndexPage(List<EngagementIndexEntry> entries, Optional<String> nextCursor) {

	public IndexPage {
		entries = List.copyOf(entries);
		Objects.requireNonNull(nextCursor, "nextCursor");
	}

}
