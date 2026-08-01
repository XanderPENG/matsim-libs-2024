package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.receiver.Receiver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAllocationFactorStatsListenerTest {

	@Test
	void writesStableSelectedBestCoalitionAndTransferColumns(@TempDir Path directory) throws Exception {
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(directory.toString());
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		CarrierPlan lower = carrier.getSelectedPlan();
		lower.setScore(10.0);
		CarrierAllocationFactor.set(lower, 0.3, mutableConfig);
		CarrierPlan best = new CarrierPlan(carrier, new java.util.ArrayList<>());
		best.setScore(20.0);
		CarrierAllocationFactor.set(best, 0.7, mutableConfig);
		carrier.addPlan(best);
		carrier.setSelectedPlan(lower);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);

		Receiver receiver = FreightCollaborationTestFixtures.receiver("receiver");
		MutableFreightCoalition coalition = new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(carrier));
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(receiver));
		FreightCoalitionManager coalitionManager = new FreightCoalitionManager(scenario);
		coalitionManager.setMutableFreightCoalitions(List.of(coalition));
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.recordDistributorPlayerTransfer(
			new org.matsim.contrib.freightcollaboration.CollaboratorKey(
				org.matsim.contrib.freightcollaboration.CollaboratorRole.CARRIER,
				Id.create("carrier", Carrier.class)), 4.5);
		OutputDirectoryHierarchy output = new OutputDirectoryHierarchy(config);
		MutableAllocationFactorStatsListener listener = new MutableAllocationFactorStatsListener(
			scenario, output, store, coalitionManager);

		listener.notifyStartup(new StartupEvent(null));
		listener.notifyIterationEnds(new IterationEndsEvent(null, 3, false));

		List<String> lines = Files.readAllLines(directory.resolve(
			MutableAllocationFactorStatsListener.OUTPUT_FILE));
		assertEquals(2, lines.size());
		assertTrue(lines.getFirst().startsWith("iteration,carrierId,selectedFactor"));
		assertEquals("3,\"carrier\",0.3,10.0,0.7,20.0,true,1,4.5", lines.get(1));
	}
}
