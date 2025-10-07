package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.GrandFreightCoalition;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Functions that need to be executed at the start of each iteration.
 * The functions are listed below:
 * 	1. Form coalitions
 */
public class FormFreightCoalitionListener implements IterationStartsListener {

	private static final Logger LOGGER = LogManager.getLogger(FormFreightCoalitionListener.class);

	@Inject
	private Scenario scenario;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private FreightCoalitionManager freightCoalitionManager;

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		GrandFreightCoalition grandCoalition = formFreightGrandCoalition();
		freightCoalitionManager.setGrandFreightCoalition(grandCoalition);
		informGrandCoalitionInfo(grandCoalition);
	}

	private GrandFreightCoalition formFreightGrandCoalition(){
		Set<CollaboratorRole> allRoles = freightCoalitionManager.getAllCollaboratorRoles();
		GrandFreightCoalition grandCoalition;
		Set<FreightCollaborator<?>> grandCollaborators = new HashSet<>();
		for (CollaboratorRole role : allRoles) {
			// Get freight collaborators with the role of 'role'
			switch (role){
				case CollaboratorRole.CARRIER -> {
					Map<Id<Carrier>, FreightCollaborator<Carrier>> carrierCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.CARRIER);
					for (FreightCollaborator<Carrier> collaborator : carrierCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				case CollaboratorRole.LSP -> {
					Map<Id<LSP>, FreightCollaborator<LSP>> lspCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.LSP);
					for (FreightCollaborator<LSP> collaborator : lspCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				case CollaboratorRole.RECEIVER -> {
					Map<Id<Receiver>, FreightCollaborator<Receiver>> receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
					for (FreightCollaborator<Receiver> collaborator : receiverCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				default -> throw new IllegalStateException("Unexpected value: " + role);
			}
		}
		grandCoalition = new GrandFreightCoalition(grandCollaborators);
		return grandCoalition;
	}

	private void informGrandCoalitionInfo(GrandFreightCoalition grandCoalition){
		LOGGER.info("Grand Coalition formed with {} collaborators.", grandCoalition.size());
		for (CollaboratorRole role : grandCoalition.getRoles()) {
			long count = grandCoalition.getCollaboratorsSet().stream()
					.filter(collaborator -> collaborator.getRole() == role)
					.count();
			LOGGER.info(" - Role: {}, Number of Collaborators: {}", role, count);
		}
	}



}
