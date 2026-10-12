package com.caseware.test.fanout.model;

import java.util.Objects;

public record VersionId(String productId, String version) {

	public VersionId {
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(version, "version");
		if (productId.isBlank() || version.isBlank()) {
			throw new IllegalArgumentException("productId and version must not be blank");
		}
	}

	public static VersionId of(String productId, String version) {
		return new VersionId(productId, version);
	}

	@Override
	public String toString() {
		return productId + "@" + version;
	}

}
