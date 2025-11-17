package org.matsim.contrib.freightcollaboration.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorImpl;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModel;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.allocation.ShapleyValueAllocationModel;
import org.matsim.freight.carriers.*;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AllocationUtils {

	private static Logger LOGGER = LogManager.getLogger(AllocationUtils.class);

	// TODO: the CollaborationDataStore should be injected via Guice, fix it later
	public static AllocationModel createAllocationModel(AllocationModels allocationModelType, CollaborationDataStore collaborationDataStore) {
		// Placeholder for actual implementation
		switch (allocationModelType){
			case AllocationModels.PROPORTIONAL:
				// return new ProportionalAllocation();
			case AllocationModels.SHAPLEY:
				return new ShapleyValueAllocationModel(collaborationDataStore);
			case AllocationModels.MARGINAL:
				// return new MarginalContributionAllocationModel();
			default:
				throw new IllegalArgumentException("Unknown allocation model type: " + allocationModelType);
		}
	}

	public static Map<Set<Id<?>>, Double> generateSubsets(Map<Id<?>, ?> coalitionMembers) {
		// The map would be like {(id1, id2, ...): score}
		List<Id<?>> ids = new ArrayList<>(coalitionMembers.keySet());
		Map<Set<Id<?>>, Double> result = new HashMap<>();

		int n = ids.size();
		int total = 1 << n; // 2^n subsets

		for (int mask = 0; mask < total; mask++) {
			Set<Id<?>> subset = new HashSet<>();
			for (int i = 0; i < n; i++) {
				if ((mask & (1 << i)) != 0) {
					subset.add(ids.get(i));
				}
			}
			result.put(subset, 0.0); // default value at the initialization
		}

		return result;
	}

	/**
	 * Return the set of Ids that are in allMembers but not in collaboratingMembers for this sub-coalition PSim.
	 */
	public static Set<Id<?>> identifyNonCollaboratingMembers(Set<Id<?>> allMembers, Set<Id<?>> collaboratingMembers){
		Set<Id<?>> nonCollaboratingMembers = new HashSet<>(allMembers);
		nonCollaboratingMembers.removeAll(collaboratingMembers);
		return nonCollaboratingMembers;
	}

	/**
	 * A function that could deep copy a map of collaborators.
	 * Creates new instances of the underlying freight agents (Carrier, LSP, Receiver) to avoid
	 * modifying the original collaborators during pseudo simulation.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends FreightCollaborator<?>> Map<Id<?>, T> deepCopyCollaboratorsMap(Map<Id<?>, T> originalMap) {
		LOGGER.info("Deep copy collaborators map");

		Map<Id<?>, T> copiedMap = new HashMap<>();

		for (Map.Entry<Id<?>, T> entry : originalMap.entrySet()) {
			Id<?> collaboratorId = entry.getKey();
			T originalCollaborator = entry.getValue();

			// Create a deep copy of the collaborator by copying its delegate and preserving its role
			T copiedCollaborator = (T) deepCopyCollaborator(originalCollaborator);
			copiedMap.put(collaboratorId, copiedCollaborator);
		}

		return copiedMap;
	}

	/**
	 * Deep copies a single FreightCollaborator by creating a new instance of its delegate agent.
	 * @FIXME: Curretnly, the revceiverTriggerCarriersReplanning will reset the carrier plan (without scores), which will result in erroer when using CarrierUtils.copyPlan
	 */
	@SuppressWarnings("unchecked")
	private static FreightCollaborator<?> deepCopyCollaborator(FreightCollaborator<?> originalCollaborator) {
		var originalDelegate = originalCollaborator.getDelegate();
		CollaboratorRole role = originalCollaborator.getRole();

		// Create a copy of the delegate based on its type
		HasPlansAndId<?, ?> copiedDelegate = null;

		switch (role) {
			case CARRIER -> {
				if (originalDelegate instanceof Carrier originalCarrier) {
					Carrier copiedCarrier = CarriersUtils.createCarrier(originalCarrier.getId());

					// Copy carrier capabilities (vehicles, etc.)
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
						CarrierPlan copiedPlan = copyNoScorePlan(plan);
						copiedCarrier.addPlan(copiedPlan);
					}

					// Set selected plan if exists
					if (originalCarrier.getSelectedPlan() != null) {
						// Find the corresponding copied plan and set it as selected
						List<CarrierPlan> copiedPlans = copiedCarrier.getPlans();
						if (!copiedPlans.isEmpty()) {
							copiedCarrier.setSelectedPlan(copiedPlans.get(0)); // Simplified - use first plan
						}
					}

					// Copy attributes manually
					for (String key : originalCarrier.getAttributes().getAsMap().keySet()) {
						Object value = originalCarrier.getAttributes().getAttribute(key);
						copiedCarrier.getAttributes().putAttribute(key, value);
					}

					copiedDelegate = copiedCarrier;
				}
			}
			case LSP -> {
				if (originalDelegate instanceof LSP) {
					// For LSP, we cannot use LSPImpl constructor directly as it's not public
					// This is a limitation - in practice you'd need a proper LSP factory method
					// For now, throw an exception indicating this case needs special handling
					throw new UnsupportedOperationException("LSP deep copying not yet fully implemented - requires access to LSP factory methods");
				}
			}
			case RECEIVER -> {
				if (originalDelegate instanceof Receiver originalReceiver) {
					// Use ReceiverUtils factory method
					Receiver copiedReceiver = ReceiverUtils.newInstance(originalReceiver.getId());

					// Copy plans
					for (ReceiverPlan plan : originalReceiver.getPlans()) {
						// For now, add reference - in full implementation, deep copy the plan
						copiedReceiver.addPlan(plan);
					}

					// Set selected plan
					if (originalReceiver.getSelectedPlan() != null && !copiedReceiver.getPlans().isEmpty()) {
						copiedReceiver.setSelectedPlan(copiedReceiver.getPlans().getFirst());
					}

					// Copy attributes manually
					for (String key : originalReceiver.getAttributes().getAsMap().keySet()) {
						Object value = originalReceiver.getAttributes().getAttribute(key);
						copiedReceiver.getAttributes().putAttribute(key, value);
					}

					copiedDelegate = copiedReceiver;
				}
			}
		}

		if (copiedDelegate == null) {
			throw new IllegalArgumentException("Unknown delegate type for role: " + role);
		}

		// Create new FreightCollaborator with the copied delegate
		// Cast is safe because copiedDelegate is guaranteed to implement HasPlansAndId by our validation above
		var copiedCollaborator =
			FreightCollaboratorFactory.createCollaborator(copiedDelegate);

		// Copy collaboration status
		if (originalCollaborator.getCollaborationStatus()) {
			copiedCollaborator.enableCollaboration();
		} else {
			copiedCollaborator.disableCollaboration();
		}

		return copiedCollaborator;
	}

	static CarrierPlan copyNoScorePlan(CarrierPlan plan2copy) {
		List<ScheduledTour> tours = new ArrayList<>();
		for (ScheduledTour sTour : plan2copy.getScheduledTours()) {
			double depTime = sTour.getDeparture();
			CarrierVehicle vehicle = sTour.getVehicle();
			Tour tour = sTour.getTour().duplicate();
			tours.add(ScheduledTour.newInstance(tour, vehicle, depTime));
		}
		CarrierPlan copiedPlan = new CarrierPlan(plan2copy.getCarrier(), tours);
		double initialScoreOfCopiedPlan;
		if (plan2copy.getScore() != null) {
			initialScoreOfCopiedPlan = plan2copy.getScore();
		} else {
			initialScoreOfCopiedPlan = 0.0;
		}
		copiedPlan.setScore(initialScoreOfCopiedPlan);
		return copiedPlan;

	}

}
