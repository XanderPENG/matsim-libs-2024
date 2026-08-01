package org.matsim.contrib.freightcollaboration.config;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Opt-in configuration for allowing carriers to learn their allocation factor.
 * Merely omitting this module keeps the existing fixed-factor collaboration path.
 */
public final class MutableAllocationFactorConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "mutableAllocationFactor";
	private static final double EPSILON = 1e-9;

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
	public int MAX_FACTOR_PLANS = 11;

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
		if (value < 1) {
			throw new IllegalArgumentException("MAX_FACTOR_PLANS must be positive.");
		}
		MAX_FACTOR_PLANS = value;
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
		if (MAX_FACTOR_PLANS < gridPoints) {
			throw new IllegalArgumentException("MAX_FACTOR_PLANS must be at least the number of grid points ("
				+ gridPoints + ").");
		}
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
}
