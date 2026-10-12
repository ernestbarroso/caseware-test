package com.caseware.test.fanout.port;

import com.caseware.test.fanout.model.DeadLetter;

public interface DeadLetterSink {
	void publish(DeadLetter deadLetter);
}
