package com.caseware.test.fanout;

import java.time.Duration;
import java.util.Objects;

public record FanoutSettings(int maxConcurrentDownstreamCalls, int pageSize, Duration callTimeout, RetryPolicy retry,
		Duration lease) {

	public FanoutSettings {
		if (maxConcurrentDownstreamCalls < 1) {
			throw new IllegalArgumentException("maxConcurrentDownstreamCalls must be >= 1");
		}
		if (pageSize < 1) {
			throw new IllegalArgumentException("pageSize must be >= 1");
		}
		Objects.requireNonNull(callTimeout, "callTimeout");
		Objects.requireNonNull(retry, "retry");
		Objects.requireNonNull(lease, "lease");
	}

}
