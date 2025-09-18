package org.matsim.contrib.xpreceiver.control;

import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;
import org.matsim.contrib.xpreceiver.config.ReceiverReplanningType;
import org.matsim.contrib.xpreceiver.cost.ReceiverCostAllocation;
import org.matsim.contrib.xpreceiver.replanning.ReceiverReplanningUtils;
import org.matsim.contrib.xpreceiver.replanning.ReceiverStrategyManager;
import org.matsim.contrib.xpreceiver.scoring.ReceiverScoringFunctionFactory;
import org.matsim.contrib.xpreceiver.scoring.ReceiverScoringFunctionFactoryMoneyOnly;
import org.matsim.contrib.xpreceiver.carrier.ReceiverTriggersCarrierReplanningListener;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;

/**
 * Binds the xp-receiver components into a MATSim controler.
 */
public final class ReceiverModule extends AbstractModule {
    private final ReceiverCostAllocation costAllocation;

    public ReceiverModule(ReceiverCostAllocation costAllocation) {
        this.costAllocation = costAllocation;
    }

    @Override
    public void install() {
        ReceiverConfigGroup configGroup = ConfigUtils.addOrGetModule(getConfig(), ReceiverConfigGroup.class);

        bind(ReceiverConfigGroup.class).toInstance(configGroup);
        bind(ReceiverCostAllocation.class).toInstance(costAllocation);
        bind(ReceiverScoringFunctionFactory.class).toInstance(new ReceiverScoringFunctionFactoryMoneyOnly());

        if (configGroup.getReplanningType() == ReceiverReplanningType.timeWindow) {
            bind(ReceiverStrategyManager.class).toProvider(ReceiverReplanningUtils.timeWindowStrategyProvider());
        } else {
            bind(ReceiverStrategyManager.class).toInstance((receivers, iteration) -> { });
        }

        addControlerListenerBinding().to(ReceiverControlerListener.class);
        addControlerListenerBinding().to(ReceiverTriggersCarrierReplanningListener.class);
    }
}
