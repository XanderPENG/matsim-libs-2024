package org.matsim.contrib.freightcollaboration.learning;

import java.util.Objects;

/** The single recoverable joint state retained for one allocation-factor grid point. */
public record FactorCheckpoint(
	int factorIndex,
	double factor,
	int visit,
	int sourceIteration,
	CheckpointReason reason,
	FactorMaturity maturity,
	MutableAfSelectionPolicy selectionPolicy,
	ExecutedJointSnapshot executedState,
	WindowStatistics windowStatistics,
	double objectiveValue,
	boolean participationFeasible
) {
	public FactorCheckpoint {
		reason = Objects.requireNonNull(reason, "reason");
		maturity = Objects.requireNonNull(maturity, "maturity");
		selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy");
		executedState = Objects.requireNonNull(executedState, "executedState");
		windowStatistics = Objects.requireNonNull(windowStatistics, "windowStatistics");
		if (sourceIteration != executedState.observation().executionIteration()) {
			throw new IllegalArgumentException("Checkpoint source iteration must match its executed snapshot.");
		}
		if (!Double.isFinite(objectiveValue)) {
			throw new IllegalArgumentException("Checkpoint objective must be finite.");
		}
	}
}
