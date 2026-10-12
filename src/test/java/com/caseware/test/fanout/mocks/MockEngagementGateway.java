package com.caseware.test.fanout.mocks;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.caseware.test.fanout.model.EngagementTemplateState;
import com.caseware.test.fanout.port.DownstreamException;
import com.caseware.test.fanout.port.EngagementGateway;


public final class MockEngagementGateway implements EngagementGateway {

	private final Map<String, EngagementTemplateState> states = new ConcurrentHashMap<>();
	private final Map<String, Deque<DownstreamException>> scriptedFailures = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> callsPerEngagement = new ConcurrentHashMap<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger maxInFlight = new AtomicInteger();
	private final AtomicInteger totalCalls = new AtomicInteger();
	private final Clock clock;
	private volatile Duration latency = Duration.ZERO;

	public MockEngagementGateway(Clock clock) {
		this.clock = clock;
	}

	public void setLatency(Duration latency) {
		this.latency = latency;
	}

	public void put(EngagementTemplateState state) {
		states.put(state.engagementId(), state);
	}

	public void failNext(String engagementId, DownstreamException... failures) {
		scriptedFailures.computeIfAbsent(engagementId, k -> new ArrayDeque<>()).addAll(List.of(failures));
	}

	@Override
	public EngagementTemplateState readTemplateState(String engagementId, Duration deadline)
			throws DownstreamException {
		totalCalls.incrementAndGet();
		callsPerEngagement.computeIfAbsent(engagementId, k -> new AtomicInteger()).incrementAndGet();
		maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
		try {
			if (!latency.isZero()) {
				Thread.sleep(latency);
			}
			Deque<DownstreamException> failures = scriptedFailures.get(engagementId);
			DownstreamException failure = (failures != null) ? failures.poll() : null;
			if (failure != null) {
				throw failure;
			}
			EngagementTemplateState state = states.get(engagementId);
			if (state == null) {
				throw new DownstreamException.NotFound(engagementId);
			}
			return new EngagementTemplateState(state.engagementId(), state.currentVersion(), state.declinedVersion(),
					clock.instant());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new DownstreamException.Transient("interrupted");
		}
		finally {
			inFlight.decrementAndGet();
		}
	}

	public int totalCalls() {
		return totalCalls.get();
	}

	public int callsFor(String engagementId) {
		AtomicInteger calls = callsPerEngagement.get(engagementId);
		return (calls != null) ? calls.get() : 0;
	}

	public int maxInFlight() {
		return maxInFlight.get();
	}

}
