package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_CARRIER;
import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER;

public class FreightCollaborationEngine {

	private final Scenario scenario;

	private final Config config;

	private final FreightCollaborators freightCollaborators;

	// Set it as package-private to be accessible from scorers and allocation models
	final CollaborationDataStore collaborationDataStore;

	private final List<MutableFreightCoalition> existingCoalitions;

	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborationEngine.class);

	private CarrierScoringFunctionFactory carrierScoringFunctionFactory;

	// FIXME: I do not see any reason to have this field if it is not used
//	private final EventsManager existingEventsManager;

	private final TravelTime travelTime;

	public FreightCollaborationEngine(Config config, Scenario scenario, FreightCollaborators freightCollaborators,
			CollaborationDataStore collaborationDataStore, List<MutableFreightCoalition> existingCoalitions, TravelTime travelTime,
									  CarrierScoringFunctionFactory carrierScoringFunctionFactory) {
		this.config = config;
		this.scenario = scenario;
		this.freightCollaborators = freightCollaborators;
		this.collaborationDataStore = collaborationDataStore;
		this.existingCoalitions = existingCoalitions;
		this.travelTime = travelTime;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
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
		AllocationModel allocationModel = AllocationUtils.createAllocationModel(fcg.ALLOCATION_MODEL, collaborationDataStore);
		if (fcg.ALLOCATION_MODEL == AllocationModels.PROPORTIONAL) {
			// Do not need to run the freight psim with such allocation model
			// TODO: only need to specify the distributor and assignee for the model input
		} else {
			// Need to run the freight psim to evaluate the new plans and calculate the contributions

			FreightPseudoSimulator freightPsim = new FreightPseudoSimulator(collaborationDataStore, scenario.getNetwork(),
				freightCollaborators, travelTime, carrierScoringFunctionFactory); // initialize a new psim instance

			// A for-loop to iterate through all existing coalitions
			for (MutableFreightCoalition coalition : existingCoalitions) {
				// Extract the valid collaborators (player and distributor) based on the collaboration type
				Map<Id<?>, FreightCollaborator<?>> validPlayer = extractValidPlayer(coalition);
				Map<Id<?>, FreightCollaborator<?>> validDistributor = extractValidDistributor(coalition);
				Map<Set<Id<?>>, Double> subCoalitionsScoreMap = freightPsim.runAllSubCoalitions(validDistributor, validPlayer);
				// add the sub-coalitions scores to the data store
				collaborationDataStore.addSimulatedCoalitionScores(coalition, subCoalitionsScoreMap);
			}
			// TODO: the allocation value type should be specified in the config later
			if (existingCoalitions.isEmpty()) {
				LOGGER.info("No valid coalitions found, skipping the allocation process.");
				return;
			}
			allocationModel.allocate(AllocationValueTypes.COST_SAVINGS);
		}

		// Something to do with triggering the MATSim scoring module
		/**
		 * Here, we may need to add a custom scoring function to each collaborator agent, by implementing the @BasicScoring,
		 * since it will definitely be called at the end the MATSim scoring phase.
		 *
		 * It seems we cannot directly inject the scoring function here, so that we have two options:
		 * 1. Create a custom ScoringFunctionFactory that creates a SumScoringFunction with our custom scoring function included at the start of the simulation
		 * 2. Modify the scores after the scoring phase using the iteration ends listener.
		 * Now, I temporarily choose the second option for simplicity.
		 */

	}

	private Map<Id<?>, FreightCollaborator<?>> extractValidPlayer(MutableFreightCoalition existingCoalition) {
		CollaborationType collaborationType = existingCoalition.getCollaborationType();
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.RECEIVER);
			case CARRIER_CARRIER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};

	}

	private Map<Id<?>, FreightCollaborator<?>> extractValidDistributor(MutableFreightCoalition existingCoalition) {
		CollaborationType collaborationType = existingCoalition.getCollaborationType();
		return switch (collaborationType) {
			case CARRIER_RECEIVER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			case CARRIER_CARRIER -> existingCoalition.getCollaboratorsMapByRole(CollaboratorRole.CARRIER);
			default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
		};
	}

	private void injectScoringFunctionForValueAllocation(){

	}


}
