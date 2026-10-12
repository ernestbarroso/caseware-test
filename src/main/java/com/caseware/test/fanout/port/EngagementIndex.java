package com.caseware.test.fanout.port;

import com.caseware.test.fanout.model.EngagementTemplateState;
import com.caseware.test.fanout.model.IndexPage;

public interface EngagementIndex {

	IndexPage page(String productId, String cursor, int limit);

	void recordObserved(EngagementTemplateState state);
}
