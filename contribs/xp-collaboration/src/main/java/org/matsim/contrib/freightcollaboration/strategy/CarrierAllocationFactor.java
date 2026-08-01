package org.matsim.contrib.freightcollaboration.strategy;

import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.freight.carriers.CarrierPlan;

import java.util.Objects;
import java.util.OptionalDouble;

/** Stores the carrier's offered allocation factor on a {@link CarrierPlan}. */
public final class CarrierAllocationFactor {

	public static final String ATTRIBUTE_NAME = "freightCollaboration:allocationFactor";

	private CarrierAllocationFactor() {
	}

	public static OptionalDouble find(CarrierPlan plan) {
		Objects.requireNonNull(plan, "plan");
		Object value = plan.getAttributes().getAttribute(ATTRIBUTE_NAME);
		if (value == null) {
			return OptionalDouble.empty();
		}
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("Carrier-plan allocation factor is not numeric: " + value);
		}
		double factor = number.doubleValue();
		validateUnitInterval(factor);
		return OptionalDouble.of(factor);
	}

	public static double require(CarrierPlan plan) {
		return find(plan).orElseThrow(() -> new IllegalStateException(
			"Carrier plan has no '" + ATTRIBUTE_NAME + "' attribute."));
	}

	public static double require(CarrierPlan plan, MutableAllocationFactorConfigGroup config) {
		double factor = require(plan);
		if (!Objects.requireNonNull(config, "config").isOnGrid(factor)) {
			throw new IllegalStateException("Carrier-plan allocation factor is outside the configured grid: " + factor);
		}
		return factor;
	}

	public static void set(CarrierPlan plan, double factor) {
		Objects.requireNonNull(plan, "plan");
		validateUnitInterval(factor);
		plan.getAttributes().putAttribute(ATTRIBUTE_NAME, factor);
	}

	public static void set(CarrierPlan plan, double factor, MutableAllocationFactorConfigGroup config) {
		Objects.requireNonNull(config, "config").validateGrid();
		if (!config.isOnGrid(factor)) {
			throw new IllegalArgumentException("Allocation factor is not on the configured grid: " + factor);
		}
		set(plan, factor);
	}

	private static void validateUnitInterval(double factor) {
		if (!Double.isFinite(factor) || factor < 0.0 || factor > 1.0) {
			throw new IllegalArgumentException("Allocation factor must be finite and in [0,1].");
		}
	}
}
