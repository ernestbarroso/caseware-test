package com.caseware.test.fanout;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

	Sleeper SYSTEM = Thread::sleep;

	void sleep(Duration duration) throws InterruptedException;

}
