package org.matsim.contrib.xpreceiver.carrier;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;
import org.matsim.contrib.xpreceiver.core.Receivers;
import org.matsim.contrib.xpreceiver.util.ReceiverUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

/**
 * Notifies log output when a carrier replanning iteration should occur.
 */
public final class ReceiverTriggersCarrierReplanningListener implements IterationEndsListener {
    private static final Logger LOG = LogManager.getLogger(ReceiverTriggersCarrierReplanningListener.class);

    private final Scenario scenario;
    private final ReceiverConfigGroup configGroup;

    @Inject
    public ReceiverTriggersCarrierReplanningListener(Scenario scenario, ReceiverConfigGroup configGroup) {
        this.scenario = scenario;
        this.configGroup = configGroup;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        if (event.getIteration() % configGroup.getReceiverReplanningInterval() != 0) {
            return;
        }
        Receivers receivers = ReceiverUtils.getReceivers(scenario);
        LOG.info("Receiver iteration {} ready for carrier replanning of {} receivers.", event.getIteration(), receivers.values().size());
    }
}
