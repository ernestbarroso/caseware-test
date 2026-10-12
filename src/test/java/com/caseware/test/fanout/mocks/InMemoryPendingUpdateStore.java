package com.caseware.test.fanout.mocks;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.caseware.test.fanout.model.PendingUpdate;
import com.caseware.test.fanout.port.PendingUpdateStore;

public final class InMemoryPendingUpdateStore implements PendingUpdateStore {

	private final Map<String, PendingUpdate> updates = new ConcurrentHashMap<>();

	@Override
	public Optional<PendingUpdate> find(String engagementId) {
		return Optional.ofNullable(updates.get(engagementId));
	}

	@Override
	public boolean compareAndSet(String engagementId, Optional<PendingUpdate> expected, PendingUpdate update) {
		return expected.map(e -> updates.replace(engagementId, e, update))
			.orElseGet(() -> updates.putIfAbsent(engagementId, update) == null);
	}

}
