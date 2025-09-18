package org.matsim.contrib.xpreceiver.util;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;
import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverImpl;
import org.matsim.contrib.xpreceiver.core.Receivers;
import org.matsim.contrib.xpreceiver.cost.ReceiverCostAllocationEqualProportion;
import org.matsim.contrib.xpreceiver.cost.ReceiverCostAllocationFixed;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

/**
 * Entry points for creating receivers and registering them in a scenario.
 */
public final class ReceiverUtils {
    private static final String SCENARIO_ELEMENT = "xpReceivers";

    private ReceiverUtils() {
    }

    public static Receivers createReceivers() {
        return new Receivers();
    }

    public static Receiver newReceiver(Id<Receiver> id) {
        return new ReceiverImpl(id);
    }

    public static void setReceivers(Receivers receivers, Scenario scenario) {
        scenario.addScenarioElement(SCENARIO_ELEMENT, receivers);
    }

    public static Receivers getReceivers(Scenario scenario) {
        Receivers receivers = (Receivers) scenario.getScenarioElement(SCENARIO_ELEMENT);
        if (receivers == null) {
            receivers = createReceivers();
            setReceivers(receivers, scenario);
        }
        return receivers;
    }

    public static ReceiverConfigGroup getConfigGroup(Config config) {
        return ConfigUtils.addOrGetModule(config, ReceiverConfigGroup.class);
    }

    public static ReceiverCostAllocationFixed createFixedReceiverCostAllocation(double cost) {
        return new ReceiverCostAllocationFixed(cost);
    }

    public static ReceiverCostAllocationEqualProportion createEqualProportionCostAllocation() {
        return new ReceiverCostAllocationEqualProportion();
    }
}
