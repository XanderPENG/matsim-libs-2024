package org.matsim.contrib.freightcollaboration.learning;

/** Lifecycle of one carrier's factor-conditioned response learning. */
public enum MutableAfPhase {
	BASELINE,
	ADAPT,
	SWITCH_PENDING,
	WARM_START_TRIAL,
	FINAL_REVISIT,
	FINAL_SELECTION
}
