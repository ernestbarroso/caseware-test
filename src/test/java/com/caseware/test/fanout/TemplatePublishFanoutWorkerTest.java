package com.caseware.test.fanout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.caseware.test.fanout.FanoutReport.ItemOutcome;
import com.caseware.test.fanout.FanoutReport.RunStatus;
import com.caseware.test.fanout.mocks.InMemoryEngagementIndex;
import com.caseware.test.fanout.mocks.InMemoryFanoutJobStore;
import com.caseware.test.fanout.mocks.InMemoryPendingUpdateStore;
import com.caseware.test.fanout.mocks.InMemoryTemplateLineage;
import com.caseware.test.fanout.mocks.MockEngagementGateway;
import com.caseware.test.fanout.model.DeadLetter;
import com.caseware.test.fanout.model.EngagementIndexEntry;
import com.caseware.test.fanout.model.EngagementTemplateState;
import com.caseware.test.fanout.model.PendingUpdate;
import com.caseware.test.fanout.model.TemplatePublished;
import com.caseware.test.fanout.model.VerificationStatus;
import com.caseware.test.fanout.model.VersionId;
import com.caseware.test.fanout.port.DownstreamException;
import com.caseware.test.fanout.port.FanoutJobStore.FanoutJob;
import com.caseware.test.fanout.port.FanoutJobStore.JobStatus;

class TemplatePublishFanoutWorkerTest {

	private static final VersionId V1 = VersionId.of("fs", "v1");
	private static final VersionId V2 = VersionId.of("fs", "v2");
	private static final VersionId V3 = VersionId.of("fs", "v3");
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private final Clock clock = Clock.systemUTC();
	private InMemoryTemplateLineage lineage;
	private InMemoryEngagementIndex index;
	private MockEngagementGateway gateway;
	private InMemoryPendingUpdateStore pending;
	private InMemoryFanoutJobStore jobs;
	private List<DeadLetter> deadLetters;

	@BeforeEach
	void setUp() {
		lineage = new InMemoryTemplateLineage().publish(V1, null, "CA").publish(V2, V1, "CA");
		index = new InMemoryEngagementIndex();
		gateway = new MockEngagementGateway(clock);
		pending = new InMemoryPendingUpdateStore();
		jobs = new InMemoryFanoutJobStore();
		deadLetters = new CopyOnWriteArrayList<>();
	}

	@Test
	void unverifiedRowIsLoadedOnceThenServedFromTheIndex() throws Exception {
		unverified("e1", V1);

		FanoutReport first = worker().handle(publish(V2));
		assertThat(first.count(ItemOutcome.OFFERED)).isEqualTo(1);
		assertThat(first.downstreamCalls()).isEqualTo(1);
		assertThat(index.get("e1").verification()).isEqualTo(VerificationStatus.VERIFIED);

		lineage.publish(V3, V2, "CA");
		FanoutReport second = worker().handle(publish(V3));
		assertThat(second.count(ItemOutcome.OFFERED)).isEqualTo(1);
		assertThat(second.downstreamCalls()).isZero();
	}

	@Test
	void redeliveryIsIdempotent() throws Exception {
		unverified("e1", V1);
		verified("e2", V1);
		worker().handle(publish(V2));
		PendingUpdate written = pending.find("e1").orElseThrow();

		assertThat(worker().handle(publish(V2)).status()).isEqualTo(RunStatus.ALREADY_FINISHED);

		jobs = new InMemoryFanoutJobStore();
		FanoutReport replay = worker().handle(publish(V2));
		assertThat(replay.count(ItemOutcome.PENDING_UNCHANGED)).isEqualTo(2);
		assertThat(pending.find("e1")).contains(written);
		assertThat(gateway.totalCalls()).isEqualTo(1);
	}

	@Test
	void accumulatedUpdatesCollapseIntoOneCumulativeOffer() throws Exception {
		verified("e1", V1);
		worker().handle(publish(V2));

		lineage.publish(V3, V2, "CA");
		worker().handle(publish(V3));

		PendingUpdate update = pending.find("e1").orElseThrow();
		assertThat(update.fromVersion()).isEqualTo(V1);
		assertThat(update.targetVersion()).isEqualTo(V3);
	}

	@Test
	void downstreamConcurrencyNeverExceedsTheCap() throws Exception {
		IntStream.range(0, 40).forEach(i -> unverified("e%02d".formatted(i), V1));
		gateway.setLatency(Duration.ofMillis(20));

		FanoutReport report = worker(settings(4, 10)).handle(publish(V2));

		assertThat(report.count(ItemOutcome.OFFERED)).isEqualTo(40);
		assertThat(gateway.maxInFlight()).isLessThanOrEqualTo(4);
	}

	@Test
	void retriesTransientFailuresAndDeadLettersTheRestWithoutBlockingTheJob() throws Exception {
		unverified("e1", V1);
		gateway.failNext("e1", new DownstreamException.Transient("timeout"),
				new DownstreamException.Overloaded("busy", Duration.ofMillis(1)));
		unverified("e2", V1);
		gateway.failNext("e2", new DownstreamException.Permanent("corrupt file"));
		unverified("e3", V1);
		gateway.failNext("e3", new DownstreamException.Transient("a"), new DownstreamException.Transient("b"),
				new DownstreamException.Transient("c"));
		index.put(entry("e4", V1, VerificationStatus.UNVERIFIED));
		unverified("e5", V1);

		FanoutReport report = worker().handle(publish(V2));

		assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
		assertThat(report.count(ItemOutcome.OFFERED)).isEqualTo(2);
		assertThat(report.count(ItemOutcome.DEAD_LETTERED)).isEqualTo(2);
		assertThat(report.count(ItemOutcome.ENGAGEMENT_GONE)).isEqualTo(1);
		assertThat(gateway.callsFor("e1")).isEqualTo(3);
		assertThat(gateway.callsFor("e2")).as("permanent errors are not retried").isEqualTo(1);
		assertThat(deadLetters).extracting(DeadLetter::engagementId, DeadLetter::attempts)
			.containsExactlyInAnyOrder(tuple("e2", 1),
					tuple("e3", 3));
	}

	@Test
	void resumesFromTheLastCheckpointAfterACrash() throws Exception {
		IntStream.rangeClosed(1, 6).forEach(i -> verified("e" + i, V1));
		jobs.seed(new FanoutJob(V2, "evt-v2", JobStatus.IN_PROGRESS, Optional.of("e2")), "crashed-worker",
				clock.instant().minusSeconds(1));

		FanoutReport report = worker(settings(4, 2)).handle(publish(V2));

		assertThat(report.status()).isEqualTo(RunStatus.COMPLETED);
		assertThat(report.count(ItemOutcome.OFFERED)).isEqualTo(4);
		assertThat(pending.find("e1")).isEmpty();
		assertThat(pending.find("e3")).isPresent();
	}

	@Test
	void concurrentDeliveriesAreProcessedByOneWorker() throws Exception {
		IntStream.range(0, 10).forEach(i -> unverified("e" + i, V1));
		gateway.setLatency(Duration.ofMillis(20));
		TemplatePublishFanoutWorker a = worker("worker-a", settings(4, 100));
		TemplatePublishFanoutWorker b = worker("worker-b", settings(4, 100));

		List<RunStatus> statuses;
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Callable<FanoutReport>> tasks = List.of(() -> a.handle(publish(V2)), () -> b.handle(publish(V2)));
			statuses = executor.invokeAll(tasks).stream().map(Future::resultNow)
				.map(FanoutReport::status)
				.toList();
		}

		assertThat(statuses).containsOnlyOnce(RunStatus.COMPLETED);
		assertThat(statuses).containsAnyOf(RunStatus.HELD_BY_OTHER_WORKER, RunStatus.ALREADY_FINISHED);
		assertThat(gateway.totalCalls()).isEqualTo(10);
	}

	private void verified(String engagementId, VersionId current) {
		index.put(entry(engagementId, current, VerificationStatus.VERIFIED));
	}

	private void unverified(String engagementId, VersionId current) {
		index.put(entry(engagementId, current, VerificationStatus.UNVERIFIED));
		gateway.put(new EngagementTemplateState(engagementId, current, Optional.empty(), T0));
	}

	private static EngagementIndexEntry entry(String engagementId, VersionId current, VerificationStatus status) {
		return new EngagementIndexEntry(engagementId, "firm-1", current, Optional.empty(), status, T0);
	}

	private TemplatePublished publish(VersionId version) {
		return new TemplatePublished("evt-" + version.version(), version, clock.instant());
	}

	private static FanoutSettings settings(int maxConcurrent, int pageSize) {
		return new FanoutSettings(maxConcurrent, pageSize, Duration.ofSeconds(5),
				new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5)), Duration.ofMinutes(10));
	}

	private TemplatePublishFanoutWorker worker() {
		return worker(settings(4, 100));
	}

	private TemplatePublishFanoutWorker worker(FanoutSettings settings) {
		return worker("worker-1", settings);
	}

	private TemplatePublishFanoutWorker worker(String workerId, FanoutSettings settings) {
		return new TemplatePublishFanoutWorker(workerId, settings, lineage, index, gateway, pending, jobs,
				deadLetters::add, clock, duration -> {
				});
	}
}
