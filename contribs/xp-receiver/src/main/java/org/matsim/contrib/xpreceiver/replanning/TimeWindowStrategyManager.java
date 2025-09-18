package org.matsim.contrib.xpreceiver.replanning;

import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;
import java.util.Collection;
import java.util.Random;

/**
 * Applies the {@link TimeWindowMutator} to receiver plans during replanning iterations.
 */
final class TimeWindowStrategyManager implements ReceiverStrategyManager {
    private final double step;

    TimeWindowStrategyManager(double step) {
        this.step = step;
    }

    @Override
    public void run(Collection<Receiver> receivers, int iteration) {
        if (receivers == null || receivers.isEmpty()) {
            return;
        }
        Random random = new Random(iteration + 37L);
        TimeWindowMutator mutator = new TimeWindowMutator(step, random);
        for (Receiver receiver : receivers) {
            ReceiverPlan selected = receiver.getSelectedPlan();
            if (selected == null || selected.isEmpty()) {
                continue;
            }
            ReceiverPlan mutated = mutator.mutate(selected);
            receiver.addPlan(mutated);
            receiver.setSelectedPlan(mutated);
        }
    }
}
