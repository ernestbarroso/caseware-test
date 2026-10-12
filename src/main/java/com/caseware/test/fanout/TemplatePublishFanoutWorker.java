package com.caseware.test.fanout;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

import com.caseware.test.fanout.FanoutReport.ItemOutcome;
import com.caseware.test.fanout.FanoutReport.RunStatus;
import com.caseware.test.fanout.PendingUpdateResolver.Decision;
import com.caseware.test.fanout.PendingUpdateResolver.NeedsFreshState;
import com.caseware.test.fanout.PendingUpdateResolver.Offer;
import com.caseware.test.fanout.PendingUpdateResolver.Skip;
import com.caseware.test.fanout.model.DeadLetter;
import com.caseware.test.fanout.model.EngagementIndexEntry;
import com.caseware.test.fanout.model.EngagementTemplateState;
import com.caseware.test.fanout.model.IndexPage;
import com.caseware.test.fanout.model.PendingUpdate;
import com.caseware.test.fanout.model.TemplatePublished;
import com.caseware.test.fanout.model.VerificationStatus;
import com.caseware.test.fanout.model.VersionId;
import com.caseware.test.fanout.port.DeadLetterSink;
import com.caseware.test.fanout.port.DownstreamException;
import com.caseware.test.fanout.port.EngagementGateway;
import com.caseware.test.fanout.port.EngagementIndex;
import com.caseware.test.fanout.port.FanoutJobStore;
import com.caseware.test.fanout.port.FanoutJobStore.AcquireResult;
import com.caseware.test.fanout.port.FanoutJobStore.FanoutJob;
import com.caseware.test.fanout.port.FanoutJobStore.JobStatus;
import com.caseware.test.fanout.port.FanoutJobStore.LeaseLostException;
import com.caseware.test.fanout.port.PendingUpdateStore;
import com.caseware.test.fanout.port.TemplateLineage;

public final class TemplatePublishFanoutWorker {

	private static final Logger LOG = System.getLogger(TemplatePublishFanoutWorker.class.getName());
	private static final int MAX_CAS_ATTEMPTS = 5;

	private final String workerId;
	private final FanoutSettings settings;
	private final TemplateLineage lineage;
	private final EngagementIndex index;
	private final EngagementGateway gateway;
	private final PendingUpdateStore pendingUpdates;
	private final FanoutJobStore jobs;
	private final DeadLetterSink deadLetters;
	private final Clock clock;
	private final Sleeper sleeper;
	private final PendingUpdateResolver resolver;
	private final Semaphore downstreamPermits;

	public TemplatePublishFanoutWorker(String workerId, FanoutSettings settings, TemplateLineage lineage,
			EngagementIndex index, EngagementGateway gateway, PendingUpdateStore pendingUpdates, FanoutJobStore jobs,
			DeadLetterSink deadLetters, Clock clock, Sleeper sleeper) {
		this.workerId = Objects.requireNonNull(workerId, "workerId");
		this.settings = Objects.requireNonNull(settings, "settings");
		this.lineage = Objects.requireNonNull(lineage, "lineage");
		this.index = Objects.requireNonNull(index, "index");
		this.gateway = Objects.requireNonNull(gateway, "gateway");
		this.pendingUpdates = Objects.requireNonNull(pendingUpdates, "pendingUpdates");
		this.jobs = Objects.requireNonNull(jobs, "jobs");
		this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
		this.resolver = new PendingUpdateResolver(lineage);
		this.downstreamPermits = new Semaphore(settings.maxConcurrentDownstreamCalls(), true);
	}

	public FanoutReport handle(TemplatePublished event) throws InterruptedException {
		VersionId version = event.version();
		Counters counters = new Counters();
		FanoutJob job;
		switch (jobs.tryAcquire(event, workerId, clock.instant(), settings.lease())) {
			case AcquireResult.Acquired(var acquired) -> job = acquired;
			case AcquireResult.AlreadyFinished _ -> {
				return counters.report(version, RunStatus.ALREADY_FINISHED);
			}
			case AcquireResult.HeldByOther _ -> {
				return counters.report(version, RunStatus.HELD_BY_OTHER_WORKER);
			}
		}
		String cursor = job.cursor().orElse(null);
		try {
			while (true) {
				Optional<JobStatus> abandon = abandonReason(version);
				if (abandon.isPresent()) {
					jobs.finish(version, workerId, abandon.get());
					RunStatus status = abandon.get() == JobStatus.WITHDRAWN ? RunStatus.ABANDONED_WITHDRAWN
							: RunStatus.ABANDONED_SUPERSEDED;
					return counters.report(version, status);
				}

				IndexPage page = index.page(version.productId(), cursor, settings.pageSize());
				processPage(event, page, counters);

				if (page.nextCursor().isEmpty()) {
					jobs.finish(version, workerId, JobStatus.COMPLETED);
					return counters.report(version, RunStatus.COMPLETED);
				}
				cursor = page.nextCursor().get();
				jobs.checkpoint(version, workerId, cursor, clock.instant(), settings.lease());
			}
		}
		catch (LeaseLostException ex) {
			LOG.log(Level.WARNING, ex.getMessage());
			return counters.report(version, RunStatus.LEASE_LOST);
		}
	}

	private Optional<JobStatus> abandonReason(VersionId version) {
		if (lineage.isWithdrawn(version)) {
			return Optional.of(JobStatus.WITHDRAWN);
		}
		VersionId latest = lineage.latestOnBranchOf(version);
		if (lineage.supersedes(latest, version)) {
			return Optional.of(JobStatus.SUPERSEDED);
		}
		return Optional.empty();
	}

	private void processPage(TemplatePublished event, IndexPage page, Counters counters) throws InterruptedException {
		try (ExecutorService rows = Executors.newVirtualThreadPerTaskExecutor()) {
			for (EngagementIndexEntry entry : page.entries()) {
				rows.submit(() -> processSafely(event, entry, counters));
			}
		}
		if (Thread.interrupted()) {
			throw new InterruptedException("interrupted while processing a page of " + event.version());
		}
	}

	private void processSafely(TemplatePublished event, EngagementIndexEntry entry, Counters counters) {
		try {
			counters.record(processOne(event, entry, counters));
		}
		catch (InterruptedException ex) {
		}
		catch (RuntimeException ex) {
			LOG.log(Level.ERROR, "unexpected failure for engagement " + entry.engagementId(), ex);
			deadLetter(event, entry, 0, "unexpected: " + ex);
			counters.record(ItemOutcome.DEAD_LETTERED);
		}
	}

	private ItemOutcome processOne(TemplatePublished event, EngagementIndexEntry entry, Counters counters)
			throws InterruptedException {
		VersionId target = event.version();
		EngagementTemplateState state = EngagementTemplateState.from(entry);
		Decision decision = resolver.decide(state.currentVersion(), state.declinedVersion(), entry.verification(),
				target);
		while (true) {
			switch (decision) {
				case Skip(var reason) -> {
					return ItemOutcome.skipped(reason);
				}
				case Offer(var from, var to) -> {
					return writePending(new PendingUpdate(entry.engagementId(), entry.firmId(), from, to,
							event.eventId(), state.observedAt(), clock.instant()));
				}
				case NeedsFreshState _ -> {
					switch (fetch(entry, counters)) {
						case Fetched(var fresh) -> {
							state = fresh;
							index.recordObserved(fresh);
							decision = resolver.decide(fresh.currentVersion(), fresh.declinedVersion(),
									VerificationStatus.VERIFIED, target);
						}
						case Gone() -> {
							return ItemOutcome.ENGAGEMENT_GONE;
						}
						case Failed(int attempts, String reason) -> {
							deadLetter(event, entry, attempts, reason);
							return ItemOutcome.DEAD_LETTERED;
						}
					}
				}
			}
		}
	}

	private ItemOutcome writePending(PendingUpdate candidate) {
		for (int attempt = 1; attempt <= MAX_CAS_ATTEMPTS; attempt++) {
			Optional<PendingUpdate> existing = pendingUpdates.find(candidate.engagementId());
			if (existing.isPresent() && !resolver.shouldReplace(existing.get(), candidate)) {
				return ItemOutcome.PENDING_UNCHANGED;
			}
			if (pendingUpdates.compareAndSet(candidate.engagementId(), existing, candidate)) {
				return ItemOutcome.OFFERED;
			}
		}
		throw new IllegalStateException("pending update for " + candidate.engagementId() + " kept changing after "
				+ MAX_CAS_ATTEMPTS + " attempts");
	}

	private sealed interface FetchResult {
	}

	private record Fetched(EngagementTemplateState state) implements FetchResult {
	}

	private record Gone() implements FetchResult {
	}

	private record Failed(int attempts, String reason) implements FetchResult {
	}

	private FetchResult fetch(EngagementIndexEntry entry, Counters counters) throws InterruptedException {
		RetryPolicy retry = settings.retry();
		for (int attempt = 1;; attempt++) {
			DownstreamException failure;
			downstreamPermits.acquire();
			try {
				counters.downstreamCalls.increment();
				return new Fetched(gateway.readTemplateState(entry.engagementId(), settings.callTimeout()));
			}
			catch (DownstreamException ex) {
				failure = ex;
			}
			finally {
				downstreamPermits.release();
			}

			String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
			Duration delay;
			switch (failure) {
				case DownstreamException.NotFound _ -> {
					return new Gone();
				}
				case DownstreamException.Permanent _ -> {
					return new Failed(attempt, reason);
				}
				case DownstreamException.Overloaded overloaded ->
					delay = max(retry.backoff(attempt), overloaded.retryAfter());
				case DownstreamException.Transient _ -> delay = retry.backoff(attempt);
			}
			if (attempt >= retry.maxAttempts()) {
				return new Failed(attempt, reason);
			}
			sleeper.sleep(delay);
		}
	}

	private static Duration max(Duration a, Duration b) {
		return (a.compareTo(b) >= 0) ? a : b;
	}

	private void deadLetter(TemplatePublished event, EngagementIndexEntry entry, int attempts, String reason) {
		deadLetters.publish(new DeadLetter(event.version(), event.eventId(), entry.engagementId(), entry.firmId(),
				attempts, reason, clock.instant()));
	}

	private static final class Counters {

		private final Map<ItemOutcome, LongAdder> outcomes = new EnumMap<>(ItemOutcome.class);

		private final LongAdder downstreamCalls = new LongAdder();

		Counters() {
			for (ItemOutcome outcome : ItemOutcome.values()) {
				outcomes.put(outcome, new LongAdder());
			}
		}

		void record(ItemOutcome outcome) {
			outcomes.get(outcome).increment();
		}

		FanoutReport report(VersionId version, RunStatus status) {
			Map<ItemOutcome, Long> snapshot = new EnumMap<>(ItemOutcome.class);
			outcomes.forEach((outcome, count) -> snapshot.put(outcome, count.sum()));
			return new FanoutReport(version, status, snapshot, downstreamCalls.sum());
		}

	}

}
