package com.caseware.test.fanout.mocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import com.caseware.test.fanout.model.EngagementIndexEntry;
import com.caseware.test.fanout.model.EngagementTemplateState;
import com.caseware.test.fanout.model.IndexPage;
import com.caseware.test.fanout.model.VerificationStatus;
import com.caseware.test.fanout.port.EngagementIndex;

public final class InMemoryEngagementIndex implements EngagementIndex {

	private final TreeMap<String, EngagementIndexEntry> rows = new TreeMap<>();

	public synchronized void put(EngagementIndexEntry entry) {
		rows.put(entry.engagementId(), entry);
	}

	public synchronized EngagementIndexEntry get(String engagementId) {
		return rows.get(engagementId);
	}

	@Override
	public synchronized IndexPage page(String productId, String cursor, int limit) {
		var tail = (cursor == null) ? rows.values() : rows.tailMap(cursor, false).values();
		List<EngagementIndexEntry> matching = new ArrayList<>();
		boolean more = false;
		for (EngagementIndexEntry entry : tail) {
			if (!entry.currentVersion().productId().equals(productId)) {
				continue;
			}
			if (matching.size() == limit) {
				more = true;
				break;
			}
			matching.add(entry);
		}
		Optional<String> next = more ? Optional.of(matching.getLast().engagementId()) : Optional.empty();
		return new IndexPage(matching, next);
	}

	@Override
	public synchronized void recordObserved(EngagementTemplateState state) {
		EngagementIndexEntry existing = rows.get(state.engagementId());
		if (existing == null || !state.observedAt().isAfter(existing.observedAt())) {
			return;
		}
		rows.put(state.engagementId(), new EngagementIndexEntry(state.engagementId(), existing.firmId(),
				state.currentVersion(), state.declinedVersion(), VerificationStatus.VERIFIED, state.observedAt()));
	}

}
