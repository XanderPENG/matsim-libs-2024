package org.matsim.contrib.freightcollaboration.learning;

/** Immutable diagnostics for a tried allocation factor, including factors evicted from live plan memory. */
public record FactorSummary(
	int gridIndex,
	double factor,
	int visits,
	int checkpoints,
	double objectiveValue,
	int checkpointSourceIteration,
	CheckpointReason checkpointReason,
	int lastVisitedIteration,
	FactorMaturity maturity,
	boolean participationFeasible,
	boolean retained
) {
}
