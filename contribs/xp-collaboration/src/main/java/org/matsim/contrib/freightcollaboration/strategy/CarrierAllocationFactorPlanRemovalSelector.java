package org.matsim.contrib.freightcollaboration.strategy;

import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Retains factor diversity by removing inferior duplicate-factor plans first. */
public final class CarrierAllocationFactorPlanRemovalSelector
		implements PlanSelector<CarrierPlan, Carrier> {

	@Override
	public CarrierPlan selectPlan(HasPlansAndId<CarrierPlan, Carrier> carrier) {
		CarrierPlan selected = carrier.getSelectedPlan();
		List<? extends CarrierPlan> removable = carrier.getPlans().stream()
			.filter(plan -> plan != selected)
			.filter(plan -> plan.getScore() != null && !plan.getScore().isNaN())
			.toList();

		Map<Double, Integer> factorCounts = new HashMap<>();
		for (CarrierPlan plan : carrier.getPlans()) {
			CarrierAllocationFactor.find(plan).ifPresent(factor -> factorCounts.merge(factor, 1, Integer::sum));
		}
		CarrierPlan duplicate = removable.stream()
			.filter(plan -> CarrierAllocationFactor.find(plan)
				.stream().anyMatch(factor -> factorCounts.getOrDefault(factor, 0) > 1))
			.min(Comparator.comparingDouble(CarrierPlan::getScore))
			.orElse(null);
		if (duplicate != null) {
			return duplicate;
		}

		CarrierPlan worstScored = removable.stream()
			.min(Comparator.comparingDouble(CarrierPlan::getScore))
			.orElse(null);
		if (worstScored != null) {
			return worstScored;
		}

		throw new IllegalStateException("Cannot reduce allocation-factor plan memory without removing an "
			+ "unscored or selected plan. Every non-selected candidate must be scored before removal.");
	}
}
