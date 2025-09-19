package org.matsim.contrib.xpreceiver.replanning;

import org.matsim.contrib.xpreceiver.core.Receiver;

import java.util.Collection;

/**
 * Minimal org.matsim.freightcollaboration.strategy manager API.
 */
public interface ReceiverStrategyManager {
    void run(Collection<Receiver> receivers, int iteration);
}
