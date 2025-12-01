package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.*;

public class CoalitionUtils {


	/**
	 * Get all collaborated receivers from the freight collaborators, based on the collaboration status.
	 */
	public static Map<Id<Receiver>, FreightCollaborator<Receiver>> getCollaboratedReceivers(FreightCollaborators freightCollaborators
																			  ) {
		// Get all receiver collaborators
		Map<Id<Receiver>, FreightCollaborator<Receiver>> allReceiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
		// Get the collaborated receiver map
		Map<Id<Receiver>, FreightCollaborator<Receiver>> collaboratedReceiverMap = new HashMap<>();
		for (FreightCollaborator<Receiver> receiverCollaborator : allReceiverCollaborators.values()) {
			if (receiverCollaborator.getCollaborationStatus()){
				collaboratedReceiverMap.put(receiverCollaborator.getDelegate().getId(), receiverCollaborator);
			}
		}
		return collaboratedReceiverMap;
	}


	public static void markCollaboratedReceiver(FreightCollaborators freightCollaborators,
												CollaborationDataStore collaborationDataStore) {
		// Get all receiver collaborators
		Map<Id<Receiver>, FreightCollaborator<Receiver>> allReceiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
		// Get the original plans from the data store
		var receiverOriginPlans = collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER);

		// For each receiver collaborator, compare its current plan with the original plan to determine collaboration status
		for (FreightCollaborator<Receiver> receiverCollaborator : allReceiverCollaborators.values()) {
			Receiver receiver = receiverCollaborator.getDelegate();
			// Get the original plan
			ReceiverPlan originalPlan = (ReceiverPlan) receiverOriginPlans.get(receiver.getId());
			if (originalPlan == null) {
				throw new RuntimeException("Original plan for receiver " + receiver.getId() + " not found in the data store.");
			}
			// Get the current plan
			ReceiverPlan currentPlan = receiverCollaborator.getTypedSelectedPlan();

			Set<Id<Carrier>> collaboratingCarriers = new HashSet<>();

			for (ReceiverOrder order: currentPlan.getReceiverOrders()){

				// Get the TWs and service durations of this current plan
				List<TimeWindow> thisTWs = currentPlan.getTimeWindows();
				List<Double> thisServiceDurations = new ArrayList<>();
				order.getReceiverProductOrders().forEach(productOrder -> {
					thisServiceDurations.add(productOrder.getServiceDuration());
				});

				// Get the TWs and service durations of the original plan
				List<TimeWindow> originalTWs = originalPlan.getTimeWindows();
				List<Double> originalServiceDurations = new ArrayList<>();
				Objects.requireNonNull(originalPlan.getReceiverOrders().stream()
						.filter(o -> o.getCarrierId() == order.getCarrierId())
						.findFirst()
						.orElse(null))
					.getReceiverProductOrders()
					.forEach(productOrder -> {
						originalServiceDurations.add(productOrder.getServiceDuration());
					});
				// case 1: if any of the TW has been extended
				boolean isExtended = false;
				for (int i = 0; i < thisTWs.size(); i++) {
					TimeWindow thisTW = thisTWs.get(i);
					TimeWindow originalTW = originalTWs.get(i);
					if (thisTW.getStart() < originalTW.getStart() ||
						thisTW.getEnd() > originalTW.getEnd()) {
						// mark as collaborated
						isExtended = true;
						collaboratingCarriers.add(order.getCarrierId());
					}
				}

				if (isExtended) {
					receiverCollaborator.enableCollaboration();
					Set<Id<?>> collaborationPartnerIds = new HashSet<>(collaboratingCarriers);
					receiverCollaborator.setCollaborationPartners(collaborationPartnerIds);
				} else {
					receiverCollaborator.disableCollaboration();
				}

				// case 2: if the service duration has been contracted
				boolean isContracted = false;
				for (int i = 0; i < thisServiceDurations.size(); i++) {
					double thisServiceDuration = thisServiceDurations.get(i);
					double originalServiceDuration = originalServiceDurations.get(i);
					if (thisServiceDuration < originalServiceDuration) {
						isContracted = true;
						collaboratingCarriers.add(order.getCarrierId());
					}
				}

				if (isContracted) {
					receiverCollaborator.enableCollaboration();
					Set<Id<?>> collaborationPartnerIds = new HashSet<>(collaboratingCarriers);
					receiverCollaborator.setCollaborationPartners(collaborationPartnerIds);
				} else {
					if (!isExtended) {
						receiverCollaborator.disableCollaboration();
					}
				}
			}


		}

	}


}
