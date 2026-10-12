package com.caseware.test.fanout.port;

import com.caseware.test.fanout.model.VersionId;

public interface TemplateLineage {

	boolean supersedes(VersionId descendant, VersionId ancestor);

	boolean isWithdrawn(VersionId version);

	VersionId latestOnBranchOf(VersionId version);

}
