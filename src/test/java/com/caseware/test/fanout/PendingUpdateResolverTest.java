package com.caseware.test.fanout;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.caseware.test.fanout.PendingUpdateResolver.Offer;
import com.caseware.test.fanout.PendingUpdateResolver.Skip;
import com.caseware.test.fanout.PendingUpdateResolver.SkipReason;
import com.caseware.test.fanout.mocks.InMemoryTemplateLineage;
import com.caseware.test.fanout.model.VerificationStatus;
import com.caseware.test.fanout.model.VersionId;

class PendingUpdateResolverTest {

	private static final VersionId V1 = VersionId.of("fs", "v1");
	private static final VersionId V2 = VersionId.of("fs", "v2");
	private static final VersionId V3 = VersionId.of("fs", "v3");
	private static final VersionId V4 = VersionId.of("fs", "v4");

	private InMemoryTemplateLineage lineage;

	private PendingUpdateResolver resolver;

	@BeforeEach
	void setUp() {
		lineage = new InMemoryTemplateLineage().publish(V1, null, "CA")
			.publish(V2, V1, "CA")
			.publish(V3, V2, "CA")
			.publish(V4, V3, "CA");
		resolver = new PendingUpdateResolver(lineage);
	}

	@Test
	void skipsWithdrawnTarget() {
		lineage.withdraw(V3);
		assertThat(resolver.decide(V1, Optional.empty(), VerificationStatus.VERIFIED, V3)).isEqualTo(new Skip(SkipReason.TARGET_WITHDRAWN));
	}

	@Test
	void declinePinsTheDeclinedVersionButNotLaterOnes() {
		assertThat(resolver.decide(V1, Optional.of(V2), VerificationStatus.VERIFIED, V2)).isEqualTo(new Skip(SkipReason.DECLINED));
		assertThat(resolver.decide(V1, Optional.of(V3), VerificationStatus.VERIFIED, V2)).isEqualTo(new Skip(SkipReason.DECLINED));
		assertThat(resolver.decide(V1, Optional.of(V2), VerificationStatus.VERIFIED, V4)).isEqualTo(new Offer(V1, V4));
	}

}
