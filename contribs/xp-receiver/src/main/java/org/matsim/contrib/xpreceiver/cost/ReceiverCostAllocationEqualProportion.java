package org.matsim.contrib.xpreceiver.cost;

import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverOrder;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;

/**
 * Shares carrier cost equally over the receiver's orders.
 */
public final class ReceiverCostAllocationEqualProportion implements ReceiverCostAllocation {
    @Override
    public double calculateCost(Receiver receiver, ReceiverPlan plan) {
        if (plan == null || plan.isEmpty()) {
            return 0.0;
        }
        double totalCost = 0.0;
        int orderCount = 0;
        for (ReceiverOrder order : plan.getOrders()) {
            totalCost += order.getCarrierCost();
            orderCount++;
        }
        if (orderCount == 0) {
            return 0.0;
        }
        return totalCost / orderCount;
    }
}
