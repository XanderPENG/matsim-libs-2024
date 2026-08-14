package org.matsim.contrib.freightcollaboration.learning;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deep, recoverable copy of one jointly executed iteration state. */
public record ExecutedJointSnapshot(
	ExecutedJointObservation observation,
	double allocationFactor,
	List<ScheduledTour> carrierTours,
	Map<Id<Receiver>, ReceiverPlan> selectedReceiverPlans
) {
	public ExecutedJointSnapshot {
		observation = Objects.requireNonNull(observation, "observation");
		if (!Double.isFinite(allocationFactor)) {
			throw new IllegalArgumentException("allocationFactor must be finite");
		}
		carrierTours = List.copyOf(MutableAfPlanUtils.copyTours(
			Objects.requireNonNull(carrierTours, "carrierTours")));
		Map<Id<Receiver>, ReceiverPlan> copies = new LinkedHashMap<>();
		Objects.requireNonNull(selectedReceiverPlans, "selectedReceiverPlans").forEach((id, plan) -> {
			ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(plan, true);
			MutableAfPlanUtils.clearPendingEvaluation(copy);
			copies.put(id, copy);
		});
		selectedReceiverPlans = Map.copyOf(copies);
	}
}
