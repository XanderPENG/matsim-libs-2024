package org.matsim.contrib.freightcollaboration.learning;

/** Evidence level of the most recently validated response at an allocation factor. */
public enum FactorMaturity {
	UNVISITED,
	ADAPTING,
	STABLE_CHECKPOINT,
	FALLBACK_CHECKPOINT,
	NO_FEASIBLE_CHECKPOINT,
	EVICTED
}
