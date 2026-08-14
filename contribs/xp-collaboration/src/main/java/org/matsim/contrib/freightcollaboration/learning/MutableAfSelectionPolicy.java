package org.matsim.contrib.freightcollaboration.learning;

import java.util.Arrays;
import java.util.Locale;

/** Objective used consistently for visit fallback, exploitation, eviction and final selection. */
public enum MutableAfSelectionPolicy {
	CARRIER_BEST("carrier-best"),
	RECEIVER_BEST("receiver-best"),
	BEST_SURPLUS("best-surplus");

	private final String configValue;

	MutableAfSelectionPolicy(String configValue) {
		this.configValue = configValue;
	}

	public String configValue() {
		return configValue;
	}

	public static MutableAfSelectionPolicy parse(String value) {
		if (value == null) {
			throw new IllegalArgumentException("Mutable-AF selection policy must not be null.");
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		return Arrays.stream(values())
			.filter(policy -> policy.configValue.equals(normalized))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"Unknown mutable-AF selection policy '" + value
					+ "'. Expected carrier-best, receiver-best, or best-surplus."));
	}
}
