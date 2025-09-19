package org.matsim.contrib.freightcollaboration.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

public class LinkFreightAgentToFreightCollaborator {

	// A logger
	private static final Logger LOGGER = LogManager.getLogger(LinkFreightAgentToFreightCollaborator.class);

	/**
	 * Maps a Receiver to a FreightCollaborator, setting the collaboration status based on the Receiver's attributes.
	 */
	public static FreightCollaborator<Receiver> mapReceiverToFreightCollaborator(Receiver receiver){
		FreightCollaborator<Receiver> freightCollaborator = FreightCollaboratorFactory.createCollaborator(receiver);
		if (receiver.getAttributes().getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS) == null) {
			// If the attribute is not set, we assume the receiver is open to collaboration by default, but a warning is logged.
			LOGGER.warn("No collaboration status attribute found in Receiver {}. Assuming open to collaboration by default.", receiver.getId());
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			freightCollaborator.enableCollaboration();
		} else if (receiver.getAttributes().getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS) != null) {
			if ((Boolean) receiver.getAttributes().getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS)) {
				freightCollaborator.enableCollaboration();
			} else {
				freightCollaborator.disableCollaboration();
			}
		}
		return freightCollaborator;
	}
}
