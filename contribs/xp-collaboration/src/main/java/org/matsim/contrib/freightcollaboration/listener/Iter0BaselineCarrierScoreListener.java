package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.freight.receiver.Receiver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Iter0BaselineCarrierScoreListener implements IterationEndsListener {

	private static final Logger LOGGER = LogManager.getLogger(Iter0BaselineCarrierScoreListener.class);

	@Inject private Scenario scenario;
	@Inject private Config config;
	@Inject private CollaborationDataStore dataStore;
	@Inject private FreightCollaborators freightCollaborators;

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int firstIteration = config.controller().getFirstIteration();
		if (event.getIteration() != firstIteration) {
			return;
		}

		FreightCollaborationConfigGroup fccg = (FreightCollaborationConfigGroup)
			scenario.getConfig().getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		if (fccg == null || fccg.getIter0BaselineMode() == FreightCollaborationConfigGroup.Iter0BaselineMode.DISABLED) {
			return;
		}

		if (dataStore.getIter0CarrierBaselineFeeFree() != null || dataStore.getIter0CarrierBaselineFeeIncluded() != null) {
			return;
		}

		Map<Id<Carrier>, Double> feeIncluded = new HashMap<>();
		Map<Id<Carrier>, Double> feeFree = new HashMap<>();
		double feePerReceiver = fccg.CARRIER_CHARGED_FEE;

		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			Double score = carrier.getSelectedPlan() != null ? carrier.getSelectedPlan().getScore() : null;
			if (score == null) {
				LOGGER.warn("Carrier {} has no scored selected plan at iteration {}.", carrier.getId(), firstIteration);
				continue;
			}
			feeIncluded.put(carrier.getId(), score);
			Set<FreightCollaborator<Receiver>> linkedReceivers =
				LinkReceiverAndCarrier.findLinkedReceivers(carrier, freightCollaborators);
			double fee = linkedReceivers.size() * feePerReceiver;
			feeFree.put(carrier.getId(), score - fee);
		}

		dataStore.setIter0CarrierBaselineFeeIncluded(feeIncluded);
		dataStore.setIter0CarrierBaselineFeeFree(feeFree);
		LOGGER.info("Cached iteration {} carrier baselines: feeIncluded={}, feeFree={}", firstIteration, feeIncluded.size(), feeFree.size());
	}
}
