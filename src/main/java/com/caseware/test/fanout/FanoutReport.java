package com.caseware.test.fanout;

import java.util.Map;

import com.caseware.test.fanout.PendingUpdateResolver.SkipReason;
import com.caseware.test.fanout.model.VersionId;

public record FanoutReport(VersionId version, RunStatus status, Map<ItemOutcome, Long> outcomes,
		long downstreamCalls) {

	public FanoutReport {
		outcomes = Map.copyOf(outcomes);
	}

	public long count(ItemOutcome outcome) {
		return outcomes.getOrDefault(outcome, 0L);
	}

	public enum RunStatus {

		COMPLETED,
		ALREADY_FINISHED,
		HELD_BY_OTHER_WORKER,
		ABANDONED_WITHDRAWN,
		ABANDONED_SUPERSEDED,
		LEASE_LOST

	}

	public enum ItemOutcome {

		OFFERED,
		PENDING_UNCHANGED,
		SKIPPED_WITHDRAWN,
		SKIPPED_ALREADY_CURRENT_OR_AHEAD,
		SKIPPED_DIFFERENT_LINEAGE,
		SKIPPED_DECLINED,
		ENGAGEMENT_GONE,
		DEAD_LETTERED;

		static ItemOutcome skipped(SkipReason reason) {
			return switch (reason) {
				case TARGET_WITHDRAWN -> SKIPPED_WITHDRAWN;
				case ALREADY_CURRENT_OR_AHEAD -> SKIPPED_ALREADY_CURRENT_OR_AHEAD;
				case DIFFERENT_LINEAGE -> SKIPPED_DIFFERENT_LINEAGE;
				case DECLINED -> SKIPPED_DECLINED;
			};
		}

	}

}
