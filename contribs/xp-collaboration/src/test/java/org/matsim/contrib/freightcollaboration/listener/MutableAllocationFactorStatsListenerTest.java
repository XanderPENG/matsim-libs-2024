package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAllocationFactorStatsListenerTest {

	@Test
	void writesStateAndInitializesAllThreeCsvFiles(@TempDir Path directory) throws Exception {
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		Config config = ConfigUtils.createConfig(mutableConfig);
		config.controller().setLastIteration(100);
		config.controller().setOutputDirectory(directory.toString());
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		Scenario scenario = ScenarioUtils.createScenario(config);

		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier,quoted");
		carrier.getSelectedPlan().setScore(10.0);
		CarrierAllocationFactor.set(carrier.getSelectedPlan(), 0.8, mutableConfig);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);

		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", carrier.getId().toString(), 600.0,
			TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		receiver.getSelectedPlan().setScore(5.0);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);

		MutableFreightCoalition coalition = new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(carrier));
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(receiver));
		FreightCoalitionManager coalitionManager = new FreightCoalitionManager(scenario);
		coalitionManager.setMutableFreightCoalitions(List.of(coalition));

		ReceiverPlan original = MutableAfPlanUtils.copyReceiverPlan(receiver.getSelectedPlan(), false);
		Map<org.matsim.api.core.v01.Id<?>, ? extends BasicPlan> receiverOriginals =
			Map.of(receiver.getId(), original);
		CollaborationDataStore collaborationStore = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, receiverOriginals));
		MutableAfLearningStore learningStore = new MutableAfLearningStore(
			scenario, mutableConfig, collaborationStore, coalitionManager);
		OutputDirectoryHierarchy output = new OutputDirectoryHierarchy(config);
		MutableAllocationFactorStatsListener listener = new MutableAllocationFactorStatsListener(
			scenario, output, learningStore);

		listener.notifyStartup(new StartupEvent(null));
		learningStore.observeIterationEnd(0);
		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		List<String> lines = Files.readAllLines(directory.resolve(
			MutableAllocationFactorStatsListener.OUTPUT_FILE));
		assertEquals(2, lines.size());
		assertTrue(lines.getFirst().startsWith("iteration,carrierId,phase,activeFactorIndex"));
		assertTrue(lines.getFirst().contains(
			"warmStartTrial,warmStartSourceFactorIndex,warmStartScoreClearedBeforeMobsim"));
		assertTrue(lines.get(1).startsWith("0,\"carrier,quoted\",BASELINE,8,0.8"));
		assertTrue(lines.get(1).contains(",false,,false,"));
		assertEquals(1, Files.readAllLines(directory.resolve(
			MutableAllocationFactorStatsListener.LOCAL_OPTIMA_FILE)).size());
		assertEquals(1, Files.readAllLines(directory.resolve(
			MutableAllocationFactorStatsListener.RECEIVER_OUTCOMES_FILE)).size());
	}
}
