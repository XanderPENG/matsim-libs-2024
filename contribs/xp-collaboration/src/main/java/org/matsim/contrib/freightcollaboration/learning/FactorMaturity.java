package org.matsim.contrib.freightcollaboration.learning;

/** Evidence level of the most recently validated response at an allocation factor. */
public enum FactorMaturity {
	UNVISITED,
	ADAPTING,
	MATURE_STABLE,
	VALIDATED_UNSTABLE,
	EVICTED
}
