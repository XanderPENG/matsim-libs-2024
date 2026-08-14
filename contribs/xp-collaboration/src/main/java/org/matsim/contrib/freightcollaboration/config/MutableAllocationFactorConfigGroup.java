package org.matsim.contrib.freightcollaboration.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.freightcollaboration.learning.MutableAfSelectionPolicy;
import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in configuration for allowing carriers to learn their allocation factor.
 * Merely omitting this module keeps the existing fixed-factor collaboration path.
 */
public final class MutableAllocationFactorConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "mutableAllocationFactor";
	private static final double EPSILON = 1e-9;
	private static final Logger LOG = LogManager.getLogger(MutableAllocationFactorConfigGroup.class);

	@Parameter
	public double INITIAL_ALLOCATION_FACTOR = 0.8;

	@Parameter
	public double MIN_ALLOCATION_FACTOR = 0.0;

	@Parameter
	public double MAX_ALLOCATION_FACTOR = 1.0;

	@Parameter
	public double ALLOCATION_FACTOR_STEP = 0.1;

	@Parameter
	public double MUTATION_WEIGHT = 1.0;

	@Parameter
	public double DISABLE_INNOVATION_FRACTION = 0.9;

	@Parameter
	public int MAX_FACTOR_PLANS = 5;

	@Parameter
	public int NEW_FACTOR_MIN_DWELL = 6;

	@Parameter
	public int REVISIT_FACTOR_MIN_DWELL = 3;

	@Parameter
	public int STABILITY_WINDOW = 5;

	@Parameter
	public int MAX_ADAPT_DWELL = 15;

	/** @deprecated Repeated EVALUATE was removed; retained only for old XML/CLI compatibility. */
	@Deprecated
	@Parameter
	public int EVALUATION_WINDOW = 3;

	@Parameter
	public double STABILITY_RELATIVE_TOLERANCE = 0.05;

	@Parameter
	public double COALITION_STABILITY_THRESHOLD = 0.70;

	@Parameter
	public String SOLUTION_SELECTION_POLICY = MutableAfSelectionPolicy.CARRIER_BEST.configValue();

	@Parameter
	public double PARTICIPATION_RELATIVE_TOLERANCE = 1e-6;

	@Parameter
	public double MIN_EXPLORATION_PROBABILITY = 0.10;

	@Parameter
	public double MAX_EXPLORATION_PROBABILITY = 0.80;

	@Parameter
	public double EXPLOITATION_BETA = 4.0;

	@Parameter
	public int MAX_RECEIVER_PLANS_PER_FACTOR = 5;

	private static final AtomicBoolean EVALUATION_WINDOW_WARNING_LOGGED = new AtomicBoolean();

	public MutableAllocationFactorConfigGroup() {
		super(GROUP_NAME);
	}

	public double getInitialAllocationFactor() {
		return INITIAL_ALLOCATION_FACTOR;
	}

	public void setInitialAllocationFactor(double value) {
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw new IllegalArgumentException("INITIAL_ALLOCATION_FACTOR must be finite and in [0,1].");
		}
		INITIAL_ALLOCATION_FACTOR = value;
	}

	public double getMinAllocationFactor() {
		return MIN_ALLOCATION_FACTOR;
	}

	public void setMinAllocationFactor(double value) {
		if (!Double.isFinite(value) || value < 0.0 || value >= MAX_ALLOCATION_FACTOR) {
			throw new IllegalArgumentException("MIN_ALLOCATION_FACTOR must be finite and satisfy 0 <= min < max.");
		}
		MIN_ALLOCATION_FACTOR = value;
	}

	public double getMaxAllocationFactor() {
		return MAX_ALLOCATION_FACTOR;
	}

	public void setMaxAllocationFactor(double value) {
		if (!Double.isFinite(value) || value > 1.0 || value <= MIN_ALLOCATION_FACTOR) {
			throw new IllegalArgumentException("MAX_ALLOCATION_FACTOR must be finite and satisfy min < max <= 1.");
		}
		MAX_ALLOCATION_FACTOR = value;
	}

	public double getAllocationFactorStep() {
		return ALLOCATION_FACTOR_STEP;
	}

	public void setAllocationFactorStep(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			throw new IllegalArgumentException("ALLOCATION_FACTOR_STEP must be finite and positive.");
		}
		ALLOCATION_FACTOR_STEP = value;
	}

	public double getMutationWeight() {
		return MUTATION_WEIGHT;
	}

	public void setMutationWeight(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			throw new IllegalArgumentException("MUTATION_WEIGHT must be finite and positive.");
		}
		MUTATION_WEIGHT = value;
	}

	public double getDisableInnovationFraction() {
		return DISABLE_INNOVATION_FRACTION;
	}

	public void setDisableInnovationFraction(double value) {
		if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
			throw new IllegalArgumentException("DISABLE_INNOVATION_FRACTION must be in (0,1].");
		}
		DISABLE_INNOVATION_FRACTION = value;
	}

	public int getMaxFactorPlans() {
		return MAX_FACTOR_PLANS;
	}

	public void setMaxFactorPlans(int value) {
		if (value < 2) {
			throw new IllegalArgumentException("MAX_FACTOR_PLANS must be at least 2.");
		}
		MAX_FACTOR_PLANS = value;
	}

	public int getNewFactorMinDwell() {
		return NEW_FACTOR_MIN_DWELL;
	}

	public void setNewFactorMinDwell(int value) {
		NEW_FACTOR_MIN_DWELL = requirePositive(value, "NEW_FACTOR_MIN_DWELL");
	}

	public int getRevisitFactorMinDwell() {
		return REVISIT_FACTOR_MIN_DWELL;
	}

	public void setRevisitFactorMinDwell(int value) {
		REVISIT_FACTOR_MIN_DWELL = requirePositive(value, "REVISIT_FACTOR_MIN_DWELL");
	}

	public int getStabilityWindow() {
		return STABILITY_WINDOW;
	}

	public void setStabilityWindow(int value) {
		STABILITY_WINDOW = requirePositive(value, "STABILITY_WINDOW");
	}

	public int getMaxAdaptDwell() {
		return MAX_ADAPT_DWELL;
	}

	public void setMaxAdaptDwell(int value) {
		MAX_ADAPT_DWELL = requirePositive(value, "MAX_ADAPT_DWELL");
	}

	/** @deprecated Accepted for compatibility but ignored by the learning state machine. */
	@Deprecated
	public int getEvaluationWindow() {
		return EVALUATION_WINDOW;
	}

	/** @deprecated Accepted for compatibility but ignored by the learning state machine. */
	@Deprecated
	public void setEvaluationWindow(int value) {
		EVALUATION_WINDOW = requirePositive(value, "EVALUATION_WINDOW");
		warnEvaluationWindowIgnored();
	}

	public double getStabilityRelativeTolerance() {
		return STABILITY_RELATIVE_TOLERANCE;
	}

	public void setStabilityRelativeTolerance(double value) {
		STABILITY_RELATIVE_TOLERANCE = requireNonNegativeFinite(value,
			"STABILITY_RELATIVE_TOLERANCE");
	}

	public double getCoalitionStabilityThreshold() {
		return COALITION_STABILITY_THRESHOLD;
	}

	public void setCoalitionStabilityThreshold(double value) {
		COALITION_STABILITY_THRESHOLD = requireProbability(value,
			"COALITION_STABILITY_THRESHOLD");
	}

	public MutableAfSelectionPolicy getSolutionSelectionPolicy() {
		return MutableAfSelectionPolicy.parse(SOLUTION_SELECTION_POLICY);
	}

	public void setSolutionSelectionPolicy(MutableAfSelectionPolicy value) {
		SOLUTION_SELECTION_POLICY = java.util.Objects.requireNonNull(value, "value").configValue();
	}

	public void setSolutionSelectionPolicy(String value) {
		SOLUTION_SELECTION_POLICY = MutableAfSelectionPolicy.parse(value).configValue();
	}

	public double getParticipationRelativeTolerance() {
		return PARTICIPATION_RELATIVE_TOLERANCE;
	}

	public void setParticipationRelativeTolerance(double value) {
		PARTICIPATION_RELATIVE_TOLERANCE = requireNonNegativeFinite(value,
			"PARTICIPATION_RELATIVE_TOLERANCE");
	}

	public double getMinExplorationProbability() {
		return MIN_EXPLORATION_PROBABILITY;
	}

	public void setMinExplorationProbability(double value) {
		MIN_EXPLORATION_PROBABILITY = requireProbability(value, "MIN_EXPLORATION_PROBABILITY");
	}

	public double getMaxExplorationProbability() {
		return MAX_EXPLORATION_PROBABILITY;
	}

	public void setMaxExplorationProbability(double value) {
		MAX_EXPLORATION_PROBABILITY = requireProbability(value, "MAX_EXPLORATION_PROBABILITY");
	}

	public double getExploitationBeta() {
		return EXPLOITATION_BETA;
	}

	public void setExploitationBeta(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			throw new IllegalArgumentException("EXPLOITATION_BETA must be finite and positive.");
		}
		EXPLOITATION_BETA = value;
	}

	public int getMaxReceiverPlansPerFactor() {
		return MAX_RECEIVER_PLANS_PER_FACTOR;
	}

	public void setMaxReceiverPlansPerFactor(int value) {
		MAX_RECEIVER_PLANS_PER_FACTOR = requirePositive(value, "MAX_RECEIVER_PLANS_PER_FACTOR");
	}

	public int gridPointCount() {
		validateGridBounds();
		double steps = (MAX_ALLOCATION_FACTOR - MIN_ALLOCATION_FACTOR) / ALLOCATION_FACTOR_STEP;
		long rounded = Math.round(steps);
		if (Math.abs(steps - rounded) > EPSILON) {
			throw new IllegalArgumentException(
				"Allocation-factor step must exactly divide [min,max].");
		}
		return Math.toIntExact(rounded + 1L);
	}

	public boolean isOnGrid(double value) {
		if (!Double.isFinite(value) || value < MIN_ALLOCATION_FACTOR - EPSILON
			|| value > MAX_ALLOCATION_FACTOR + EPSILON) {
			return false;
		}
		double position = (value - MIN_ALLOCATION_FACTOR) / ALLOCATION_FACTOR_STEP;
		return Math.abs(position - Math.rint(position)) <= EPSILON;
	}

	public double valueAt(int gridIndex) {
		int points = gridPointCount();
		if (gridIndex < 0 || gridIndex >= points) {
			throw new IllegalArgumentException("Grid index out of range: " + gridIndex);
		}
		return canonicalize(MIN_ALLOCATION_FACTOR + gridIndex * ALLOCATION_FACTOR_STEP);
	}

	public int indexOf(double value) {
		if (!isOnGrid(value)) {
			throw new IllegalArgumentException("Allocation factor is not on the configured grid: " + value);
		}
		return (int) Math.round((value - MIN_ALLOCATION_FACTOR) / ALLOCATION_FACTOR_STEP);
	}

	public void validateGrid() {
		int gridPoints = gridPointCount();
		if (!isOnGrid(INITIAL_ALLOCATION_FACTOR)) {
			throw new IllegalArgumentException("INITIAL_ALLOCATION_FACTOR must lie on the configured grid.");
		}
		if (!Double.isFinite(MUTATION_WEIGHT) || MUTATION_WEIGHT <= 0.0) {
			throw new IllegalArgumentException("MUTATION_WEIGHT must be finite and positive.");
		}
		if (!Double.isFinite(DISABLE_INNOVATION_FRACTION)
			|| DISABLE_INNOVATION_FRACTION <= 0.0 || DISABLE_INNOVATION_FRACTION > 1.0) {
			throw new IllegalArgumentException("DISABLE_INNOVATION_FRACTION must be in (0,1].");
		}
		if (MAX_FACTOR_PLANS < 2 || MAX_FACTOR_PLANS > gridPoints) {
			throw new IllegalArgumentException("MAX_FACTOR_PLANS must be between 2 and the number of grid points ("
				+ gridPoints + ").");
		}
		if (NEW_FACTOR_MIN_DWELL < 1 || REVISIT_FACTOR_MIN_DWELL < 1 || STABILITY_WINDOW < 1
			|| MAX_ADAPT_DWELL < 1 || EVALUATION_WINDOW < 1) {
			throw new IllegalArgumentException("Mutable-AF dwell and window parameters must be positive.");
		}
		if (MAX_ADAPT_DWELL < NEW_FACTOR_MIN_DWELL
			|| MAX_ADAPT_DWELL < REVISIT_FACTOR_MIN_DWELL
			|| MAX_ADAPT_DWELL < STABILITY_WINDOW) {
			throw new IllegalArgumentException("MAX_ADAPT_DWELL must be at least STABILITY_WINDOW, "
				+ "NEW_FACTOR_MIN_DWELL, and REVISIT_FACTOR_MIN_DWELL.");
		}
		requireNonNegativeFinite(STABILITY_RELATIVE_TOLERANCE, "STABILITY_RELATIVE_TOLERANCE");
		requireProbability(COALITION_STABILITY_THRESHOLD, "COALITION_STABILITY_THRESHOLD");
		requireNonNegativeFinite(PARTICIPATION_RELATIVE_TOLERANCE, "PARTICIPATION_RELATIVE_TOLERANCE");
		requireProbability(MIN_EXPLORATION_PROBABILITY, "MIN_EXPLORATION_PROBABILITY");
		requireProbability(MAX_EXPLORATION_PROBABILITY, "MAX_EXPLORATION_PROBABILITY");
		if (MIN_EXPLORATION_PROBABILITY > MAX_EXPLORATION_PROBABILITY) {
			throw new IllegalArgumentException("MIN_EXPLORATION_PROBABILITY must not exceed MAX_EXPLORATION_PROBABILITY.");
		}
		if (!Double.isFinite(EXPLOITATION_BETA) || EXPLOITATION_BETA <= 0.0) {
			throw new IllegalArgumentException("EXPLOITATION_BETA must be finite and positive.");
		}
		if (MAX_RECEIVER_PLANS_PER_FACTOR < 1) {
			throw new IllegalArgumentException("MAX_RECEIVER_PLANS_PER_FACTOR must be positive.");
		}
		getSolutionSelectionPolicy();
		warnEvaluationWindowIgnored();
	}

	public int finalizationIteration(int firstIteration, int lastIteration) {
		validateGrid();
		if (lastIteration <= firstIteration) {
			throw new IllegalArgumentException("Mutable allocation-factor learning needs lastIteration > firstIteration.");
		}
		int finalization = firstIteration + (int) Math.round(
			(lastIteration - firstIteration) * DISABLE_INNOVATION_FRACTION);
		int firstVisitComplete = firstIteration + 1 + Math.max(NEW_FACTOR_MIN_DWELL, STABILITY_WINDOW);
		if (finalization < firstVisitComplete) {
			throw new IllegalArgumentException("Mutable allocation-factor run is too short: finalization iteration "
				+ finalization + " occurs before a first factor can fill its dwell/stability window at iteration "
				+ firstVisitComplete + ".");
		}
		return finalization;
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		validateGrid();
	}

	private void validateGridBounds() {
		if (!Double.isFinite(MIN_ALLOCATION_FACTOR) || !Double.isFinite(MAX_ALLOCATION_FACTOR)
			|| !Double.isFinite(ALLOCATION_FACTOR_STEP)) {
			throw new IllegalArgumentException("Allocation-factor grid values must be finite.");
		}
		if (MIN_ALLOCATION_FACTOR < 0.0 || MAX_ALLOCATION_FACTOR > 1.0
			|| MIN_ALLOCATION_FACTOR >= MAX_ALLOCATION_FACTOR) {
			throw new IllegalArgumentException("Allocation-factor grid must satisfy 0 <= min < max <= 1.");
		}
		if (ALLOCATION_FACTOR_STEP <= 0.0) {
			throw new IllegalArgumentException("ALLOCATION_FACTOR_STEP must be positive.");
		}
	}

	private static double canonicalize(double value) {
		return Math.rint(value * 1_000_000_000_000L) / 1_000_000_000_000L;
	}

	private static int requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be positive.");
		}
		return value;
	}

	private static double requireNonNegativeFinite(double value, String name) {
		if (!Double.isFinite(value) || value < 0.0) {
			throw new IllegalArgumentException(name + " must be finite and non-negative.");
		}
		return value;
	}

	private static double requireProbability(double value, String name) {
		if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
			throw new IllegalArgumentException(name + " must be in [0,1].");
		}
		return value;
	}

	private void warnEvaluationWindowIgnored() {
		if (EVALUATION_WINDOW_WARNING_LOGGED.compareAndSet(false, true)) {
			LOG.warn("EVALUATION_WINDOW is deprecated and ignored: mutable-AF checkpoints now use "
				+ "real ADAPT iteration snapshots without repeated EVALUATE rounds.");
		}
	}
}
