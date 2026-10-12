package com.caseware.test.fanout.model;

import java.time.Instant;
import java.util.Objects;

public record TemplatePublished(String eventId, VersionId version, Instant publishedAt) {

	public TemplatePublished {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(version, "version");
		Objects.requireNonNull(publishedAt, "publishedAt");
	}

}
