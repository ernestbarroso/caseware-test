package com.caseware.test.fanout.port;

import java.util.Optional;

import com.caseware.test.fanout.model.PendingUpdate;

public interface PendingUpdateStore {

	Optional<PendingUpdate> find(String engagementId);

	boolean compareAndSet(String engagementId, Optional<PendingUpdate> expected, PendingUpdate update);

}
