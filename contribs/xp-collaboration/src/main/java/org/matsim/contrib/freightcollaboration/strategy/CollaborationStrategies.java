package org.matsim.contrib.freightcollaboration.strategy;

import org.matsim.contrib.freightcollaboration.CollaborationType;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;

/**
 * List of collaboration strategies.
 * Note: Some strategies have been implemented in specific packages, e.g., the "TimeWindowMutator" for Carrier-Receiver collaboration is in
 * `freightreceiver` package, so we will only use this enum to list strategy names for configuration purposes.
 */
public enum CollaborationStrategies {

	RECEIVER_SERVICE_TIME_MUTATION(CollaborationTypes.CARRIER_RECEIVER, "Mutate service times of the receiver's orders," +
		" using the @ServiceTimeMutator strategy implemented in the `freightreceiver` package."),

	RECEIVER_TIME_WINDOW_MUTATION(CollaborationTypes.CARRIER_RECEIVER, "Mutate time windows of the receiver's orders," +
		" using the @TimeWindowMutator strategy implemented in the `freightreceiver` package."),

	COLLABORATION_STATUS_MUTATION(CollaborationTypes.CARRIER_RECEIVER, "Mutate collaboration status"),
	;


	private final CollaborationType type;
	private final String description;

	CollaborationStrategies(CollaborationType type, String description) {
		this.type = type;
		this.description = description;
	}

	public CollaborationType getCollaborationType() {
		return type;
	}

	public String getDescription() {
		return description;
	}
}
