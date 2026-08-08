package org.matsim.contrib.freightcollaboration.learning;

/** Immutable diagnostics for a tried allocation factor, including factors evicted from live plan memory. */
public record FactorSummary(
	int gridIndex,
	double factor,
	int visits,
	int matureEvaluations,
	double carrierScoreMean,
	double carrierScoreVariance,
	int lastVisitedIteration,
	FactorMaturity maturity,
	boolean participationFeasible,
	boolean retained
) {
}
