package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationLifecycleListenersTest {

	@Test
	void formsEnabledGrandCoalitionThenCarrierReceiverCoalitionFromReceiverDelta() {
		FreightCollaborationConfigGroup group = group(CollaborationTypes.CARRIER_RECEIVER);
		Config config = ConfigUtils.createConfig(group);
		config.controller().setFirstIteration(0);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", "carrier", 5, TimeWindow.newInstance(90, 220));
		ReceiverPlan original = FreightCollaborationTestFixtures.receiverPlan(
			receiver, "carrier", 10, TimeWindow.newInstance(100, 200));
		FreightCollaborator<Carrier> carrierCollaborator =
			FreightCollaboratorFactory.createCollaborator(carrier);
		FreightCollaborator<Receiver> receiverCollaborator =
			FreightCollaboratorFactory.createCollaborator(receiver);
		FreightCollaborator<Receiver> disabled =
			FreightCollaborationTestFixtures.receiverCollaborator("disabled");
		disabled.disableCollaboration();
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(carrierCollaborator);
		collaborators.addFreightCollaborator(receiverCollaborator);
		collaborators.addFreightCollaborator(disabled);
		Map<Id<?>, BasicPlan> receiverPlans = Map.of(receiver.getId(), original);
		CollaborationDataStore store = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, receiverPlans));
		FreightCoalitionManager manager = new FreightCoalitionManager(scenario);
		FormFreightCoalitionListener listener =
			new FormFreightCoalitionListener(scenario, collaborators, manager, store);

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		assertEquals(2, manager.getGrandFreightCoalition().size());
		assertFalse(manager.getGrandFreightCoalition().contains(
			CollaboratorRole.RECEIVER, disabled.getId()));

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));
		assertEquals(1, manager.getMutableFreightCoalitions().size());
		MutableFreightCoalition coalition = manager.getMutableFreightCoalitions().getFirst();
		assertEquals(CollaborationTypes.CARRIER_RECEIVER, coalition.getCollaborationType());
		assertEquals(Set.of(carrierCollaborator, receiverCollaborator),
			coalition.getCollaboratorsSet());
		assertEquals(-10, listener.priority());
	}

	@Test
	void lspFormationFailsClearlyWhenConfigurationHasNoLsp() {
		FreightCollaborationConfigGroup group = group(CollaborationTypes.LSP_RECEIVER);
		Config config = ConfigUtils.createConfig(group);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Receiver receiver = FreightCollaborationTestFixtures.receiver("receiver");
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(
			FreightCollaboratorFactory.createCollaborator(receiver));
		Map<Id<?>, BasicPlan> originals = Map.of(
			receiver.getId(), receiver.getSelectedPlan().createCopy());
		FormFreightCoalitionListener listener = new FormFreightCoalitionListener(
			scenario, collaborators, new FreightCoalitionManager(scenario),
			new CollaborationDataStore(Map.of(CollaboratorRole.RECEIVER, originals)));

		assertThrows(IllegalStateException.class,
			() -> listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false)));
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedAllocationListenerUsesRoleAwareKeysAndFirstIterationSkip() {
		Config config = ConfigUtils.createConfig();
		config.controller().setFirstIteration(0);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = FreightCollaborationTestFixtures.carrier("shared");
		Receiver receiver = FreightCollaborationTestFixtures.receiver("shared");
		carrier.getSelectedPlan().setScore(1.0);
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(
			FreightCollaboratorFactory.createCollaborator(carrier));
		collaborators.addFreightCollaborator(
			FreightCollaboratorFactory.createCollaborator(receiver));
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.CARRIER, carrier.getId()), 2.0,
			new CollaboratorKey(CollaboratorRole.RECEIVER, receiver.getId()), 4.0));
		AllocationToScoreListener listener =
			new AllocationToScoreListener(store, scenario, collaborators);

		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		assertEquals(1.0, carrier.getSelectedPlan().getScore());
		listener.notifyIterationEnds(new IterationEndsEvent(null, 1, false));
		assertEquals(3.0, carrier.getSelectedPlan().getScore());
		assertEquals(4.0, receiver.getSelectedPlan().getScore());

		store.setAllocatedValues(Map.of());
		listener.notifyIterationEnds(new IterationEndsEvent(null, 2, false));
		assertEquals(3.0, carrier.getSelectedPlan().getScore());

		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.CARRIER,
				Id.create("missing", Carrier.class)), 1.0));
		assertThrows(IllegalStateException.class,
			() -> listener.notifyIterationEnds(new IterationEndsEvent(null, 3, false)));
	}

	@Test
	void iterationZeroBaselineSeparatesReceiverFeesAndDoesNotOverwriteCache() {
		FreightCollaborationConfigGroup group = group(CollaborationTypes.CARRIER_RECEIVER);
		group.setIter0BaselineModeString(
			FreightCollaborationConfigGroup.Iter0BaselineMode.FEE_INCLUDED.name());
		group.CARRIER_CHARGED_FEE = 25;
		Config config = ConfigUtils.createConfig(group);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		carrier.getSelectedPlan().setScore(100.0);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", "carrier", 10, TimeWindow.newInstance(0, 100));
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(
			FreightCollaboratorFactory.createCollaborator(receiver));
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		Iter0BaselineCarrierScoreListener listener =
			new Iter0BaselineCarrierScoreListener(scenario, config, store, collaborators);

		listener.notifyIterationEnds(new IterationEndsEvent(null, 1, false));
		assertNull(store.getIter0CarrierBaselineFeeIncluded());
		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		assertEquals(100.0, store.getIter0CarrierBaselineFeeIncluded().get(carrier.getId()));
		assertEquals(75.0, store.getIter0CarrierBaselineFeeFree().get(carrier.getId()));

		carrier.getSelectedPlan().setScore(999.0);
		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		assertEquals(100.0, store.getIter0CarrierBaselineFeeIncluded().get(carrier.getId()),
			"The first cached baseline is immutable for the run");
	}

	@Test
	void lspPreAndAfterListenersCopyAndRestoreConfiguredCarrier() {
		FreightCollaborationConfigGroup group = group(CollaborationTypes.LSP_RECEIVER);
		CollaborationParamSet params = group.getCollaborationParamSets().iterator().next();
		params.setAdditionalParams(Map.of("MAIN_RUN_CARRIER_IDS", Set.of("main")));
		Config config = ConfigUtils.createConfig(group);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier original = FreightCollaborationTestFixtures.carrier("main");
		Carriers carriers = CarriersUtils.addOrGetCarriers(scenario);
		carriers.addCarrier(original);
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();

		LspReceiverPreRerouteListener pre =
			new LspReceiverPreRerouteListener(store, scenario, config);
		pre.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));
		Carrier retained = store.getLspReceiverCopiedNonDistrCarriers().get(original.getId());
		assertNotNull(retained);
		assertNotSame(original, retained);
		assertNotNull(retained.getSelectedPlan());
		assertNotSame(original.getSelectedPlan(), retained.getSelectedPlan());
		assertEquals(1000.0, pre.priority());

		original.clearPlans();
		assertNull(original.getSelectedPlan());
		LspReceiverAfterRerouteListener after =
			new LspReceiverAfterRerouteListener(store, scenario);
		after.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));
		assertSame(retained.getSelectedPlan(), original.getSelectedPlan());
		assertEquals(-10.0, after.priority());
	}

	@Test
	void writeListenerSkipsFirstIterationAndWritesLaterData(@TempDir Path output) {
		Config config = ConfigUtils.createConfig();
		config.controller().setFirstIteration(0);
		Scenario scenario = ScenarioUtils.createScenario(config);
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(output.toString(),
			OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles,
			ControllerConfigGroup.CompressionType.none);
		hierarchy.createIterationDirectory(1);
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.CARRIER, carrier.getId()), 3.0));
		WriteCollaborationDataListener listener =
			new WriteCollaborationDataListener(store, scenario, hierarchy);

		listener.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		assertFalse(Files.exists(Path.of(
			hierarchy.getIterationFilename(0, "collaboration_data.xml"))));
		listener.notifyIterationEnds(new IterationEndsEvent(null, 1, false));
		assertTrue(Files.exists(Path.of(
			hierarchy.getIterationFilename(1, "collaboration_data.xml"))));
	}

	private static FreightCollaborationConfigGroup group(CollaborationTypes type) {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		group.addParameterSet(new CollaborationParamSet(type, Set.of()));
		return group;
	}
}
