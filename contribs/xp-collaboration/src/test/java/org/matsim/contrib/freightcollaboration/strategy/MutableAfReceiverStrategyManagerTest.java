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
		setRouteProfile(carrier, receiver);
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
			assertUniqueBehaviors(receiver);
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
	void normalizesLegacyDuplicatesWithOutsideSelectedScoreAndOrderPriority() {
		Fixture fixture = fixture();
		ReceiverPlan outside = fixture.receiver.getSelectedPlan();

		ReceiverPlan selectedLowerScore = planWithEnd(outside, 11 * 3600.0, 6.0, false);
		ReceiverPlan unselectedHigherScore = MutableAfPlanUtils.copyReceiverPlan(
			selectedLowerScore, true);
		unselectedHigherScore.setScore(9.0);
		fixture.receiver.addPlan(selectedLowerScore);
		fixture.receiver.addPlan(unselectedHigherScore);
		fixture.receiver.setSelectedPlan(selectedLowerScore);
		fixture.manager.normalizeAndTrim(fixture.receiver);

		assertTrue(fixture.receiver.getPlans().contains(selectedLowerScore),
			"the selected duplicate outranks a higher-scored equivalent");
		assertFalse(fixture.receiver.getPlans().contains(unselectedHigherScore));

		ReceiverPlan selectedOutsideDuplicate = MutableAfPlanUtils.copyReceiverPlan(outside, true);
		selectedOutsideDuplicate.getAttributes().putAttribute(
			MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, false);
		selectedOutsideDuplicate.setScore(100.0);
		fixture.receiver.addPlan(selectedOutsideDuplicate);
		fixture.receiver.setSelectedPlan(selectedOutsideDuplicate);
		fixture.manager.normalizeAndTrim(fixture.receiver);

		assertSame(outside, fixture.receiver.getSelectedPlan(),
			"the outside option outranks an equivalent selected adaptive plan");
		assertFalse(fixture.receiver.getPlans().contains(selectedOutsideDuplicate));

		ReceiverPlan firstEqualScore = planWithEnd(outside, 12 * 3600.0, 8.0, false);
		ReceiverPlan laterEqualScore = MutableAfPlanUtils.copyReceiverPlan(firstEqualScore, true);
		fixture.receiver.addPlan(firstEqualScore);
		fixture.receiver.addPlan(laterEqualScore);
		fixture.manager.normalizeAndTrim(fixture.receiver);

		assertTrue(fixture.receiver.getPlans().contains(firstEqualScore));
		assertFalse(fixture.receiver.getPlans().contains(laterEqualScore),
			"original list order breaks otherwise equal canonicalization ties");
		assertUniqueBehaviors(fixture.receiver);
		assertEquals(1, fixture.receiver.getPlans().stream().filter(fixture.store::isOutsideOption).count());
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
		fixture.dataStore.reset(4);
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
	void finalRevisitDisablesReceiverMutationAndContextPlanCreation() {
		Fixture fixture = fixture();
		advanceToFirstWarmStartTrial(fixture);
		// Finalization at replanning restores the stable AF=0.5 checkpoint before Receiver strategy.
		fixture.store.prepareReplanning(90);
		assertEquals(org.matsim.contrib.freightcollaboration.learning.MutableAfPhase.FINAL_REVISIT,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		ReceiverPlan restored = fixture.receiver.getSelectedPlan();
		int planCount = fixture.receiver.getPlans().size();

		for (int iteration = 90; iteration <= 95; iteration++) {
			fixture.manager.run(List.of(fixture.receiver), iteration, null);
		}

		assertSame(restored, fixture.receiver.getSelectedPlan());
		assertEquals(planCount, fixture.receiver.getPlans().size());
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
		mutableConfig.setStabilityWindow(3);
		mutableConfig.setMaxAdaptDwell(3);
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
		setRouteProfile(carrier, receiver);
		return new Fixture(mutableConfig, carrier, receiver, dataStore, store,
			new MutableAfReceiverStrategyManager(store, mutableConfig, dataStore));
	}

	private static void advanceToFirstWarmStartTrial(Fixture fixture) {
		fixture.store.observeIterationEnd(0);
		fixture.store.prepareReplanning(1);
		fixture.dataStore.reset(1);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(1);
		fixture.store.prepareReplanning(2);
		fixture.dataStore.reset(2);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(2);
		fixture.store.prepareReplanning(3);
		fixture.dataStore.reset(3);
		fixture.carrier.getSelectedPlan().setScore(12.0);
		fixture.receiver.getSelectedPlan().setScore(8.0);
		fixture.store.observeIterationEnd(3);
		fixture.store.prepareReplanning(4);
	}

	private static void setRouteProfile(Carrier carrier, Receiver receiver) {
		carrier.getSelectedPlan().getAttributes().putAttribute(
			MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			MutableAfPlanUtils.selectedReceiverProfile(carrier, List.of(receiver)));
	}

	private static ReceiverPlan planWithEnd(ReceiverPlan source, double end, double score,
			boolean outside) {
		ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(source, false);
		TimeWindow current = copy.getTimeWindows().getFirst();
		copy.getTimeWindows().set(0, TimeWindow.newInstance(current.getStart(), end));
		copy.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, outside);
		copy.setScore(score);
		return copy;
	}

	private static void assertUniqueBehaviors(Receiver receiver) {
		assertEquals(receiver.getPlans().size(), receiver.getPlans().stream()
			.map(MutableAfPlanUtils::receiverPlanSignature).distinct().count());
	}

	private record Fixture(MutableAllocationFactorConfigGroup config, Carrier carrier, Receiver receiver,
			CollaborationDataStore dataStore, MutableAfLearningStore store,
			MutableAfReceiverStrategyManager manager) {
	}
}
