package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;

import java.util.Map;
import java.util.Set;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_CARRIER;
import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER;

public class FreightCollaborationEngine {

	@Inject
	private Scenario scenario;

	@Inject
	private Config config;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private CollaborationType collaborationType;

	// Set it as package-private to be accessible from scorers and allocation models
	@Inject
	CollaborationDataStore collaborationDataStore;

	// FIXME: it is not a single mutable coalition, instead, it should be a collection of coalitions
	private final MutableFreightCoalition existingCoalition;

	private final EventsManager existingEventsManager;

	public FreightCollaborationEngine(MutableFreightCoalition existingCoalition, EventsManager existingEventsManager) {
		this.existingCoalition = existingCoalition;
		this.existingEventsManager = existingEventsManager;
	}


	public void runCollaboration() {
		// Run the freight collaboration engine logic here
		// This may include:
		// - Forming coalitions based on the collaboration type
		// - Allocating tasks or resources among collaborators
		// - Updating plans and contributions in the CollaborationDataStore
		// - Interacting with the existing coalition and events manager as needed

		// Example pseudocode:
		// if (collaborationType == CollaborationType.SOME_TYPE) {
		//     // Perform specific logic for this collaboration type
		// }
		FreightCollaborationConfigGroup fcg = (FreightCollaborationConfigGroup) config.getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		AllocationModel allocationModel = AllocationUtils.createAllocationModel(fcg.ALLOCATION_MODEL);
		if (fcg.ALLOCATION_MODEL == AllocationModels.PROPORTIONAL) {
			// Do not need to run the freight psim with such allocation model
			// TODO: only need to specify the distributor and assignee for the model input
		} else {
			// Need to run the freight psim to evaluate the new plans and calculate the contributions
			// Extract the valid collaborators (player and distributor) based on the collaboration type
			Map<Id<?>, FreightCollaborator<?>> validPlayer = extractValidPlayer();
			Map<Id<?>, FreightCollaborator<?>> validDistributor = extractValidDistributor();

			// Initialize and run the freight pseudo simulator
			// TODO: pass the valid Player and Distributor to the pSim?
			FreightPseudoSimulator freightPsim = new FreightPseudoSimulator();
			Map<Set<Id<?>>, Double> subCoalitionsScoreMap = freightPsim.runAllSubCoalitions(validDistributor, validPlayer);
			allocationModel.allocate();
		}

		// Something to do with triggering the MATSim scoring module

	}

	private Map<Id<?>, FreightCollaborator<?>> extractValidPlayer() {
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.RECEIVER);
			case CARRIER_CARRIER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};

	}

	private Map<Id<?>, FreightCollaborator<?>> extractValidDistributor() {
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			case CARRIER_CARRIER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};
	}



}
