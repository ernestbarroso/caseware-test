package com.caseware.test.fanout.port;

import java.time.Duration;

public abstract sealed class DownstreamException extends Exception {

	private DownstreamException(String message) {
		super(message);
	}

	public static final class Overloaded extends DownstreamException {

		private final Duration retryAfter;

		public Overloaded(String message, Duration retryAfter) {
			super(message);
			this.retryAfter = retryAfter;
		}

		public Duration retryAfter() {
			return retryAfter;
		}

	}

	public static final class Transient extends DownstreamException {

		public Transient(String message) {
			super(message);
		}

	}

	public static final class NotFound extends DownstreamException {

		public NotFound(String message) {
			super(message);
		}

	}

	public static final class Permanent extends DownstreamException {

		public Permanent(String message) {
			super(message);
		}

	}

}
