package com.caseware.test.fanout.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record EngagementIndexEntry(String engagementId, String firmId, VersionId currentVersion,
		Optional<VersionId> declinedVersion, VerificationStatus verification, Instant observedAt) {

	public EngagementIndexEntry {
		Objects.requireNonNull(engagementId, "engagementId");
		Objects.requireNonNull(firmId, "firmId");
		Objects.requireNonNull(currentVersion, "currentVersion");
		Objects.requireNonNull(declinedVersion, "declinedVersion");
		Objects.requireNonNull(verification, "verification");
		Objects.requireNonNull(observedAt, "observedAt");
	}

}
