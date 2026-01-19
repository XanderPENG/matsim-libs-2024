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

    public record ReceiverDelta(boolean twExtended, boolean serviceContracted, Set<Id<Carrier>> carriers) {
        public boolean hasChange() { return twExtended || serviceContracted; }
    }


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
        Map<Id<Receiver>, ReceiverPlan> originPlans = collaborationDataStore.getOriginalPlans()
            .get(CollaboratorRole.RECEIVER)
            .entrySet().stream()
            .collect(HashMap::new, (m, e) -> m.put((Id<Receiver>) e.getKey(), (ReceiverPlan) e.getValue()), Map::putAll);

        Map<Id<Receiver>, FreightCollaborator<Receiver>> allReceivers = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);

        for (FreightCollaborator<Receiver> receiverCollaborator : allReceivers.values()) {
            Receiver receiver = receiverCollaborator.getDelegate();
            ReceiverPlan originalPlan = originPlans.get(receiver.getId());
            if (originalPlan == null) {
                throw new RuntimeException("Original plan for receiver " + receiver.getId() + " not found in the data store.");
            }

            ReceiverPlan currentPlan = receiverCollaborator.getTypedSelectedPlan();
            ReceiverDelta delta = computeReceiverDelta(originalPlan, currentPlan);

            if (delta.hasChange()) {
                receiverCollaborator.enableCollaboration();
                receiverCollaborator.setCollaborationPartners(new HashSet<>(delta.carriers()));
            } else {
                receiverCollaborator.disableCollaboration();
                receiverCollaborator.setCollaborationPartners(Set.of());
            }
        }

    }

    /**
     * Compute whether a receiver relaxed TW or reduced service duration vs. its original plan.
     * Returns carriers affected for quick coalition linking.
     */
    public static ReceiverDelta computeReceiverDelta(ReceiverPlan originalPlan, ReceiverPlan currentPlan) {
        boolean twExtended = false;
        boolean serviceContracted = false;
        Set<Id<Carrier>> collaboratingCarriers = new HashSet<>();

        // Build a lookup of original orders by carrier for fast access
        Map<Id<Carrier>, ReceiverOrder> originalOrdersByCarrier = new HashMap<>();
        for (ReceiverOrder order : originalPlan.getReceiverOrders()) {
            originalOrdersByCarrier.put(order.getCarrierId(), order);
        }

        List<TimeWindow> originalTWs = originalPlan.getTimeWindows();

        for (ReceiverOrder order : currentPlan.getReceiverOrders()) {
            ReceiverOrder originalOrder = originalOrdersByCarrier.get(order.getCarrierId());
            if (originalOrder == null) {
                continue; // no baseline to compare; treat as unchanged
            }

            // TWs assumed aligned by index
            List<TimeWindow> currentTWs = currentPlan.getTimeWindows();
            int twSize = Math.min(currentTWs.size(), originalTWs.size());
            for (int i = 0; i < twSize; i++) {
                TimeWindow tw = currentTWs.get(i);
                TimeWindow base = originalTWs.get(i);
                if (tw.getStart() < base.getStart() || tw.getEnd() > base.getEnd()) {
                    twExtended = true;
                    collaboratingCarriers.add(order.getCarrierId());
                    break; // one extension is enough
                }
            }

            var currentDurations = order.getReceiverProductOrders().stream().toList();
            var baseDurations = originalOrder.getReceiverProductOrders().stream().toList();
            int durSize = Math.min(currentDurations.size(), baseDurations.size());
            for (int i = 0; i < durSize; i++) {
                double cur = currentDurations.get(i).getServiceDuration();
                double base = baseDurations.get(i).getServiceDuration();
                if (cur < base) {
                    serviceContracted = true;
                    collaboratingCarriers.add(order.getCarrierId());
                    break;
                }
            }
        }

        return new ReceiverDelta(twExtended, serviceContracted, collaboratingCarriers);
    }


}
