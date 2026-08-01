package org.matsim.contrib.freightcollaboration.strategy;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.ExpBetaPlanChanger;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;

import java.util.Objects;

/** Carrier strategy provider used exclusively by mutable-allocation-factor runs. */
public final class MutableAfCarrierStrategyManagerProvider implements Provider<CarrierStrategyManager> {

	private final MutableAllocationFactorConfigGroup mutableConfig;

	@Inject
	private Scenario scenario;

	public MutableAfCarrierStrategyManagerProvider(MutableAllocationFactorConfigGroup mutableConfig) {
		this.mutableConfig = Objects.requireNonNull(mutableConfig, "mutableConfig");
		mutableConfig.validateGrid();
	}

	@Override
	public CarrierStrategyManager get() {
		Objects.requireNonNull(scenario, "scenario was not injected");
		initializeFactors();

		CarrierStrategyManager manager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
		manager.setMaxPlansPerAgent(mutableConfig.getMaxFactorPlans());
		manager.setPlanSelectorForRemoval(new CarrierAllocationFactorPlanRemovalSelector());

		GenericPlanStrategyImpl<CarrierPlan, Carrier> expBetaSelection = new GenericPlanStrategyImpl<>(
			new ExpBetaPlanChanger.Factory<CarrierPlan, Carrier>().build());
		CarrierAllocationFactorPlanStrategy factorMutation = new CarrierAllocationFactorPlanStrategy(
			new ExpBetaPlanChanger.Factory<CarrierPlan, Carrier>().build(), mutableConfig);
		GenericPlanStrategyImpl<CarrierPlan, Carrier> bestSelection = new GenericPlanStrategyImpl<>(
			new BestPlanSelector<>());

		manager.addStrategy(expBetaSelection, null, 1.0);
		manager.addStrategy(factorMutation, null, 0.0);
		manager.addStrategy(bestSelection, null, 0.0);

		int first = scenario.getConfig().controller().getFirstIteration();
		int last = scenario.getConfig().controller().getLastIteration();
		// Iteration 0 is the baseline. Replanning in iteration 1 runs after the first
		// collaboration allocation has been scored, so this is the first safe mutation point.
		int innovationStart = first + 1;
		int freezeIteration = first + (int) Math.round(
			(last - first) * mutableConfig.getDisableInnovationFraction());
		if (freezeIteration <= innovationStart || freezeIteration > last) {
			throw new IllegalArgumentException("Mutable allocation-factor run needs an innovation window: start="
				+ innovationStart + ", freeze=" + freezeIteration + ", last=" + last);
		}

		manager.addChangeRequest(innovationStart, factorMutation, null, mutableConfig.getMutationWeight());
		manager.addChangeRequest(freezeIteration, factorMutation, null, 0.0);
		manager.addChangeRequest(freezeIteration, expBetaSelection, null, 0.0);
		manager.addChangeRequest(freezeIteration, bestSelection, null, 1.0);
		return manager;
	}

	private void initializeFactors() {
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			if (carrier.getPlans().isEmpty()) {
				// Receiver-triggered jsprit creates the initial route during iteration 0 BeforeMobsim.
				// The preserving listener assigns INITIAL_ALLOCATION_FACTOR to that first plan.
				continue;
			}
			for (CarrierPlan plan : carrier.getPlans()) {
				if (CarrierAllocationFactor.find(plan).isEmpty()) {
					CarrierAllocationFactor.set(plan, mutableConfig.getInitialAllocationFactor(), mutableConfig);
				} else {
					CarrierAllocationFactor.require(plan, mutableConfig);
				}
			}
		}
	}
}
