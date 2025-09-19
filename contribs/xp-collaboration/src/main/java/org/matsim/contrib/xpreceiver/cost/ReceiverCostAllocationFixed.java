package org.matsim.contrib.xpreceiver.cost;

import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;

/**
 * Charges each receiver order a fixed amount.
 */
public final class ReceiverCostAllocationFixed implements ReceiverCostAllocation {
    private final double costPerOrder;

    public ReceiverCostAllocationFixed(double costPerOrder) {
        this.costPerOrder = costPerOrder;
    }

    @Override
    public double calculateCost(Receiver receiver, ReceiverPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return 0.0;
        }
        return costPerOrder * plan.getOrders().size();
    }
}
