package com.caseware.test.fanout.mocks;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.caseware.test.fanout.model.VersionId;
import com.caseware.test.fanout.port.TemplateLineage;

public final class InMemoryTemplateLineage implements TemplateLineage {

	private record Node(VersionId parent, String branch) {
	}

	private final Map<VersionId, Node> nodes = new LinkedHashMap<>();

	private final Set<VersionId> withdrawn = new HashSet<>();

	public synchronized InMemoryTemplateLineage publish(VersionId version, VersionId parent, String branch) {
		if (parent != null && !nodes.containsKey(parent)) {
			throw new IllegalArgumentException("unknown parent " + parent);
		}
		nodes.put(version, new Node(parent, branch));
		return this;
	}

	public synchronized void withdraw(VersionId version) {
		withdrawn.add(version);
	}

	@Override
	public synchronized boolean supersedes(VersionId descendant, VersionId ancestor) {
		Node node = nodes.get(descendant);
		while (node != null && node.parent() != null) {
			if (node.parent().equals(ancestor)) {
				return true;
			}
			node = nodes.get(node.parent());
		}
		return false;
	}

	@Override
	public synchronized boolean isWithdrawn(VersionId version) {
		return withdrawn.contains(version);
	}

	@Override
	public synchronized VersionId latestOnBranchOf(VersionId version) {
		String branch = nodes.get(version).branch();
		List<VersionId> onBranch = nodes.entrySet()
			.stream()
			.filter(e -> e.getValue().branch().equals(branch) && !withdrawn.contains(e.getKey()))
			.map(Map.Entry::getKey)
			.toList();
		return onBranch.isEmpty() ? version : onBranch.getLast();
	}

}
