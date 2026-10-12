package com.caseware.test.fanout.model;

import java.time.Instant;

public record DeadLetter(VersionId version, String sourceEventId, String engagementId, String firmId, int attempts,
		String reason, Instant at) {
}
