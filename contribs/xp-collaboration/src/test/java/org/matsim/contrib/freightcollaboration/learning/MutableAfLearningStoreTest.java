package org.matsim.contrib.freightcollaboration.learning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAfLearningStoreTest {

	@AfterEach
	void resetRandom() {
		MatsimRandom.reset();
	}

	@Test
	void stableWindowCreatesCheckpointFromLatestRealExecutionAndSwitchesNextReplanning() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 5, 5, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);

		ReceiverPlan executedPlan = null;
		for (int iteration = 1; iteration <= 5; iteration++) {
			double end = (10 + iteration) * 3600.0;
			executedPlan = selectPlan(fixture, end, 0);
			execute(fixture, iteration, 100.0 + iteration, 50.0 + 0.5 * iteration, 0.0);
			if (iteration < 5) {
				assertEquals(MutableAfPhase.ADAPT, snapshot(fixture).phase());
				fixture.store.prepareReplanning(iteration + 1);
			}
		}

		CarrierPlan executedCarrierPlan = fixture.carrier.getSelectedPlan();
		assertEquals(MutableAfPhase.SWITCH_PENDING, snapshot(fixture).phase());
		assertSame(executedCarrierPlan, fixture.carrier.getSelectedPlan());
		assertSame(executedPlan, fixture.receiver.getSelectedPlan(),
			"IterationEnds may schedule a switch but must not change the executed plans");

		FactorCheckpoint checkpoint = fixture.store.checkpoint(fixture.carrier.getId(), 0).orElseThrow();
		assertEquals(CheckpointReason.STABLE_WINDOW, checkpoint.reason());
		assertEquals(FactorMaturity.STABLE_CHECKPOINT, checkpoint.maturity());
		assertEquals(5, checkpoint.sourceIteration());
		assertEquals(5, checkpoint.executedState().observation().capturedAtIteration());
		assertEquals(15 * 3600.0, checkpointEnd(fixture.store
			.checkpoint(fixture.carrier.getId(), 0).orElseThrow()));
		assertEquals(105.0, checkpoint.executedState().observation().carrierScore());
		assertEquals(5, checkpoint.windowStatistics().size());
		assertEquals(1.0, checkpoint.windowStatistics().coalitionSimilarity());
		assertTrue(checkpoint.windowStatistics().receiverParticipationFeasible());

		ReceiverPlan exposedCheckpointPlan = checkpoint.executedState()
			.selectedReceiverPlans().values().iterator().next();
		exposedCheckpointPlan.getTimeWindows().set(0,
			TimeWindow.newInstance(8 * 3600.0, 19 * 3600.0));
		assertEquals(15 * 3600.0, checkpointEnd(fixture.store
			.checkpoint(fixture.carrier.getId(), 0).orElseThrow()),
			"the public checkpoint view must not expose the store's recoverable snapshot");

		// A mutation after capture must not alter the immutable historical state.
		fixture.receiver.getSelectedPlan().getTimeWindows().set(0,
			TimeWindow.newInstance(8 * 3600.0, 20 * 3600.0));
		assertEquals(15 * 3600.0, checkpointEnd(fixture.store
			.checkpoint(fixture.carrier.getId(), 0).orElseThrow()));

		fixture.store.prepareReplanning(6);
		assertEquals(MutableAfPhase.WARM_START_TRIAL, snapshot(fixture).phase());
		assertEquals(0.6, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertNotSame(executedCarrierPlan, fixture.carrier.getSelectedPlan());
	}

	@Test
	void relativeRangeHandlesNegativeAndNearZeroScores() {
		Fixture negative = fixture(0.5, 0.6, 0.1, 2, 2, 2, 0.05);
		setScores(negative, -100.0, -50.0);
		baseline(negative);
		negative.store.prepareReplanning(1);
		execute(negative, 1, -100.0, -50.0, 0.0);
		negative.store.prepareReplanning(2);
		execute(negative, 2, -95.0, -47.5, 0.0);
		assertEquals(0.05, snapshot(negative).carrierWindowRelativeRange(), 1e-12);
		assertEquals(0.05, snapshot(negative).receiverWindowRelativeRange(), 1e-12);
		assertEquals(FactorMaturity.STABLE_CHECKPOINT,
			negative.store.factorSummaries(negative.carrier.getId()).get(0).maturity());

		Fixture nearZero = fixture(0.5, 0.6, 0.1, 2, 2, 2, 0.5);
		setScores(nearZero, -1.0, -1.0);
		baseline(nearZero);
		nearZero.store.prepareReplanning(1);
		execute(nearZero, 1, -0.25, -0.25, 0.0);
		nearZero.store.prepareReplanning(2);
		execute(nearZero, 2, 0.25, 0.25, 0.0);
		assertEquals(0.5, snapshot(nearZero).carrierWindowRelativeRange(), 1e-12,
			"scores near zero must use denominator 1.0");
	}

	@Test
	void stabilityUsesRelativeRangeAndRequiresAFullWindowAndStableCoalition() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 5, 6, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);

		for (int iteration = 1; iteration <= 4; iteration++) {
			execute(fixture, iteration, 100.0, 50.0, 0.0);
			assertEquals(MutableAfPhase.ADAPT, snapshot(fixture).phase());
			fixture.store.prepareReplanning(iteration + 1);
		}
		assertEquals(4, snapshot(fixture).stabilityWindowSize());

		// Exactly 5% range is stable: (100 - 95) / 100 == 0.05.
		execute(fixture, 5, 95.0, 47.5, 0.0);
		assertEquals(MutableAfPhase.SWITCH_PENDING, snapshot(fixture).phase());
		assertEquals(0.05, snapshot(fixture).carrierWindowRelativeRange(), 1e-12);
		assertEquals(0.05, snapshot(fixture).receiverWindowRelativeRange(), 1e-12);

		Fixture coalitionChanges = fixture(0.5, 0.6, 0.1, 2, 3, 3, 1.0);
		baseline(coalitionChanges);
		coalitionChanges.store.prepareReplanning(1);
		execute(coalitionChanges, 1, 11.0, 6.0, 0.0);
		coalitionChanges.store.prepareReplanning(2);
		setCoalition(coalitionChanges, true);
		execute(coalitionChanges, 2, 11.0, 6.0, 10.0);
		coalitionChanges.store.prepareReplanning(3);
		execute(coalitionChanges, 3, 11.0, 6.0, 10.0);

		assertEquals(MutableAfPhase.SWITCH_PENDING, snapshot(coalitionChanges).phase(),
			"max dwell ends the visit even when the coalition window is unstable");
		assertEquals(0.0, snapshot(coalitionChanges).coalitionWindowSimilarity());
		assertEquals(CheckpointReason.MAX_DWELL_FALLBACK,
			coalitionChanges.store.checkpoint(coalitionChanges.carrier.getId(), 0).orElseThrow().reason());
	}

	@Test
	void coalitionSimilarityUsesWindowIntersectionOverUnionAndHonorsThreshold() {
		List<Id<Receiver>> receivers = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			receivers.add(Id.create("r" + index, Receiver.class));
		}
		Set<Id<Receiver>> all = Set.copyOf(receivers);
		Set<Id<Receiver>> seven = Set.copyOf(receivers.subList(0, 7));
		Set<Id<Receiver>> six = Set.copyOf(receivers.subList(0, 6));

		assertEquals(0.7, MutableAfLearningStore.coalitionSimilarity(
			List.of(all, seven, seven)), 1e-12);
		assertEquals(0.6, MutableAfLearningStore.coalitionSimilarity(
			List.of(all, six, six)), 1e-12);
		assertEquals(1.0, MutableAfLearningStore.coalitionSimilarity(
			List.of(Set.of(), Set.of())), 1e-12);
		assertTrue(new WindowStatistics(5, 100, 104, 0.04, 50, 52, 0.04,
			10, 9, 11, 1, 0.7, true).satisfies(5, 0.05, 0.7));
		assertFalse(new WindowStatistics(5, 100, 104, 0.04, 50, 52, 0.04,
			10, 9, 11, 1, 0.699, true).satisfies(5, 0.05, 0.7));
	}

	@Test
	void receiverParticipationViolationPreventsStableCheckpoint() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		setCoalition(fixture, true);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		for (int iteration = 1; iteration <= 3; iteration++) {
			execute(fixture, iteration, 10.0, 4.0, 0.0);
			if (iteration < 3) {
				assertEquals(MutableAfPhase.ADAPT, snapshot(fixture).phase());
				fixture.store.prepareReplanning(iteration + 1);
			}
		}

		assertEquals(1.0, snapshot(fixture).coalitionWindowSimilarity());
		assertFalse(snapshot(fixture).receiverParticipationWindowFeasible());
		assertEquals(MutableAfPhase.SWITCH_PENDING, snapshot(fixture).phase());
		assertTrue(fixture.store.checkpoint(fixture.carrier.getId(), 0).isEmpty());
		assertEquals("MAX_DWELL_NO_FEASIBLE_SOLUTION", snapshot(fixture).decision());
	}

	@Test
	void maxDwellUsesTheConfiguredPolicyAndRetainsAnOlderRealSnapshot() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 3, 3, 0.0);
		fixture.config.setSolutionSelectionPolicy(MutableAfSelectionPolicy.CARRIER_BEST);
		baseline(fixture);
		fixture.store.prepareReplanning(1);

		selectPlan(fixture, 11 * 3600.0, 0);
		execute(fixture, 1, 11.0, 20.0, 4.0);
		fixture.store.prepareReplanning(2);
		selectPlan(fixture, 12 * 3600.0, 0);
		execute(fixture, 2, 15.0, 10.0, 3.0);
		fixture.store.prepareReplanning(3);
		selectPlan(fixture, 13 * 3600.0, 0);
		execute(fixture, 3, 12.0, 30.0, 20.0);

		FactorCheckpoint checkpoint = fixture.store.checkpoint(fixture.carrier.getId(), 0).orElseThrow();
		assertEquals(FactorMaturity.FALLBACK_CHECKPOINT, checkpoint.maturity());
		assertEquals(CheckpointReason.MAX_DWELL_FALLBACK, checkpoint.reason());
		assertEquals(2, checkpoint.sourceIteration(), "carrier-best must keep iteration 2");
		assertEquals(12 * 3600.0, checkpointEnd(checkpoint));
		assertEquals(5.0, checkpoint.objectiveValue());

		selectPlan(fixture, 18 * 3600.0, 0);
		assertEquals(12 * 3600.0, checkpointEnd(checkpoint),
			"visit-best must be a deep snapshot, not a live plan reference");
	}

	@Test
	void noFeasibleVisitProducesNoCheckpointAndFinalizationRestoresBaseline() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 2, 2, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		execute(fixture, 1, 9.0, 6.0, 0.0);
		fixture.store.prepareReplanning(2);
		execute(fixture, 2, 8.0, 7.0, 0.0);

		assertEquals(FactorMaturity.NO_FEASIBLE_CHECKPOINT,
			fixture.store.factorSummaries(fixture.carrier.getId()).get(0).maturity());
		assertTrue(fixture.store.checkpoint(fixture.carrier.getId(), 0).isEmpty());
		assertEquals("MAX_DWELL_NO_FEASIBLE_SOLUTION", snapshot(fixture).decision());

		fixture.store.prepareReplanning(90);
		assertEquals(MutableAfPhase.FINAL_REVISIT, snapshot(fixture).phase());
		assertEquals("FINAL_REVISIT_BASELINE_ONLY", snapshot(fixture).finalStatus());
		assertEquals(10.0, fixture.carrier.getSelectedPlan().getScore());
		assertEquals(5.0, fixture.receiver.getSelectedPlan().getScore());
		fixture.store.prepareReplanning(100);
		assertEquals(MutableAfPhase.FINAL_SELECTION, snapshot(fixture).phase());
		assertEquals("NO_FEASIBLE_FACTOR_BASELINE_FALLBACK", snapshot(fixture).finalStatus());
		assertEquals("BASELINE", fixture.store.checkpointEvents().getLast().finalSelectionTier());
	}

	@Test
	void finalizationSettlesCurrentVisitAndPrefersStableOverFallback() {
		Fixture stableFixture = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		baseline(stableFixture);
		stableFixture.store.prepareReplanning(1);
		execute(stableFixture, 1, 12.0, 7.0, 0.0);
		stableFixture.store.prepareReplanning(2);
		execute(stableFixture, 2, 12.0, 7.0, 0.0);
		assertEquals(FactorMaturity.STABLE_CHECKPOINT,
			stableFixture.store.factorSummaries(stableFixture.carrier.getId()).get(0).maturity());

		stableFixture.store.prepareReplanning(90);
		assertEquals(MutableAfPhase.FINAL_REVISIT, snapshot(stableFixture).phase());
		stableFixture.store.prepareReplanning(100);
		assertEquals("FINAL_SELECTION_STABLE", snapshot(stableFixture).finalStatus());
		assertEquals("STABLE", stableFixture.store.checkpointEvents().getLast().finalSelectionTier());
		assertEquals(12.0, snapshot(stableFixture).checkpointSelectionScore());

		Fixture unfinished = fixture(0.5, 0.6, 0.1, 2, 5, 10, 0.05);
		baseline(unfinished);
		unfinished.store.prepareReplanning(1);
		execute(unfinished, 1, 11.0, 6.0, 0.0);
		unfinished.store.prepareReplanning(2);
		execute(unfinished, 2, 14.0, 8.0, 0.0);
		unfinished.store.prepareReplanning(90);

		FactorCheckpoint fallback = unfinished.store.checkpoint(
			unfinished.carrier.getId(), 0).orElseThrow();
		assertEquals(CheckpointReason.FINALIZATION_FALLBACK, fallback.reason());
		assertEquals(2, fallback.sourceIteration());
		assertEquals(MutableAfPhase.FINAL_REVISIT, snapshot(unfinished).phase());
		unfinished.store.prepareReplanning(100);
		assertEquals("FINAL_SELECTION_FALLBACK", snapshot(unfinished).finalStatus());
		assertEquals("FALLBACK", unfinished.store.checkpointEvents().getLast().finalSelectionTier());
	}

	@Test
	void warmStartTrialInitializesUnseenFactorButRevisitUsesItsOwnContextDirectly() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 2, 2, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		selectPlan(fixture, 12 * 3600.0, 0);
		execute(fixture, 1, 12.0, 8.0, 0.0);
		fixture.store.prepareReplanning(2);
		execute(fixture, 2, 12.0, 8.0, 0.0);
		fixture.store.prepareReplanning(3);

		ReceiverPlan warm = fixture.receiver.getSelectedPlan();
		assertEquals(MutableAfPhase.WARM_START_TRIAL, snapshot(fixture).phase());
		assertEquals(0.6, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warm));
		assertEquals(8.0, warm.getScore());
		assertTrue(fixture.store.incumbent(fixture.receiver).stream().noneMatch(plan -> plan == warm));

		fixture.store.beginWarmStartExecution(3);
		assertNull(warm.getScore());
		execute(fixture, 3, 13.0, 9.0, 0.0);
		assertEquals(MutableAfPhase.ADAPT, snapshot(fixture).phase());
		assertEquals(1, snapshot(fixture).dwell());
		assertFalse(MutableAfPlanUtils.isPendingEvaluation(warm));
		assertEquals(1, fixture.store.checkpointEvents().size(),
			"the first event belongs to AF=0.5; a trial itself is not a checkpoint");

		fixture.store.prepareReplanning(4);
		execute(fixture, 4, 13.0, 9.0, 0.0);
		fixture.store.prepareReplanning(5);
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(MutableAfPhase.ADAPT, snapshot(fixture).phase(),
			"a same-factor revisit must not perform a cross-factor warm-start trial");
		assertEquals(12 * 3600.0, selectedEnd(fixture.receiver));
		assertFalse(MutableAfPlanUtils.isPendingEvaluation(fixture.receiver.getSelectedPlan()));
		assertEquals(0, fixture.store.contextIndex(fixture.receiver.getSelectedPlan()).orElseThrow());
	}

	@Test
	void checkpointReplacementKeepsStableOverFallbackAndUsesConfiguredObjectiveWithinTier() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		execute(fixture, 1, 12.0, 6.0, 0.0);
		FactorCheckpoint firstStable = fixture.store.checkpoint(fixture.carrier.getId(), 0).orElseThrow();
		assertEquals(FactorMaturity.STABLE_CHECKPOINT, firstStable.maturity());

		// Finalization cannot downgrade an existing stable checkpoint with its visit fallback.
		fixture.store.prepareReplanning(90);
		FactorCheckpoint retained = fixture.store.checkpoint(fixture.carrier.getId(), 0).orElseThrow();
		assertEquals(FactorMaturity.STABLE_CHECKPOINT, retained.maturity());
		assertEquals(firstStable.sourceIteration(), retained.sourceIteration());
	}

	@Test
	void captureFailsFastForStaleCollaborationDataRouteMismatchAndDuplicateIteration() {
		Fixture stale = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		baseline(stale);
		stale.store.prepareReplanning(1);
		setScores(stale, 11.0, 6.0);
		setRouteProfile(stale);
		IllegalStateException staleError = assertThrows(IllegalStateException.class,
			() -> stale.store.observeIterationEnd(1));
		assertTrue(staleError.getMessage().contains("stamped iteration"));

		Fixture mismatch = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		baseline(mismatch);
		mismatch.store.prepareReplanning(1);
		mismatch.dataStore.reset(1);
		setScores(mismatch, 11.0, 6.0);
		mismatch.carrier.getSelectedPlan().getAttributes().putAttribute(
			MutableAfPlanUtils.CARRIER_ROUTE_PROFILE, "wrong-profile");
		IllegalStateException routeError = assertThrows(IllegalStateException.class,
			() -> mismatch.store.observeIterationEnd(1));
		assertTrue(routeError.getMessage().contains("route profile"));

		Fixture duplicate = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		baseline(duplicate);
		duplicate.store.prepareReplanning(1);
		execute(duplicate, 1, 11.0, 6.0, 0.0);
		assertThrows(IllegalStateException.class, () -> duplicate.store.observeIterationEnd(1));
	}

	@Test
	void boundedMemoryEvictsInferiorDormantFactorButKeepsLightweightStatistics() {
		Fixture fixture = fixture(0.5, 0.7, 0.1, 2, 1, 1, 0.05);
		fixture.carrier.getSelectedPlan().setJspritScore(55.0);
		CarrierPlan factorSix = MutableAfPlanUtils.copyCarrierPlan(
			fixture.carrier.getSelectedPlan(), false);
		CarrierAllocationFactor.set(factorSix, 0.6, fixture.config);
		fixture.carrier.addPlan(factorSix);
		fixture.config.setMinExplorationProbability(1.0);
		fixture.config.setMaxExplorationProbability(1.0);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		execute(fixture, 1, 14.0, 8.0, 0.0);
		fixture.store.prepareReplanning(2);
		assertSame(factorSix, fixture.carrier.getSelectedPlan());
		assertEquals(MutableAfPhase.WARM_START_TRIAL, snapshot(fixture).phase());
		fixture.store.beginWarmStartExecution(2);
		execute(fixture, 2, 11.0, 8.0, 0.0);
		fixture.store.prepareReplanning(3);
		execute(fixture, 3, 11.0, 8.0, 0.0);
		MatsimRandom.reset(0L);
		fixture.store.prepareReplanning(4);

		assertEquals(2, fixture.store.retainedFactorCount(fixture.carrier.getId()));
		assertEquals(0.7, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(List.of(0.5, 0.7), fixture.carrier.getPlans().stream()
			.map(CarrierAllocationFactor::require).sorted().toList());
		FactorSummary evicted = fixture.store.factorSummaries(fixture.carrier.getId()).get(1);
		assertEquals(FactorMaturity.EVICTED, evicted.maturity());
		assertFalse(evicted.retained());
		assertEquals(1, evicted.checkpoints());
		assertEquals(1.0, evicted.objectiveValue());
		assertEquals("EVICT_0.6", snapshot(fixture).evictionEvent());

		fixture.store.prepareReplanning(90);
		assertEquals(MutableAfPhase.FINAL_REVISIT, snapshot(fixture).phase());
		assertEquals(List.of(0, 1), snapshot(fixture).finalRevisitCandidateIndices(),
			"an evicted live plan must be reconstructable from its immutable checkpoint archive");
		assertEquals(List.of(0.5, 0.6), fixture.carrier.getPlans().stream()
			.map(CarrierAllocationFactor::require).sorted().toList());
		assertEquals(55.0, fixture.carrier.getPlans().stream()
			.filter(plan -> CarrierAllocationFactor.require(plan) == 0.6)
			.findFirst().orElseThrow().getJspritScore());
	}

	@Test
	void finalRevisitProbabilisticallyReevaluatesTopCheckpointsThenSelectsBestOnLastIteration() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		baseline(fixture);
		fixture.store.prepareReplanning(1);
		selectPlan(fixture, 11 * 3600.0, 0);
		execute(fixture, 1, 12.0, 6.0, 0.0);

		fixture.store.prepareReplanning(2);
		fixture.store.beginWarmStartExecution(2);
		execute(fixture, 2, 20.0, 8.0, 0.0);
		fixture.store.prepareReplanning(3);
		selectPlan(fixture, 12 * 3600.0, 1);
		execute(fixture, 3, 20.0, 8.0, 0.0);

		MatsimRandom.reset(4711L);
		fixture.store.prepareReplanning(90);
		assertEquals(MutableAfPhase.FINAL_REVISIT, snapshot(fixture).phase());
		assertEquals(List.of(0, 1), snapshot(fixture).finalRevisitCandidateIndices());
		assertEquals(2, fixture.carrier.getPlans().size());

		int highScoreSelections = 0;
		for (int iteration = 90; iteration < 100; iteration++) {
			double factor = CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan());
			if (factor == 0.6) {
				highScoreSelections++;
				assertEquals(12 * 3600.0, selectedEnd(fixture.receiver));
			} else {
				assertEquals(11 * 3600.0, selectedEnd(fixture.receiver));
			}
			fixture.store.beginFinalExecution(iteration);
			assertNull(fixture.carrier.getSelectedPlan().getScore());
			assertNull(fixture.receiver.getSelectedPlan().getScore());
			execute(fixture, iteration, factor == 0.6 ? 20.0 : 12.0,
				factor == 0.6 ? 8.0 : 6.0, 0.0);
			if (iteration < 99) {
				fixture.store.prepareReplanning(iteration + 1);
			}
		}
		assertTrue(highScoreSelections > 5,
			"score-weighted sampling should favor the higher-scoring checkpoint");
		assertEquals(10, snapshot(fixture).finalRevisitSelectionCount());

		fixture.store.prepareReplanning(100);
		assertEquals(MutableAfPhase.FINAL_SELECTION, snapshot(fixture).phase());
		assertEquals(0.6, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(12 * 3600.0, selectedEnd(fixture.receiver));
		assertEquals(20.0, snapshot(fixture).checkpointSelectionScore());
		fixture.store.beginFinalExecution(100);
		execute(fixture, 100, 25.0, 9.0, 0.0);
		assertEquals(25.0, snapshot(fixture).finalSelectionExecutedScore());
		assertEquals("FINAL_SELECTION_EXECUTED", snapshot(fixture).decision());
	}

	@Test
	void finalSelectionRetainsTheLatestEligibleRevisitAcrossConsecutiveUnsafeExecutions() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		fixture.carrier.getSelectedPlan().setJspritScore(41.0);
		baseline(fixture);
		setCoalition(fixture, true);
		fixture.store.prepareReplanning(1);
		fixture.carrier.getSelectedPlan().setJspritScore(42.0);
		execute(fixture, 1, 12.0, 6.0, 5.0);

		fixture.store.prepareReplanning(90);
		fixture.store.beginFinalExecution(90);
		fixture.carrier.getSelectedPlan().setJspritScore(43.0);
		execute(fixture, 90, 20.0, 7.0, 6.0);

		fixture.store.prepareReplanning(91);
		fixture.store.beginFinalExecution(91);
		fixture.carrier.getSelectedPlan().setJspritScore(91.0);
		execute(fixture, 91, 100.0, 4.0, 7.0);
		assertTrue(snapshot(fixture).decision().startsWith("FINAL_REVISIT_INELIGIBLE_"));

		fixture.store.prepareReplanning(92);
		fixture.store.beginFinalExecution(92);
		fixture.carrier.getSelectedPlan().setJspritScore(92.0);
		execute(fixture, 92, 200.0, 7.0, -5.0);
		assertTrue(snapshot(fixture).decision().startsWith("FINAL_REVISIT_INELIGIBLE_"));

		fixture.store.prepareReplanning(100);
		assertEquals(MutableAfPhase.FINAL_SELECTION, snapshot(fixture).phase());
		assertEquals(20.0, fixture.carrier.getSelectedPlan().getScore());
		assertEquals(43.0, fixture.carrier.getSelectedPlan().getJspritScore());
		assertEquals(7.0, fixture.receiver.getSelectedPlan().getScore());
		assertEquals(20.0, snapshot(fixture).checkpointSelectionScore());
		assertEquals(90, snapshot(fixture).checkpointSourceIteration());
		MutableAfLearningStore.CheckpointEvent finalEvent = fixture.store.checkpointEvents().getLast();
		assertTrue(finalEvent.finalSelection());
		assertTrue(finalEvent.participationFeasible());
		assertEquals(6.0, finalEvent.totalSurplus());
		assertEquals(90, finalEvent.sourceIteration());
	}

	@Test
	void finalSelectionExcludesNegativeSurplusCheckpointsAndRestoresBaselineMetadata() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		fixture.carrier.getSelectedPlan().setJspritScore(31.0);
		baseline(fixture);
		setCoalition(fixture, true);
		fixture.store.prepareReplanning(1);
		fixture.carrier.getSelectedPlan().setJspritScore(99.0);
		execute(fixture, 1, 12.0, 6.0, -0.1);

		FactorCheckpoint negativeSurplus = fixture.store.checkpoint(
			fixture.carrier.getId(), 0).orElseThrow();
		assertTrue(negativeSurplus.participationFeasible());
		assertEquals(-0.1, negativeSurplus.executedState().observation().totalSurplus(), 1e-12);

		fixture.store.prepareReplanning(90);
		assertEquals("FINAL_REVISIT_BASELINE_ONLY", snapshot(fixture).finalStatus());
		assertEquals(10.0, fixture.carrier.getSelectedPlan().getScore());
		assertEquals(31.0, fixture.carrier.getSelectedPlan().getJspritScore());
		fixture.store.beginFinalExecution(90);
		fixture.carrier.getSelectedPlan().setJspritScore(100.0);
		execute(fixture, 90, 100.0, 100.0, 50.0);
		fixture.store.prepareReplanning(100);
		assertEquals("NO_FEASIBLE_FACTOR_BASELINE_FALLBACK", snapshot(fixture).finalStatus());
		assertEquals(10.0, snapshot(fixture).checkpointSelectionScore());
		assertEquals(0, snapshot(fixture).checkpointSourceIteration());
		assertEquals(10.0, fixture.carrier.getSelectedPlan().getScore());
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(31.0, fixture.carrier.getSelectedPlan().getJspritScore());
		assertEquals("BASELINE", fixture.store.checkpointEvents().getLast().finalSelectionTier());
	}

	@Test
	void finalEligibilityUsesTheConfiguredNumericalSurplusBoundary() {
		assertTrue(MutableAfLearningStore.isFinalSelectionEligible(
			observationWithEligibility(-1e-9, true)));
		assertTrue(MutableAfLearningStore.isFinalSelectionEligible(
			observationWithEligibility(-5e-10, true)));
		assertFalse(MutableAfLearningStore.isFinalSelectionEligible(
			observationWithEligibility(-1.000001e-9, true)));
		assertFalse(MutableAfLearningStore.isFinalSelectionEligible(
			observationWithEligibility(1.0, false)));
		assertFalse(MutableAfLearningStore.isFinalSelectionEligible(
			observationWithEligibility(Double.NaN, true)));
	}

	@Test
	void initializationAndPublicViewsAreDefensiveAndRejectMalformedState() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		CarrierPlan duplicate = MutableAfPlanUtils.copyCarrierPlan(fixture.carrier.getSelectedPlan(), true);
		CarrierAllocationFactor.set(duplicate, 0.5, fixture.config);
		fixture.carrier.addPlan(duplicate);
		assertThrows(IllegalStateException.class, fixture.store::initialize);

		Fixture valid = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		valid.store.initialize();
		Map<Integer, FactorSummary> summaries = valid.store.factorSummaries(valid.carrier.getId());
		assertThrows(UnsupportedOperationException.class, summaries::clear);
		assertThrows(UnsupportedOperationException.class,
			() -> snapshot(valid).retainedFactorIndices().clear());
		assertThrows(IllegalArgumentException.class,
			() -> valid.store.snapshot(Id.create("missing", Carrier.class)));

		ReceiverPlan malformed = MutableAfPlanUtils.copyReceiverPlan(valid.receiver.getSelectedPlan(), false);
		malformed.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, "bad");
		assertThrows(IllegalStateException.class, () -> valid.store.contextIndex(malformed));
		ReceiverPlan withoutContext = MutableAfPlanUtils.copyReceiverPlan(
			valid.receiver.getSelectedPlan(), false);
		withoutContext.getAttributes().removeAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX);
		assertTrue(valid.store.contextIndex(withoutContext).isEmpty());
		Id<Receiver> unknownReceiver = Id.create("unknown", Receiver.class);
		assertEquals(MutableAfPhase.FINAL_SELECTION,
			valid.store.phaseForReceiver(unknownReceiver));
		assertThrows(IllegalArgumentException.class,
			() -> valid.store.activeFactorIndexForReceiver(unknownReceiver));
		assertFalse(valid.store.hasPendingWarmStartTrial());
		valid.store.beginWarmStartExecution(0);
		valid.store.recordRouteReplanned(valid.carrier.getId(), false);
		assertFalse(snapshot(valid).routeReplanned());
		valid.store.recordRouteReplanned(valid.carrier.getId(), true);
		valid.store.recordRouteReplanned(valid.carrier.getId(), false);
		assertTrue(snapshot(valid).routeReplanned());
	}

	@Test
	void initializationSuppliesMissingFactorAndRejectsMalformedFactorMemory() {
		Fixture missingSelectedFactor = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		missingSelectedFactor.carrier.getSelectedPlan().getAttributes()
			.removeAttribute(CarrierAllocationFactor.ATTRIBUTE_NAME);
		missingSelectedFactor.store.initialize();
		assertEquals(0.5, CarrierAllocationFactor.require(
			missingSelectedFactor.carrier.getSelectedPlan()));

		Fixture missingDormantFactor = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		CarrierPlan untagged = MutableAfPlanUtils.copyCarrierPlan(
			missingDormantFactor.carrier.getSelectedPlan(), false);
		untagged.getAttributes().removeAttribute(CarrierAllocationFactor.ATTRIBUTE_NAME);
		missingDormantFactor.carrier.addPlan(untagged);
		assertThrows(IllegalStateException.class, missingDormantFactor.store::initialize);

		Fixture overCapacity = fixture(0.5, 0.7, 0.1, 2, 2, 3, 0.05);
		for (double factor : List.of(0.6, 0.7)) {
			CarrierPlan plan = MutableAfPlanUtils.copyCarrierPlan(
				overCapacity.carrier.getSelectedPlan(), false);
			CarrierAllocationFactor.set(plan, factor, overCapacity.config);
			overCapacity.carrier.addPlan(plan);
		}
		assertThrows(IllegalStateException.class, overCapacity.store::initialize);

		Fixture wrongBaselinePhase = fixture(0.5, 0.6, 0.1, 2, 2, 3, 0.05);
		wrongBaselinePhase.store.prepareReplanning(1);
		assertThrows(IllegalStateException.class,
			() -> wrongBaselinePhase.store.observeIterationEnd(0));
	}

	@Test
	void costSavingsObservationUsesFullMinusEmptyAndRecordsSignedTransfer() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		baseline(fixture);
		setCoalition(fixture, true);
		fixture.store.prepareReplanning(1);
		execute(fixture, 1, 12.0, 6.0, 25.0);

		ExecutedJointObservation observation = fixture.store.recentObservations(
			fixture.carrier.getId()).getLast();
		assertEquals(25.0, observation.totalSurplus());
		assertEquals(-3.5, observation.signedTransfer());
		assertEquals(Set.of(fixture.receiver.getId()), observation.collaboratingReceivers());
		assertTrue(observation.participationFeasible());
	}

	@Test
	void activeCoalitionRequiresCurrentCharacteristicFunctionAndAppliedFactor() {
		Fixture missingScores = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		baseline(missingScores);
		setCoalition(missingScores, true);
		missingScores.store.prepareReplanning(1);
		missingScores.dataStore.reset(1);
		setScores(missingScores, 12.0, 6.0);
		setRouteProfile(missingScores);
		assertThrows(IllegalStateException.class,
			() -> missingScores.store.observeIterationEnd(1));

		Fixture missingFactor = fixture(0.5, 0.6, 0.1, 2, 1, 2, 0.05);
		baseline(missingFactor);
		setCoalition(missingFactor, true);
		missingFactor.store.prepareReplanning(1);
		missingFactor.dataStore.reset(1);
		setScores(missingFactor, 12.0, 6.0);
		setRouteProfile(missingFactor);
		MutableFreightCoalition coalition = missingFactor.coalitionManager
			.getMutableFreightCoalitions().getFirst();
		missingFactor.dataStore.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(), 100.0,
			Set.of(missingFactor.carrier.getId(), missingFactor.receiver.getId()), 110.0));
		assertThrows(IllegalStateException.class,
			() -> missingFactor.store.observeIterationEnd(1));
	}

	private static void baseline(Fixture fixture) {
		fixture.store.initialize();
		setRouteProfile(fixture);
		fixture.store.observeIterationEnd(0);
	}

	private static void execute(Fixture fixture, int iteration, double carrierScore,
			double receiverScore, double surplus) {
		fixture.dataStore.reset(iteration);
		setScores(fixture, carrierScore, receiverScore);
		setRouteProfile(fixture);
		if (!fixture.coalitionManager.getMutableFreightCoalitions().isEmpty()) {
			MutableFreightCoalition coalition = fixture.coalitionManager
				.getMutableFreightCoalitions().getFirst();
			fixture.dataStore.addSimulatedCoalitionScores(coalition, Map.of(
				Set.of(), 100.0,
				Set.of(fixture.carrier.getId(), fixture.receiver.getId()), 100.0 + surplus));
			fixture.dataStore.recordAppliedAllocationFactor(coalition,
				CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
			fixture.dataStore.recordDistributorPlayerTransfer(
				new CollaboratorKey(CollaboratorRole.CARRIER, fixture.carrier.getId()), -3.5);
		}
		fixture.store.observeIterationEnd(iteration);
	}

	private static void setCoalition(Fixture fixture, boolean active) {
		if (!active) {
			fixture.coalitionManager.setMutableFreightCoalitions(List.of());
			return;
		}
		MutableFreightCoalition coalition = new MutableFreightCoalition(
			CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(fixture.carrier));
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(fixture.receiver));
		fixture.coalitionManager.setMutableFreightCoalitions(List.of(coalition));
	}

	private static ReceiverPlan selectPlan(Fixture fixture, double end, int factorIndex) {
		ReceiverPlan plan = MutableAfPlanUtils.copyReceiverPlan(fixture.receiver.getSelectedPlan(), false);
		TimeWindow current = plan.getTimeWindows().getFirst();
		plan.getTimeWindows().set(0, TimeWindow.newInstance(current.getStart(), end));
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, factorIndex);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION, 1);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, false);
		fixture.receiver.addPlan(plan);
		fixture.receiver.setSelectedPlan(plan);
		return plan;
	}

	private static void setScores(Fixture fixture, double carrierScore, double receiverScore) {
		fixture.carrier.getSelectedPlan().setScore(carrierScore);
		fixture.receiver.getSelectedPlan().setScore(receiverScore);
	}

	private static ExecutedJointObservation observationWithEligibility(double surplus,
			boolean participationFeasible) {
		return new ExecutedJointObservation(1, 1, Id.create("carrier", Carrier.class), 0, 1,
			10.0, Map.of(), 0.0, Set.of(), surplus, 0.0, "receiver", "route", true,
			participationFeasible);
	}

	private static void setRouteProfile(Fixture fixture) {
		fixture.carrier.getSelectedPlan().getAttributes().putAttribute(
			MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			MutableAfPlanUtils.selectedReceiverProfile(fixture.carrier, List.of(fixture.receiver)));
	}

	private static double selectedEnd(Receiver receiver) {
		return receiver.getSelectedPlan().getTimeWindows().getFirst().getEnd();
	}

	private static double checkpointEnd(FactorCheckpoint checkpoint) {
		return checkpoint.executedState().selectedReceiverPlans().values().iterator().next()
			.getTimeWindows().getFirst().getEnd();
	}

	private static MutableAfLearningStore.CarrierSnapshot snapshot(Fixture fixture) {
		return fixture.store.snapshot(fixture.carrier.getId());
	}

	private static Fixture fixture(double min, double max, double step, int factorCap,
			int stabilityWindow, int maxDwell, double tolerance) {
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		mutableConfig.setMinAllocationFactor(min);
		mutableConfig.setMaxAllocationFactor(max);
		mutableConfig.setAllocationFactorStep(step);
		mutableConfig.setInitialAllocationFactor(min);
		mutableConfig.setMaxFactorPlans(factorCap);
		mutableConfig.setNewFactorMinDwell(1);
		mutableConfig.setRevisitFactorMinDwell(1);
		mutableConfig.setStabilityWindow(stabilityWindow);
		mutableConfig.setMaxAdaptDwell(maxDwell);
		mutableConfig.setEvaluationWindow(1);
		Config matsimConfig = ConfigUtils.createConfig(mutableConfig);
		matsimConfig.controller().setLastIteration(100);
		Scenario scenario = ScenarioUtils.createScenario(matsimConfig);

		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		carrier.clearPlans();
		CarrierPlan initialPlan = new CarrierPlan(carrier, new ArrayList<>());
		initialPlan.setScore(10.0);
		CarrierAllocationFactor.set(initialPlan, min, mutableConfig);
		carrier.addPlan(initialPlan);
		carrier.setSelectedPlan(initialPlan);
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
		return new Fixture(mutableConfig, scenario, carrier, receiver, dataStore,
			coalitionManager, store);
	}

	private record Fixture(MutableAllocationFactorConfigGroup config, Scenario scenario,
			Carrier carrier, Receiver receiver, CollaborationDataStore dataStore,
			FreightCoalitionManager coalitionManager, MutableAfLearningStore store) {
	}
}
