package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.matsim.contrib.freightcollaboration.utils.AllocationUtils.copyNoScoreCarrierPlan;

/**
 * Since the receiver module will clean and replan the all the carrier plans,
 * which will affect the two-echelon LSP plans; thus, we need to keep the "non-distribution carriers" plans
 * and restore them after the receiver rerouting is done in LspReceiverAfterRerouteListener.
 */

public class LspReceiverPreRerouteListener implements BeforeMobsimListener {

	@Inject
	CollaborationDataStore collaborationDataStore;

	@Inject
	Scenario scenario;

	@Inject
	Config config;

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		// Get LSPs from the scenario
//		LSPs lsps = LSPUtils.getLSPs(scenario);

		/* Since the LSP package has strict access modifier for MainRunCarrierResource/CollectionCarrierResource
		 * we cannot directly check the instance type or get those carrier resources' plans here.
		 * Alternatively, we need to tell users specify the carrierId in the config file when defining those resources,
		 * so that we can identify those resources here and save their plans for later restoration.
		 */
		Map<Id<Carrier>, Carrier> copiedCarriers = new HashMap<>();
		Carriers carriers = CarriersUtils.getCarriers(scenario);

		FreightCollaborationConfigGroup fccg = (FreightCollaborationConfigGroup) config.getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		// filter out the collabroationParamSet with the collabroation type LSP_RECEIVER
		CollaborationParamSet lspReceiverParamSet = fccg.getCollaborationParamSets().stream()
				.filter(paramSet -> paramSet.getCollaborationType().equals(CollaborationTypes.LSP_RECEIVER))
				.findFirst()
				.orElseThrow(() -> new RuntimeException("No collaborationParamSet with collaboration type LSP_RECEIVER found in the config file."));

		/**
		 * TODO: Find more elegant way to get those carrier ids from the config file;
		 * A better way is to get all carriers from the scenario, and these carriers should have an attribute:
		 * "org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils$CARRIER_TYPE"
		 * Then we can judge if it is a main run carrier or collection carrier directly.
		 */
		Set<String> mainRunCarrierStringIds = lspReceiverParamSet.getAdditionalParams().get("MAIN_RUN_CARRIER_IDS");
		Set<String> collectionCarrierStringIds = lspReceiverParamSet.getAdditionalParams().get("COLLECTION_CARRIER_IDS");

		// Get corresponding carriers' id and plans and deep copy them, then store them in the map
		if (mainRunCarrierStringIds != null) {
			for (String carrierIdStr : mainRunCarrierStringIds) {
				Id<Carrier> carrierId = Id.create(carrierIdStr, Carrier.class);
				Carrier carrier = carriers.getCarriers().get(carrierId);
				if (carrier == null) {
					throw new RuntimeException("Carrier with id " + carrierId + " not found in the carriers map.");
				}
				CarrierPlan carrierPlan = carrier.getSelectedPlan();
				if (carrierPlan == null) {
					throw new RuntimeException("Selected plan for carrier with id " + carrierId + " is null.");
				}
				Carrier newCarrier = deepCopyCarrier(carrier);
				copiedCarriers.put(carrierId, newCarrier);
			}
		}
		if (collectionCarrierStringIds != null) {
			for (String carrierIdStr : collectionCarrierStringIds) {
				Id<Carrier> carrierId = Id.create(carrierIdStr, Carrier.class);
				Carrier carrier = carriers.getCarriers().get(carrierId);
				if (carrier == null) {
					throw new RuntimeException("Carrier with id " + carrierId + " not found in the carriers map.");
				}
				CarrierPlan carrierPlan = carrier.getSelectedPlan();
				if (carrierPlan == null) {
					throw new RuntimeException("Selected plan for carrier with id " + carrierId + " is null.");
				}
				Carrier newCarrier = deepCopyCarrier(carrier);
				copiedCarriers.put(carrierId, newCarrier);
			}
		}
		// Store the retained carrier plans in the collaboration data store for later restoration
		collaborationDataStore.setLspReceiverCopiedNonDistrCarriers(copiedCarriers);

	}


	/**
	 * Defines the priority of this listener. Higher values indicate higher priority.
	 * As we need to get the just changed lsp plans after the IterationStartsListener,
	 * so make sure this listener is called after that one.
	 */
	@Override
	public double priority() {
		return 1000.0;
	}

	private Carrier deepCopyCarrier(Carrier originalCarrier) {
		Carrier copiedCarrier = CarriersUtils.createCarrier(originalCarrier.getId());

		// Copy carrier capabilities (vehicles, etc.)
		// Copy carrier capabilities (vehicles, etc.) – plans/shipments are rebuilt per subset, so skip them to stay fast.
		copiedCarrier.setCarrierCapabilities(originalCarrier.getCarrierCapabilities());

		// Copy shipments
		for (CarrierShipment shipment : originalCarrier.getShipments().values()) {
			CarriersUtils.addShipment(copiedCarrier, shipment);
		}

		// Copy services
		for (CarrierService service : originalCarrier.getServices().values()) {
			CarriersUtils.addService(copiedCarrier, service);
		}

		// Copy plans
		for (CarrierPlan plan : originalCarrier.getPlans()) {
			// Since the carrier plan will not have scores during the PSim, we can set it as 0.0
			CarrierPlan copiedPlan = copyNoScoreCarrierPlan(plan);
			copiedCarrier.addPlan(copiedPlan);
		}

		// Set selected plan if exists
		if (originalCarrier.getSelectedPlan() == null) {
			// Find the corresponding copied plan and set it as selected
			List<CarrierPlan> copiedPlans = copiedCarrier.getPlans();
			if (!copiedPlans.isEmpty()) {
				copiedCarrier.setSelectedPlan(copiedPlans.getFirst()); // Simplified - use first plan
			}
		}
		return copiedCarrier;
	}
}
