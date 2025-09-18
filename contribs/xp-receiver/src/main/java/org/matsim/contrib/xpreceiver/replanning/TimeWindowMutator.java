package org.matsim.contrib.xpreceiver.replanning;

import org.matsim.contrib.xpreceiver.core.ReceiverOrder;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;
import org.matsim.contrib.xpreceiver.core.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Applies small time-window perturbations to a receiver plan.
 */
final class TimeWindowMutator {
    private final double step;
    private final Random random;

    TimeWindowMutator(double step, Random random) {
        this.step = step;
        this.random = random;
    }

    ReceiverPlan mutate(ReceiverPlan plan) {
        ReceiverPlan copy = plan.copy();
        List<ReceiverOrder> original = new ArrayList<>(copy.getOrders());
        List<ReceiverOrder> mutated = new ArrayList<>(original.size());
        for (ReceiverOrder order : original) {
            double shift = (random.nextBoolean() ? 1 : -1) * step;
            double newStart = Math.max(0.0, order.getTimeWindow().start() + shift);
            double newEnd = Math.max(newStart + 1.0, order.getTimeWindow().end() + shift);
            mutated.add(order.toBuilder().setTimeWindow(TimeWindow.of(newStart, newEnd)).build());
        }
        ReceiverPlan mutatedPlan = new ReceiverPlan();
        mutatedPlan.setType(copy.getType());
        mutatedPlan.setScore(copy.getScore());
        mutated.forEach(mutatedPlan::addOrder);
        return mutatedPlan;
    }
}
