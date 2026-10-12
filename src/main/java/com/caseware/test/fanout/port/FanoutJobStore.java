package com.caseware.test.fanout.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.caseware.test.fanout.model.TemplatePublished;
import com.caseware.test.fanout.model.VersionId;

public interface FanoutJobStore {

	enum JobStatus {

		IN_PROGRESS, COMPLETED, WITHDRAWN, SUPERSEDED;

		public boolean isTerminal() {
			return this == COMPLETED || this == WITHDRAWN;
		}

	}

	record FanoutJob(VersionId version, String sourceEventId, JobStatus status, Optional<String> cursor) {
	}

	sealed interface AcquireResult {

		record Acquired(FanoutJob job) implements AcquireResult {
		}

		record AlreadyFinished(JobStatus status) implements AcquireResult {
		}

		record HeldByOther() implements AcquireResult {
		}

	}

	AcquireResult tryAcquire(TemplatePublished event, String workerId, Instant now, Duration lease);

	void checkpoint(VersionId version, String workerId, String cursor, Instant now, Duration lease)
			throws LeaseLostException;

	void finish(VersionId version, String workerId, JobStatus status) throws LeaseLostException;

	final class LeaseLostException extends Exception {

		public LeaseLostException(VersionId version, String workerId) {
			super("worker " + workerId + " no longer holds the lease for " + version);
		}

	}

}
