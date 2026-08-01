package org.matsim.contrib.freightcollaboration.strategy;

import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.Objects;

/** A carrier innovation strategy that changes only the offered allocation factor. */
public final class CarrierAllocationFactorPlanStrategy implements GenericPlanStrategy<CarrierPlan, Carrier> {

	private final PlanSelector<CarrierPlan, Carrier> parentSelector;
	private final MutableAllocationFactorConfigGroup config;

	public CarrierAllocationFactorPlanStrategy(PlanSelector<CarrierPlan, Carrier> parentSelector,
			MutableAllocationFactorConfigGroup config) {
		this.parentSelector = Objects.requireNonNull(parentSelector, "parentSelector");
		this.config = Objects.requireNonNull(config, "config");
		config.validateGrid();
	}

	@Override
	public void run(HasPlansAndId<CarrierPlan, Carrier> carrier) {
		Objects.requireNonNull(carrier, "carrier");
		CarrierPlan parent = parentSelector.selectPlan(carrier);
		if (parent == null) {
			throw new IllegalStateException("Cannot mutate allocation factor for a carrier without plans: "
				+ carrier.getId());
		}
		if (parent.getScore() == null || parent.getScore().isNaN()) {
			throw new IllegalStateException("Allocation-factor mutation requires a scored parent plan for carrier "
				+ carrier.getId());
		}

		carrier.setSelectedPlan(parent);
		CarrierPlan mutated = CarriersUtils.copyPlan(parent);
		AttributesUtils.copyTo(parent.getAttributes(), mutated.getAttributes());
		double factor = CarrierAllocationFactor.require(parent, config);
		int currentIndex = config.indexOf(factor);
		int lastIndex = config.gridPointCount() - 1;
		int nextIndex;
		if (currentIndex == 0) {
			nextIndex = 1;
		} else if (currentIndex == lastIndex) {
			nextIndex = lastIndex - 1;
		} else {
			nextIndex = currentIndex + (MatsimRandom.getRandom().nextBoolean() ? 1 : -1);
		}
		CarrierAllocationFactor.set(mutated, config.valueAt(nextIndex), config);
		mutated.setScore(null);
		carrier.addPlan(mutated);
		carrier.setSelectedPlan(mutated);
	}

	@Override
	public void init(ReplanningContext replanningContext) {
		// No shared per-iteration state.
	}

	@Override
	public void finish() {
		// Mutations are applied synchronously in run().
	}

	@Override
	public String toString() {
		return "CarrierAllocationFactorPlanStrategy";
	}
}
