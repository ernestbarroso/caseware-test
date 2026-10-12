package com.caseware.test.fanout;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

	public RetryPolicy {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be >= 1");
		}
		Objects.requireNonNull(baseDelay, "baseDelay");
		Objects.requireNonNull(maxDelay, "maxDelay");
		if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
			throw new IllegalArgumentException("require 0 <= baseDelay <= maxDelay");
		}
	}

	public Duration backoff(int failedAttempt) {
		long capMillis = baseDelay.toMillis() << Math.min(failedAttempt - 1, 30);
		capMillis = Math.min(Math.max(capMillis, 0), maxDelay.toMillis());
		return Duration.ofMillis(ThreadLocalRandom.current().nextLong(capMillis + 1));
	}

}
