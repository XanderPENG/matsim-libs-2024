package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;

import java.util.Map;

public class LspReceiverAfterRerouteListener implements BeforeMobsimListener {

	@Inject
	CollaborationDataStore collaborationDataStore;

	@Inject
	Scenario scenario;

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		Carriers carriers = CarriersUtils.getCarriers(scenario);

		// restore the retained carrier plans and shipments/services for carriers
		Map<Id<Carrier>, Carrier> lspReceiverCopiedNonDistrCarriers = collaborationDataStore.getLspReceiverCopiedNonDistrCarriers();

		for (Map.Entry<Id<Carrier>, Carrier> entry : lspReceiverCopiedNonDistrCarriers.entrySet()) {
			Id<Carrier> carrierId = entry.getKey();
			Carrier copiedCarrier = entry.getValue();
			Carrier carrier = carriers.getCarriers().get(carrierId);
			if (carrier == null) {
				throw  new IllegalStateException("Carrier " + carrierId + " not found");
			}
			// TODO: Do not know if we need to deep copy the plans and shipments/services again here; seems not necessary
			// add shipments and services back to the carrier
			carrier.getServices().putAll(copiedCarrier.getServices());
			carrier.getShipments().putAll(copiedCarrier.getShipments());
			// replace the selected plan and add it to the carrier plans
			carrier.addPlan(copiedCarrier.getSelectedPlan());
			carrier.setSelectedPlan(copiedCarrier.getSelectedPlan());
		}
	}


	/**
	 * This listener should run after LspReceiverPreRerouteListener and ReceiverTriggerCarrierReplanningListener
	 * @return
	 */
	@Override
	public double priority() {
		return -10;
	}
}
