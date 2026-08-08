package org.matsim.contrib.freightcollaboration.strategy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAfReceiverStrategyManagerTest {

	@AfterEach
	void resetRandom() {
		MatsimRandom.reset();
	}

	@Test
	void replansEachReceiverAtMostOnceAndProtectsFactorContextAndOutsideOption() {
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		mutableConfig.setMinAllocationFactor(0.5);
		mutableConfig.setMaxAllocationFactor(0.6);
		mutableConfig.setAllocationFactorStep(0.1);
		mutableConfig.setInitialAllocationFactor(0.5);
		mutableConfig.setMaxFactorPlans(2);
		mutableConfig.setMaxReceiverPlansPerFactor(3);
		Config config = ConfigUtils.createConfig(mutableConfig);
		config.controller().setLastIteration(100);
		Scenario scenario = ScenarioUtils.createScenario(config);

		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		carrier.getSelectedPlan().setScore(10.0);
		CarrierAllocationFactor.set(carrier.getSelectedPlan(), 0.5, mutableConfig);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder("receiver", "carrier",
			600.0, TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		receiver.getSelectedPlan().setScore(5.0);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);

		ReceiverPlan original = MutableAfPlanUtils.copyReceiverPlan(receiver.getSelectedPlan(), false);
		Map<Id<?>, ? extends BasicPlan> originals = Map.of(receiver.getId(), original);
		CollaborationDataStore dataStore = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, originals));
		FreightCoalitionManager coalitionManager = new FreightCoalitionManager(scenario);
		coalitionManager.setMutableFreightCoalitions(List.of());
		MutableAfLearningStore store = new MutableAfLearningStore(
			scenario, mutableConfig, dataStore, coalitionManager);
		store.initialize();
		store.observeIterationEnd(0);
		store.prepareReplanning(1);
		MutableAfReceiverStrategyManager manager = new MutableAfReceiverStrategyManager(
			store, mutableConfig, dataStore);

		MatsimRandom.reset(123L);
		manager.run(List.of(receiver), 1, null);
		int afterFirstCall = receiver.getPlans().size();
		ReceiverPlan selectedAfterFirstCall = receiver.getSelectedPlan();
		manager.run(List.of(receiver), 1, null);

		assertEquals(afterFirstCall, receiver.getPlans().size());
		assertSame(selectedAfterFirstCall, receiver.getSelectedPlan());
		for (int iteration = 2; iteration < 40; iteration++) {
			manager.run(List.of(receiver), iteration, null);
		}
		assertTrue(receiver.getPlans().size() <= 3);
		assertEquals(1, receiver.getPlans().stream().filter(store::isOutsideOption).count());
		assertTrue(receiver.getPlans().stream().allMatch(plan ->
			store.contextIndex(plan).orElseThrow() == 0));
		assertTrue(receiver.getPlans().stream().allMatch(plan ->
			plan.getTimeWindows().getFirst().getEnd() >= 10 * 3600.0));
		assertTrue(receiver.getPlans().stream().allMatch(plan ->
			plan.getTimeWindows().getFirst().getStart() == 8 * 3600.0));
	}

	@Test
	void mutableManagerExposesOnlyItsFixedPolicyContract() {
		Fixture fixture = fixture();
		MutableAfReceiverStrategyManager manager = fixture.manager;

		assertEquals(List.of(), manager.getStrategies(null));
		assertEquals(List.of(0.7, 0.3), manager.getWeights(null));
		manager.setMaxPlansPerAgent(2);
		assertThrows(IllegalArgumentException.class, () -> manager.setMaxPlansPerAgent(0));
		assertThrows(UnsupportedOperationException.class,
			() -> manager.addStrategy(null, null, 1.0));
		assertThrows(UnsupportedOperationException.class,
			() -> manager.addChangeRequest(1, null, null, 0.0));
		assertThrows(UnsupportedOperationException.class,
			() -> manager.setPlanSelectorForRemoval(null));

		// BASELINE is frozen: this covers the non-ADAPT keep-incumbent path.
		ReceiverPlan selected = fixture.receiver.getSelectedPlan();
		manager.run(List.of(fixture.receiver), 0, null);
		assertSame(selected, fixture.receiver.getSelectedPlan());
	}

	@Test
	void warmStartTrialKeepsThePendingPlanForOneRoundThenRestoresAdaptation() {
		Fixture fixture = fixture();
		advanceToFirstWarmStartTrial(fixture);
		ReceiverPlan warmStart = fixture.receiver.getSelectedPlan();
		int planCount = fixture.receiver.getPlans().size();
		double timeWindowEnd = warmStart.getTimeWindows().getFirst().getEnd();
		Double compatibilityScore = warmStart.getScore();

		// This is the same unboxing performed by ReceiverControlerListener before it delegates
		// to ReceiverStrategyManager. The compatibility value must therefore still be finite here.
		double initialCost = compatibilityScore;
		assertTrue(Double.isFinite(initialCost));
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		MatsimRandom.reset(0L);
		fixture.manager.run(List.of(fixture.receiver), 4, null);
		fixture.manager.run(List.of(fixture.receiver), 4, null);

		assertSame(warmStart, fixture.receiver.getSelectedPlan());
		assertEquals(planCount, fixture.receiver.getPlans().size());
		assertEquals(timeWindowEnd, fixture.receiver.getSelectedPlan().getTimeWindows().getFirst().getEnd());
		assertEquals(compatibilityScore, fixture.receiver.getSelectedPlan().getScore());

		fixture.store.beginWarmStartExecution(4);
		fixture.carrier.getSelectedPlan().setScore(13.0);
		warmStart.setScore(9.0);
		fixture.store.observeIterationEnd(4);
		assertFalse(MutableAfPlanUtils.isPendingEvaluation(warmStart));

		boolean adaptationChangedSelectedPlan = false;
		for (int iteration = 5; iteration < 100; iteration++) {
			fixture.manager.run(List.of(fixture.receiver), iteration, null);
			if (fixture.receiver.getSelectedPlan() != warmStart) {
				adaptationChangedSelectedPlan = true;
				break;
			}
		}
		assertTrue(adaptationChangedSelectedPlan,
			"the iteration after the frozen trial must resume normal selection/mutation");
	}

	@Test
	void warmStartTrialRejectsEveryInvalidCompatibilityState() {
		Fixture wrongContext = fixture();
		advanceToFirstWarmStartTrial(wrongContext);
		wrongContext.receiver.getSelectedPlan().getAttributes().putAttribute(
			MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, 0);
		assertThrows(IllegalStateException.class,
			() -> wrongContext.manager.run(List.of(wrongContext.receiver), 4, null));

		Fixture notPending = fixture();
		advanceToFirstWarmStartTrial(notPending);
		MutableAfPlanUtils.clearPendingEvaluation(notPending.receiver.getSelectedPlan());
		assertThrows(IllegalStateException.class,
			() -> notPending.manager.run(List.of(notPending.receiver), 4, null));

		Fixture missingScore = fixture();
		advanceToFirstWarmStartTrial(missingScore);
		missingScore.receiver.getSelectedPlan().setScore(null);
		assertThrows(IllegalStateException.class,
			() -> missingScore.manager.run(List.of(missingScore.receiver), 4, null));

		Fixture nonFiniteScore = fixture();
		advanceToFirstWarmStartTrial(nonFiniteScore);
		nonFiniteScore.receiver.getSelectedPlan().setScore(Double.NaN);
		assertThrows(IllegalStateException.class,
			() -> nonFiniteScore.manager.run(List.of(nonFiniteScore.receiver), 4, null));
	}

	@Test
	void selectionHandlesUnscoredAndDifferentlyScoredFactorLocalPlans() {
		Fixture unscored = fixture();
		unscored.store.observeIterationEnd(0);
		unscored.store.prepareReplanning(1);
		ReceiverPlan selected = unscored.receiver.getSelectedPlan();
		unscored.receiver.getPlans().forEach(plan -> plan.setScore(null));
		MatsimRandom.reset(0L);
		unscored.manager.run(List.of(unscored.receiver), 1, null);
		assertSame(selected, unscored.receiver.getSelectedPlan(),
			"an unscored factor-local memory keeps its selected behavior");

		Fixture scored = fixture();
		scored.store.observeIterationEnd(0);
		scored.store.prepareReplanning(1);
		ReceiverPlan alternative = MutableAfPlanUtils.copyReceiverPlan(
			scored.receiver.getSelectedPlan(), true);
		alternative.setScore(20.0);
		scored.receiver.getSelectedPlan().setScore(5.0);
		scored.receiver.addPlan(alternative);
		MatsimRandom.reset(0L);
		scored.manager.run(List.of(scored.receiver), 1, null);
		assertTrue(scored.receiver.getPlans().contains(scored.receiver.getSelectedPlan()));
	}

	@Test
	void selectionAndMutationRejectMissingBehavioralInputs() {
		Fixture emptyPlans = fixture();
		emptyPlans.store.observeIterationEnd(0);
		emptyPlans.store.prepareReplanning(1);
		emptyPlans.receiver.getPlans().clear();
		MatsimRandom.reset(1L); // selection branch
		assertThrows(IllegalStateException.class,
			() -> emptyPlans.manager.run(List.of(emptyPlans.receiver), 1, null));

		Fixture missingOriginalWindow = fixture();
		missingOriginalWindow.store.observeIterationEnd(0);
		missingOriginalWindow.store.prepareReplanning(1);
		MutableAfReceiverStrategyManager managerWithWrongOriginalStore =
			new MutableAfReceiverStrategyManager(missingOriginalWindow.store,
				missingOriginalWindow.config, new CollaborationDataStore(Map.of()));
		MatsimRandom.reset(0L);
		assertThrows(IllegalStateException.class,
			() -> {
				for (int iteration = 1; iteration < 100; iteration++) {
					managerWithWrongOriginalStore.run(List.of(missingOriginalWindow.receiver), iteration, null);
				}
			});

		Fixture missingActiveWindow = fixture();
		missingActiveWindow.store.observeIterationEnd(0);
		missingActiveWindow.store.prepareReplanning(1);
		missingActiveWindow.receiver.getSelectedPlan().getTimeWindows().clear();
		MatsimRandom.reset(0L);
		assertThrows(IllegalStateException.class,
			() -> {
				for (int iteration = 1; iteration < 100; iteration++) {
					missingActiveWindow.manager.run(List.of(missingActiveWindow.receiver), iteration, null);
				}
			});
	}

	private static Fixture fixture() {
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		mutableConfig.setMinAllocationFactor(0.5);
		mutableConfig.setMaxAllocationFactor(0.6);
		mutableConfig.setAllocationFactorStep(0.1);
		mutableConfig.setInitialAllocationFactor(0.5);
		mutableConfig.setMaxFactorPlans(2);
		mutableConfig.setNewFactorMinDwell(1);
		mutableConfig.setRevisitFactorMinDwell(1);
		mutableConfig.setStabilityWindow(1);
		mutableConfig.setMaxAdaptDwell(2);
		mutableConfig.setEvaluationWindow(1);
		Config config = ConfigUtils.createConfig(mutableConfig);
		config.controller().setLastIteration(100);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		carrier.getSelectedPlan().setScore(10.0);
		CarrierAllocationFactor.set(carrier.getSelectedPlan(), 0.5, mutableConfig);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder("receiver", "carrier",
			600.0, TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		receiver.getSelectedPlan().setScore(5.0);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);
		ReceiverPlan original = MutableAfPlanUtils.copyReceiverPlan(receiver.getSelectedPlan(), false);
		CollaborationDataStore dataStore = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, Map.of(receiver.getId(), original)));
		FreightCoalitionManager coalitionManager = new FreightCoalitionManager(scenario);
		coalitionManager.setMutableFreightCoalitions(List.of());
		MutableAfLearningStore store = new MutableAfLearningStore(
			scenario, mutableConfig, dataStore, coalitionManager);
		store.initialize();
		return new Fixture(mutableConfig, carrier, receiver, store,
			new MutableAfReceiverStrategyManager(store, mutableConfig, dataStore));
	}

	private static void advanceToFirstWarmStartTrial(Fixture fixture) {
		fixture.store.observeIterationEnd(0);
		fixture.store.prepareReplanning(1);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(1);
		fixture.store.prepareReplanning(2);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(2);
		fixture.store.prepareReplanning(3);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(3);
		fixture.store.prepareReplanning(4);
	}

	private record Fixture(MutableAllocationFactorConfigGroup config, Carrier carrier, Receiver receiver,
			MutableAfLearningStore store, MutableAfReceiverStrategyManager manager) {
	}
}
