package com.caseware.test.fanout.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EngagementTemplateState(String engagementId, VersionId currentVersion,
		Optional<VersionId> declinedVersion, Instant observedAt) {

	public EngagementTemplateState {
		Objects.requireNonNull(engagementId, "engagementId");
		Objects.requireNonNull(currentVersion, "currentVersion");
		Objects.requireNonNull(declinedVersion, "declinedVersion");
		Objects.requireNonNull(observedAt, "observedAt");
	}

	public static EngagementTemplateState from(EngagementIndexEntry entry) {
		return new EngagementTemplateState(entry.engagementId(), entry.currentVersion(), entry.declinedVersion(),
				entry.observedAt());
	}

}
