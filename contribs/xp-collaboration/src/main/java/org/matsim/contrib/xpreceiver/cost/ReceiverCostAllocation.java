package org.matsim.contrib.xpreceiver.cost;

import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;

/**
 * Allocates carrier costs to receiver plans.
 */
public interface ReceiverCostAllocation {
    double calculateCost(Receiver receiver, ReceiverPlan plan);
}
