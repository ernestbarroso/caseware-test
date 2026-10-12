package com.caseware.test.fanout.model;

import java.time.Instant;
import java.util.Objects;

public record PendingUpdate(String engagementId, String firmId, VersionId fromVersion, VersionId targetVersion,
		String sourceEventId, Instant basisObservedAt, Instant computedAt) {

	public PendingUpdate {
		Objects.requireNonNull(engagementId, "engagementId");
		Objects.requireNonNull(firmId, "firmId");
		Objects.requireNonNull(fromVersion, "fromVersion");
		Objects.requireNonNull(targetVersion, "targetVersion");
		Objects.requireNonNull(sourceEventId, "sourceEventId");
		Objects.requireNonNull(basisObservedAt, "basisObservedAt");
		Objects.requireNonNull(computedAt, "computedAt");
	}

}
