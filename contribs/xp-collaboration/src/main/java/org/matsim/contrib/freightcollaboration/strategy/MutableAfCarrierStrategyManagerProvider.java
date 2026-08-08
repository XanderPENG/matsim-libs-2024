package org.matsim.contrib.freightcollaboration.strategy;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;

import java.util.Objects;

/** Carrier strategy provider used exclusively by mutable-allocation-factor runs. */
public final class MutableAfCarrierStrategyManagerProvider implements Provider<CarrierStrategyManager> {

	@Inject
	private Scenario scenario;

	public MutableAfCarrierStrategyManagerProvider(MutableAllocationFactorConfigGroup mutableConfig) {
		Objects.requireNonNull(mutableConfig, "mutableConfig");
		mutableConfig.validateGrid();
	}

	@Override
	public CarrierStrategyManager get() {
		Objects.requireNonNull(scenario, "scenario was not injected");
		CarrierStrategyManager manager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
		manager.setMaxPlansPerAgent(0);
		// AF decisions are joint Carrier/Receiver state transitions. Keeping the generic
		// Carrier listener as a no-op prevents it from independently copying or removing plans.
		manager.addStrategy(new GenericPlanStrategyImpl<>(new KeepSelected<>()), null, 1.0);
		return manager;
	}
}
