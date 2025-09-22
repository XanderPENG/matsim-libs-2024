package org.matsim.contrib.freightcollaboration.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

public class LinkFreightAgentToFreightCollaborator {

	// A logger
	private static final Logger LOGGER = LogManager.getLogger(LinkFreightAgentToFreightCollaborator.class);

	@SuppressWarnings("unchecked")
	public static <T extends HasPlansAndId<?, ?>> FreightCollaborator<T> map(T freightAgent, boolean collaborationStatus){
		return switch (freightAgent) {
			case Receiver receiver ->
				// FIXME: The collaborationStatus param will not function here, maybe check if the receiver's attribute is consistent with it?
				(FreightCollaborator<T>) mapReceiverToFreightCollaborator(receiver);
			case Carrier carrier ->
				(FreightCollaborator<T>) mapCarrierToFreightCollaborator(carrier, collaborationStatus);
			case LSP lsp ->
				(FreightCollaborator<T>) mapLSPToFreightCollaborator(lsp, collaborationStatus);
			default -> throw new IllegalArgumentException("Unsupported freight agent type: " + freightAgent.getClass().getName());
		};
	}

	/**
	 * Maps a Receiver to a FreightCollaborator, setting the collaboration status based on the Receiver's attributes.
	 */
	private static FreightCollaborator<Receiver> mapReceiverToFreightCollaborator(Receiver receiver){
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

	private static FreightCollaborator<Carrier> mapCarrierToFreightCollaborator(Carrier carrier, boolean collaborationStatus){
		var carrierCollaborator = FreightCollaboratorFactory.createCollaborator(carrier);
		if (collaborationStatus) {
			carrierCollaborator.enableCollaboration();
		} else {
			carrierCollaborator.disableCollaboration();
		}
		return carrierCollaborator;
	}

	private static FreightCollaborator<LSP> mapLSPToFreightCollaborator(LSP lsp, boolean collaborationStatus){
		var lspCollaborator = FreightCollaboratorFactory.createCollaborator(lsp);
		if (collaborationStatus) {
			lspCollaborator.enableCollaboration();
		} else {
			lspCollaborator.disableCollaboration();
		}
		return lspCollaborator;
	}

}
