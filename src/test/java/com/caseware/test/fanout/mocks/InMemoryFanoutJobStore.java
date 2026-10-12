package com.caseware.test.fanout.mocks;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.caseware.test.fanout.model.TemplatePublished;
import com.caseware.test.fanout.model.VersionId;
import com.caseware.test.fanout.port.FanoutJobStore;

public final class InMemoryFanoutJobStore implements FanoutJobStore {

	private record Row(FanoutJob job, String owner, Instant leaseExpiresAt) {
	}

	private final Map<VersionId, Row> rows = new HashMap<>();

	@Override
	public synchronized AcquireResult tryAcquire(TemplatePublished event, String workerId, Instant now,
			Duration lease) {
		Row row = rows.get(event.version());
		if (row == null) {
			FanoutJob job = new FanoutJob(event.version(), event.eventId(), JobStatus.IN_PROGRESS, Optional.empty());
			rows.put(event.version(), new Row(job, workerId, now.plus(lease)));
			return new AcquireResult.Acquired(job);
		}
		if (row.job().status().isTerminal()) {
			return new AcquireResult.AlreadyFinished(row.job().status());
		}
		if (row.owner() != null && !row.owner().equals(workerId) && now.isBefore(row.leaseExpiresAt())) {
			return new AcquireResult.HeldByOther();
		}
		rows.put(event.version(), new Row(row.job(), workerId, now.plus(lease)));
		return new AcquireResult.Acquired(row.job());
	}

	@Override
	public synchronized void checkpoint(VersionId version, String workerId, String cursor, Instant now, Duration lease)
			throws LeaseLostException {
		Row row = owned(version, workerId);
		FanoutJob job = row.job();
		rows.put(version, new Row(new FanoutJob(version, job.sourceEventId(), job.status(), Optional.of(cursor)),
				workerId, now.plus(lease)));
	}

	@Override
	public synchronized void finish(VersionId version, String workerId, JobStatus status) throws LeaseLostException {
		FanoutJob job = owned(version, workerId).job();
		rows.put(version, new Row(new FanoutJob(version, job.sourceEventId(), status, job.cursor()), null, Instant.MIN));
	}

	public synchronized void seed(FanoutJob job, String owner, Instant leaseExpiresAt) {
		rows.put(job.version(), new Row(job, owner, leaseExpiresAt));
	}

	private Row owned(VersionId version, String workerId) throws LeaseLostException {
		Row row = rows.get(version);
		if (row == null || !workerId.equals(row.owner())) {
			throw new LeaseLostException(version, workerId);
		}
		return row;
	}

}
