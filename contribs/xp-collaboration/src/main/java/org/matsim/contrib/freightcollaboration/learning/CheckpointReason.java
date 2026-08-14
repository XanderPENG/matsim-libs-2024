package org.matsim.contrib.freightcollaboration.learning;

/** Why a real executed joint state was promoted to a recoverable checkpoint. */
public enum CheckpointReason {
	STABLE_WINDOW,
	MAX_DWELL_FALLBACK,
	FINALIZATION_FALLBACK
}
