package org.matsim.contrib.freightcollaboration.learning;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;

import java.util.Objects;

/** Exact identity of a carrier's grid-valued offer; doubles are deliberately not used as keys. */
public record FactorKey(Id<Carrier> carrierId, int gridIndex) {
	public FactorKey {
		Objects.requireNonNull(carrierId, "carrierId");
		if (gridIndex < 0) {
			throw new IllegalArgumentException("gridIndex must be non-negative");
		}
	}
}
