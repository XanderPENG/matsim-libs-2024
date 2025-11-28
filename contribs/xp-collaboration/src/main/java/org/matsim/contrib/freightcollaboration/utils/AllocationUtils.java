package org.matsim.contrib.freightcollaboration.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.allocation.*;
import org.matsim.freight.carriers.*;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.logistics.LogisticChain;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;

import java.util.*;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.*;

public class AllocationUtils {

	private static Logger LOGGER = LogManager.getLogger(AllocationUtils.class);

	// TODO: the CollaborationDataStore should be injected via Guice, fix it later
	public static AllocationModel createAllocationModel(AllocationModels allocationModelType,
														  CollaborationDataStore collaborationDataStore,
														  FreightPseudoSimulator freightPseudoSimulator,
														  List<MutableFreightCoalition> coalitions,
														  double allocationFactor) {
		switch (allocationModelType){
			case AllocationModels.PROPORTIONAL:
				return new AllocationModelProportional(collaborationDataStore, allocationFactor);
			case AllocationModels.SHAPLEY:
				return new AllocationModelShapleyValue(collaborationDataStore, allocationFactor);
			case AllocationModels.MARGINAL:
				return new AllocationModelMarginalContribution(collaborationDataStore, allocationFactor);
			case AllocationModels.APPROX_SHAPLEY:
				return new AllocationModelApproxShapleyValue(collaborationDataStore, freightPseudoSimulator, coalitions, allocationFactor);
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
	 * Extract valid players from a coalition based on collaboration type.
	 */
	public static Map<Id<?>, FreightCollaborator<?>> extractValidPlayers(MutableFreightCoalition coalition) {
		CollaborationType collaborationType = coalition.getCollaborationType();
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.RECEIVER);
			case LSP_RECEIVER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.RECEIVER);
			case CARRIER_CARRIER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};
	}

	/**
	 * Extract valid distributors from a coalition based on collaboration type.
	 */
	public static Map<Id<?>, FreightCollaborator<?>> extractValidDistributors(MutableFreightCoalition coalition) {
		CollaborationType collaborationType = coalition.getCollaborationType();
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			case LSP_RECEIVER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.LSP);
			case CARRIER_CARRIER -> coalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};
	}

	/**
	 * A function that could deep copy a map of collaborators.
	 * Creates new instances of the underlying freight agents (Carrier, LSP, Receiver) to avoid
	 * modifying the original collaborators during pseudo simulation.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends FreightCollaborator<?>> Map<Id<?>, T> deepCopyCollaboratorsMap(Map<Id<?>, T> originalMap) {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug("Deep copy collaborators map");
		}
		// Note: this runs in hot loops during sampling; keep the copy lightweight.

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

					// Copy carrier capabilities (vehicles, etc.) – plans/shipments are rebuilt per subset, so skip them to stay fast.
					copiedCarrier.setCarrierCapabilities(originalCarrier.getCarrierCapabilities());

					// Copy attributes manually
					for (String key : originalCarrier.getAttributes().getAsMap().keySet()) {
						Object value = originalCarrier.getAttributes().getAttribute(key);
						copiedCarrier.getAttributes().putAttribute(key, value);
					}

					copiedDelegate = copiedCarrier;
				}
			}
			case LSP -> {
				if (originalDelegate instanceof LSP originalLsp) {
					LSPPlan copiedPlan = copyLspPlan(originalLsp.getSelectedPlan());
					// create minimal scheduler; use a forward scheduler as default fallback
					var scheduler = org.matsim.freight.logistics.LSPUtils.createForwardLogisticChainScheduler();
					var builder = org.matsim.freight.logistics.LSPUtils.LSPBuilder.getInstance(originalLsp.getId())
						.setLogisticChainScheduler(scheduler)
						.setInitialPlan(copiedPlan);
					LSP copiedLsp = builder.build();
					copiedPlan.setLSP(copiedLsp);
					// copy basic attributes
					for (String key : originalLsp.getAttributes().getAsMap().keySet()) {
						Object value = originalLsp.getAttributes().getAttribute(key);
						copiedLsp.getAttributes().putAttribute(key, value);
					}
					// copy plan score to keep comparable baseline
					if (copiedPlan.getScore() == null && originalLsp.getSelectedPlan().getScore() != null) {
						copiedPlan.setScore(originalLsp.getSelectedPlan().getScore());
					}
					copiedDelegate = copiedLsp;
				}
			}
			case RECEIVER -> {
				if (originalDelegate instanceof Receiver originalReceiver) {
					// Use ReceiverUtils factory method
					Receiver copiedReceiver = ReceiverUtils.newInstance(originalReceiver.getId());

					// Copy plans
					for (ReceiverPlan plan : originalReceiver.getPlans()) {
						ReceiverPlan newPlan = plan.createCopy();
						newPlan.setScore(plan.getScore());
						if (plan.isSelected()){
							copiedReceiver.setSelectedPlan(newPlan);
						} else {
							copiedReceiver.addPlan(newPlan);
						}
					}

					// Set selected plan
					if (copiedReceiver.getSelectedPlan() == null) {
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

	public static CarrierPlan copyNoScoreCarrierPlan(CarrierPlan plan2copy) {
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


	public static LSPPlan copyLspPlan(LSPPlan plan2copy) {
		List<LogisticChain> newPlanChains = new ArrayList<>();
		for (LogisticChain initialPlanChain : plan2copy.getLogisticChains()) {
			LogisticChain newPlanChain =
				LSPUtils.LogisticChainBuilder.newInstance(initialPlanChain.getId()).build();
			newPlanChain.getLogisticChainElements().addAll(initialPlanChain.getLogisticChainElements());
			newPlanChain.getLspShipmentIds().addAll(initialPlanChain.getLspShipmentIds());
			newPlanChains.add(newPlanChain);
		}

		LSPPlan copiedPlan = LSPUtils.createLSPPlan();
		copiedPlan.setInitialShipmentAssigner(plan2copy.getInitialShipmentAssigner());
//		copiedPlan.setLSP(plan2copy.getLSP());
		copiedPlan.setScore(plan2copy.getScore());
		copiedPlan.setType(plan2copy.getType());
		copiedPlan.getLogisticChains().addAll(newPlanChains);
		return copiedPlan;
	}

}
