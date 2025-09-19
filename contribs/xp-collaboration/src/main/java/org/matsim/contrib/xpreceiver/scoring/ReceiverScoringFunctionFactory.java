package org.matsim.contrib.xpreceiver.scoring;

import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.core.scoring.ScoringFunction;

/**
 * Factory for receiver scoring functions.
 */
public interface ReceiverScoringFunctionFactory {
    ScoringFunction createScoringFunction(Receiver receiver);
}
