package com.caseware.test.fanout;

import java.util.Objects;
import java.util.Optional;

import com.caseware.test.fanout.model.PendingUpdate;
import com.caseware.test.fanout.model.VerificationStatus;
import com.caseware.test.fanout.model.VersionId;
import com.caseware.test.fanout.port.TemplateLineage;

public final class PendingUpdateResolver {

	public sealed interface Decision {
	}

	public record Offer(VersionId from, VersionId target) implements Decision {
	}

	public record Skip(SkipReason reason) implements Decision {
	}

	public record NeedsFreshState() implements Decision {
	}

	public enum SkipReason {

		TARGET_WITHDRAWN, ALREADY_CURRENT_OR_AHEAD, DIFFERENT_LINEAGE, DECLINED

	}

	private final TemplateLineage lineage;

	public PendingUpdateResolver(TemplateLineage lineage) {
		this.lineage = Objects.requireNonNull(lineage, "lineage");
	}

	public Decision decide(VersionId current, Optional<VersionId> declined, VerificationStatus status,
			VersionId published) {
		if (lineage.isWithdrawn(published)) {
			return new Skip(SkipReason.TARGET_WITHDRAWN);
		}
		if (atOrAfter(current, published)) {
			return new Skip(SkipReason.ALREADY_CURRENT_OR_AHEAD);
		}
		if (!lineage.supersedes(published, current)) {
			return new Skip(SkipReason.DIFFERENT_LINEAGE);
		}
		if (status == VerificationStatus.UNVERIFIED) {
			return new NeedsFreshState();
		}
		if (declined.filter(d -> atOrAfter(d, published)).isPresent()) {
			return new Skip(SkipReason.DECLINED);
		}
		return new Offer(current, published);
	}

	public boolean shouldReplace(PendingUpdate existing, PendingUpdate candidate) {
		if (!existing.fromVersion().equals(candidate.fromVersion())) {
			return candidate.basisObservedAt().isAfter(existing.basisObservedAt());
		}
		if (existing.targetVersion().equals(candidate.targetVersion())) {
			return false;
		}
		return lineage.isWithdrawn(existing.targetVersion())
				|| lineage.supersedes(candidate.targetVersion(), existing.targetVersion());
	}

	private boolean atOrAfter(VersionId version, VersionId other) {
		return version.equals(other) || lineage.supersedes(version, other);
	}

}
