package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarrierReceiverCollaborationLifecycleIT {

	@Test
	void coalitionEvaluationFlowsIntoRoleAwareBalancedAllocation() {
		Config config = ConfigUtils.createConfig();
		FreightCollaborationConfigGroup freightConfig = new FreightCollaborationConfigGroup();
		freightConfig.setAllocationModelString(AllocationModels.SHAPLEY.name());
		freightConfig.setAllocationStrategyString(AllocationValueTypes.COST_SAVINGS.name());
		freightConfig.setAllocationFactor(0.8);
		freightConfig.setParallelism(1);
		config.addModule(freightConfig);
		Scenario scenario = ScenarioUtils.createScenario(config);
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver-a", "receiver-b");
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		FreightCollaborators collaborators = new FreightCollaborators();
		coalition.getCollaboratorsSet().forEach(collaborators::addFreightCollaborator);

		FreightCollaborationEngine engine = new FreightCollaborationEngine(config, scenario, collaborators, store,
			List.of(coalition), (link, time, person, vehicle) -> 0, carrier -> null,
			() -> new FreightPseudoSimulator((distributors, players, subset) -> subset.size() * 10.0),
			parallelism -> Executors.newFixedThreadPool(parallelism));
		engine.runCollaboration();

		assertEquals(4, store.getSimulatedCoalitionScores().get(coalition).size());
		assertEquals(8.0, store.getAllocatedValue(CollaboratorRole.RECEIVER,
			Id.create("receiver-a", Object.class)), 1e-12);
		assertEquals(8.0, store.getAllocatedValue(CollaboratorRole.RECEIVER,
			Id.create("receiver-b", Object.class)), 1e-12);
		assertEquals(4.0, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
		assertEquals(20.0, store.getAllocatedValues().values().stream()
			.mapToDouble(Double::doubleValue).sum(), 1e-12);
	}
}
