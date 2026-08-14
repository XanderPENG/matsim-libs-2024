package org.matsim.contrib.freightcollaboration.learning;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.receiver.Receiver;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Lightweight metrics of one Carrier-Receiver state that actually completed the whole iteration. */
public record ExecutedJointObservation(
	int executionIteration,
	int capturedAtIteration,
	Id<Carrier> carrierId,
	int factorIndex,
	int visit,
	double carrierScore,
	Map<Id<Receiver>, Double> receiverScores,
	double receiverAggregateScore,
	Set<Id<Receiver>> collaboratingReceivers,
	double totalSurplus,
	double signedTransfer,
	String executedReceiverProfileHash,
	String carrierRouteProfileHash,
	boolean receiverParticipationFeasible,
	boolean participationFeasible
) {
	public ExecutedJointObservation {
		Objects.requireNonNull(carrierId, "carrierId");
		receiverScores = Map.copyOf(Objects.requireNonNull(receiverScores, "receiverScores"));
		collaboratingReceivers = Set.copyOf(Objects.requireNonNull(collaboratingReceivers,
			"collaboratingReceivers"));
		executedReceiverProfileHash = Objects.requireNonNull(executedReceiverProfileHash,
			"executedReceiverProfileHash");
		carrierRouteProfileHash = Objects.requireNonNull(carrierRouteProfileHash,
			"carrierRouteProfileHash");
	}
}
