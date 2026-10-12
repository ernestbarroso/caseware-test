package com.caseware.test.fanout.port;

import java.time.Duration;

import com.caseware.test.fanout.model.EngagementTemplateState;

public interface EngagementGateway {
	EngagementTemplateState readTemplateState(String engagementId, Duration deadline) throws DownstreamException;
}
