package org.matsim.contrib.freightcollaboration.learning;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
	void initialFactorDwellsThenCreatesAValidatedCheckpointAndSwitchesOnce() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		MutableAfLearningStore store = fixture.store;
		store.initialize();

		assertEquals(MutableAfPhase.BASELINE, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		store.observeIterationEnd(0);

		store.prepareReplanning(1);
		assertEquals(MutableAfPhase.ADAPT, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()),
			"iteration 1 must retain the configured initial offer");

		selectRelaxedPlan(fixture.receiver, 12 * 3600.0, 8.0, 0);
		setScores(fixture, 12.0, 8.0);
		store.observeIterationEnd(1);
		store.prepareReplanning(2);
		setScores(fixture, 12.0, 8.0);
		store.observeIterationEnd(2);
		assertEquals(MutableAfPhase.EVALUATE, store.snapshot(fixture.carrier.getId()).phase());

		store.prepareReplanning(3);
		assertEquals(12 * 3600.0, selectedEnd(fixture.receiver));
		setScores(fixture, 12.0, 8.0);
		store.observeIterationEnd(3);
		assertEquals(MutableAfPhase.SWITCH_PENDING, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(FactorMaturity.MATURE_STABLE,
			store.factorSummaries(fixture.carrier.getId()).get(0).maturity());
		assertEquals(1, store.checkpointEvents().size());

		CarrierPlan initialPlan = fixture.carrier.getSelectedPlan();
		store.prepareReplanning(4);
		assertEquals(MutableAfPhase.WARM_START_TRIAL, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(0.6, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertNotSame(initialPlan, fixture.carrier.getSelectedPlan());
		assertEquals(2, store.retainedFactorCount(fixture.carrier.getId()));
		assertEquals(12 * 3600.0, selectedEnd(fixture.receiver),
			"an unseen AF must warm-start from the outgoing TW response");
		ReceiverPlan warmStart = fixture.receiver.getSelectedPlan();
		assertEquals(8.0, warmStart.getScore());
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		assertEquals(0, warmStart.getAttributes()
			.getAttribute(MutableAfPlanUtils.RECEIVER_SOURCE_FACTOR_INDEX));
		assertNotSame(warmStart, store.incumbent(fixture.receiver).orElseThrow(),
			"the compatibility score must not make a pending plan the incumbent");
		assertTrue(fixture.receiver.getPlans().stream().anyMatch(store::isOutsideOption));
		assertTrue(fixture.receiver.getPlans().stream().allMatch(plan ->
			store.contextIndex(plan).orElseThrow() == 1));
		assertTrue(store.snapshot(fixture.carrier.getId()).warmStartTrial());
		assertEquals(0, store.snapshot(fixture.carrier.getId()).warmStartSourceFactorIndex());
		assertEquals("WARM_START_EXPLORE_UNSEEN_TO_0.6",
			store.snapshot(fixture.carrier.getId()).decision());

		store.beginWarmStartExecution(4);
		assertNull(warmStart.getScore(), "temporary score is cleared only immediately before execution");
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		setScores(fixture, 13.0, 9.0);
		store.observeIterationEnd(4);
		assertEquals(MutableAfPhase.ADAPT, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(1, store.snapshot(fixture.carrier.getId()).dwell());
		assertEquals(0, store.snapshot(fixture.carrier.getId()).stableStreak());
		assertFalse(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		assertEquals(9.0, warmStart.getScore());
		assertEquals(1, store.checkpointEvents().size(),
			"a warm-start trial is an observation, not a checkpoint");

		store.prepareReplanning(90);
		MutableAfLearningStore.CarrierSnapshot finalized = store.snapshot(fixture.carrier.getId());
		assertEquals(MutableAfPhase.FINAL_VALIDATION, finalized.phase());
		assertEquals("SELECTED_FACTOR", finalized.finalStatus());
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(12 * 3600.0, selectedEnd(fixture.receiver));
		assertEquals("FINAL_SELECTION", store.checkpointEvents().getLast().eventType());
		store.prepareReplanning(91);
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()),
			"final validation must not create another factor");
	}

	@Test
	void revisitingAFRestoresItsOwnReceiverMemoryInsteadOfCrossComparingScores() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		MutableAfLearningStore store = fixture.store;
		store.initialize();
		store.observeIterationEnd(0);
		completeStableVisit(fixture, 1, 10 * 3600.0, 12.0, 8.0);
		store.prepareReplanning(4); // switch from 0.5 to 0.6

		ReceiverPlan factorSixPlan = fixture.receiver.getSelectedPlan();
		completeStableVisit(fixture, 4, 14 * 3600.0, 13.0, 50.0);
		store.prepareReplanning(7); // only adjacent factor is 0.5

		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(MutableAfPhase.WARM_START_TRIAL, store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(10 * 3600.0, selectedEnd(fixture.receiver));
		assertFalse(fixture.receiver.getPlans().contains(factorSixPlan));
		assertTrue(fixture.receiver.getPlans().stream().allMatch(plan ->
			store.contextIndex(plan).orElseThrow() == 0));
		assertTrue(fixture.receiver.getPlans().stream().noneMatch(plan -> Double.valueOf(50.0).equals(plan.getScore())),
			"a score learned under AF=0.6 must not enter AF=0.5 selection");
	}

	@Test
	void unseenFactorCopiesOnlyTheSelectedBehaviorAndPreservesTheCompleteOutgoingArchive() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);

		ReceiverPlan alternative = MutableAfPlanUtils.copyReceiverPlan(
			fixture.receiver.getSelectedPlan(), false);
		alternative.getTimeWindows().set(0, TimeWindow.newInstance(8 * 3600.0, 11 * 3600.0));
		alternative.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, 0);
		alternative.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION, 1);
		alternative.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, false);
		alternative.setScore(6.0);
		fixture.receiver.addPlan(alternative);
		completeStableVisit(fixture, 1, 12 * 3600.0, 12.0, 8.0);
		List<ReceiverPlan> outgoingPlans = List.copyOf(fixture.receiver.getPlans());
		List<Double> outgoingScores = outgoingPlans.stream().map(ReceiverPlan::getScore).toList();
		ReceiverPlan outgoingSelected = fixture.receiver.getSelectedPlan();

		fixture.store.prepareReplanning(4);

		assertEquals(2, fixture.receiver.getPlans().size(),
			"an unseen context contains one warm-start behavior and one outside option, not the whole archive");
		assertNotSame(outgoingSelected, fixture.receiver.getSelectedPlan());
		assertEquals(MutableAfPlanUtils.receiverPlanSignature(outgoingSelected),
			MutableAfPlanUtils.receiverPlanSignature(fixture.receiver.getSelectedPlan()));

		completeStableVisit(fixture, 4, 12 * 3600.0, 13.0, 9.0);
		fixture.store.prepareReplanning(7);

		assertEquals(outgoingPlans.size(), fixture.receiver.getPlans().size());
		for (int index = 0; index < outgoingPlans.size(); index++) {
			assertSame(outgoingPlans.get(index), fixture.receiver.getPlans().get(index),
				"revisit must restore the target AF's archived plan objects");
			assertEquals(outgoingScores.get(index), fixture.receiver.getPlans().get(index).getScore());
		}
		assertSame(outgoingSelected, fixture.receiver.getSelectedPlan());
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(outgoingSelected));
		assertEquals(0, outgoingSelected.getAttributes()
			.getAttribute(MutableAfPlanUtils.RECEIVER_SOURCE_FACTOR_INDEX));
	}

	@Test
	void behaviorIdenticalToOutsideOptionIsDeduplicatedButStillGetsAFSpecificTrial() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);
		completeStableVisit(fixture, 1, 10 * 3600.0, 12.0, 8.0);

		fixture.store.prepareReplanning(4);

		assertEquals(1, fixture.receiver.getPlans().size());
		ReceiverPlan selected = fixture.receiver.getSelectedPlan();
		assertTrue(fixture.store.isOutsideOption(selected));
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(selected));
		assertEquals(5.0, selected.getScore(),
			"the protected outside plan carries the immutable iteration-0 baseline until BeforeMobsim");
		assertEquals(1, fixture.store.contextIndex(selected).orElseThrow());
		assertEquals(0, selected.getAttributes()
			.getAttribute(MutableAfPlanUtils.RECEIVER_SOURCE_FACTOR_INDEX));
	}

	@Test
	void switchRejectsMissingOrNonFiniteOutgoingScoreInsteadOfInventingACompatibilityValue() {
		Fixture missing = fixture(0.5, 0.6, 0.1, 2);
		missing.store.initialize();
		missing.store.observeIterationEnd(0);
		completeStableVisit(missing, 1, 12 * 3600.0, 12.0, 8.0);
		missing.receiver.getSelectedPlan().setScore(null);
		IllegalStateException missingScore = assertThrows(IllegalStateException.class,
			() -> missing.store.prepareReplanning(4));
		assertTrue(missingScore.getMessage().contains("outgoing selected plan has no finite score"));

		Fixture nonFinite = fixture(0.5, 0.6, 0.1, 2);
		nonFinite.store.initialize();
		nonFinite.store.observeIterationEnd(0);
		completeStableVisit(nonFinite, 1, 12 * 3600.0, 12.0, 8.0);
		nonFinite.receiver.getSelectedPlan().setScore(Double.NaN);
		IllegalStateException nanScore = assertThrows(IllegalStateException.class,
			() -> nonFinite.store.prepareReplanning(4));
		assertTrue(nanScore.getMessage().contains("outgoing selected plan has no finite score"));
	}

	@Test
	void warmStartTrialFailsClearlyWhenScoringDoesNotReplaceTheTemporaryScore() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);
		completeStableVisit(fixture, 1, 12 * 3600.0, 12.0, 8.0);
		fixture.store.prepareReplanning(4);
		ReceiverPlan selected = fixture.receiver.getSelectedPlan();

		fixture.store.beginWarmStartExecution(4);
		fixture.carrier.getSelectedPlan().setScore(13.0);
		IllegalStateException error = assertThrows(IllegalStateException.class,
			() -> fixture.store.observeIterationEnd(4));

		assertTrue(error.getMessage().contains(
			"Warm-start trial completed without a finite Receiver score"));
		assertNull(selected.getScore());
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(selected),
			"failed validation must not turn an unscored plan into eligible factor experience");
		assertEquals(1, fixture.store.checkpointEvents().size());
	}

	@Test
	void finalizationFallsBackToBaselineWhenNoMatureFactorBenefitsTheCarrier() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);
		completeStableVisit(fixture, 1, 12 * 3600.0, 9.0, 8.0);

		fixture.store.prepareReplanning(90);

		MutableAfLearningStore.CarrierSnapshot snapshot =
			fixture.store.snapshot(fixture.carrier.getId());
		assertEquals(MutableAfPhase.FINAL_VALIDATION, snapshot.phase());
		assertEquals("NO_FEASIBLE_MATURE_FACTOR", snapshot.finalStatus());
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(10 * 3600.0, selectedEnd(fixture.receiver));
	}

	@Test
	void boundedMemoryEvictsTheInferiorDormantOrOutgoingFactorButKeepsLightStatistics() {
		Fixture fixture = fixture(0.5, 0.7, 0.1, 2);
		CarrierPlan factorSix = MutableAfPlanUtils.copyCarrierPlan(
			fixture.carrier.getSelectedPlan(), false);
		CarrierAllocationFactor.set(factorSix, 0.6, fixture.config);
		fixture.carrier.addPlan(factorSix);
		fixture.config.setMinExplorationProbability(1.0);
		fixture.config.setMaxExplorationProbability(1.0);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);

		completeStableVisit(fixture, 1, 11 * 3600.0, 12.0, 8.0);
		fixture.store.prepareReplanning(4);
		assertSame(factorSix, fixture.carrier.getSelectedPlan());
		completeStableVisit(fixture, 4, 11 * 3600.0, 11.0, 8.0);

		MatsimRandom.reset(0L);
		fixture.store.prepareReplanning(7);

		assertEquals(2, fixture.store.retainedFactorCount(fixture.carrier.getId()));
		assertEquals(0.7, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(List.of(0.5, 0.7), fixture.carrier.getPlans().stream()
			.map(CarrierAllocationFactor::require).sorted().toList());
		FactorSummary evicted = fixture.store.factorSummaries(fixture.carrier.getId()).get(1);
		assertEquals(FactorMaturity.EVICTED, evicted.maturity());
		assertFalse(evicted.retained());
		assertEquals(1, evicted.matureEvaluations());
		assertEquals(11.0, evicted.carrierScoreMean());
		assertEquals("EVICT_0.6", fixture.store.snapshot(fixture.carrier.getId()).evictionEvent());
		assertEquals("EVICTION", fixture.store.checkpointEvents().getLast().eventType());
	}

	@Test
	void initializationRejectsOverlappingCarrierContextsAndDuplicateFactorPlans() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		CarrierPlan duplicate = MutableAfPlanUtils.copyCarrierPlan(fixture.carrier.getSelectedPlan(), true);
		CarrierAllocationFactor.set(duplicate, 0.5, fixture.config);
		fixture.carrier.addPlan(duplicate);
		assertThrows(IllegalStateException.class, fixture.store::initialize);

		Fixture twoCarrierFixture = fixture(0.5, 0.6, 0.1, 2);
		ReceiverPlan plan = twoCarrierFixture.receiver.getSelectedPlan();
		ReceiverPlan secondCarrierOrder = FreightCollaborationTestFixtures.receiverPlan(
			twoCarrierFixture.receiver, "other", 600.0, plan.getTimeWindows().getFirst());
		ReceiverPlan overlapping = ReceiverPlan.Builder.newInstance(twoCarrierFixture.receiver, true)
			.addTimeWindow(plan.getTimeWindows().getFirst())
			.addReceiverOrder(plan.getReceiverOrders().iterator().next())
			.addReceiverOrder(secondCarrierOrder.getReceiverOrders().iterator().next())
			.build();
		overlapping.setScore(5.0);
		twoCarrierFixture.receiver.addPlan(overlapping);
		twoCarrierFixture.receiver.setSelectedPlan(overlapping);
		assertThrows(IllegalStateException.class, twoCarrierFixture.store::initialize);
	}

	@Test
	void snapshotsAndSummariesAreDefensive() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();

		Map<Integer, FactorSummary> summaries = fixture.store.factorSummaries(fixture.carrier.getId());
		assertThrows(UnsupportedOperationException.class, () -> summaries.clear());
		assertThrows(UnsupportedOperationException.class,
			() -> fixture.store.snapshot(fixture.carrier.getId()).retainedFactorIndices().clear());
		assertEquals(1, fixture.carrier.getPlans().size());
		assertSame(fixture.carrier.getSelectedPlan(), fixture.carrier.getPlans().getFirst());
	}

	@Test
	void maxDwellProducesAnExplicitUnstableCheckpoint() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);

		fixture.store.prepareReplanning(1);
		setScores(fixture, 11.0, 7.0);
		fixture.store.observeIterationEnd(1);
		fixture.store.prepareReplanning(2);
		selectRelaxedPlan(fixture.receiver, 13 * 3600.0, 8.0, 0);
		setScores(fixture, 12.0, 8.0);
		fixture.store.observeIterationEnd(2);

		assertEquals(MutableAfPhase.EVALUATE,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertEquals("START_FORCED_EVALUATION",
			fixture.store.snapshot(fixture.carrier.getId()).decision());

		fixture.store.prepareReplanning(3);
		setScores(fixture, 12.0, 8.0);
		fixture.store.observeIterationEnd(3);

		assertEquals(FactorMaturity.VALIDATED_UNSTABLE,
			fixture.store.factorSummaries(fixture.carrier.getId()).get(0).maturity());
		MutableAfLearningStore.CheckpointEvent event = fixture.store.checkpointEvents().getLast();
		assertEquals(FactorMaturity.VALIDATED_UNSTABLE, event.maturity());
		assertFalse(event.finalSelection());
	}

	@Test
	void anUnstableRevisitRollsBackItsMatureCheckpointBeforeSwitching() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);
		completeStableVisit(fixture, 1, 11 * 3600.0, 14.0, 8.0);
		fixture.store.prepareReplanning(4);
		completeStableVisit(fixture, 4, 11 * 3600.0, 12.0, 8.0);

		fixture.config.setMinExplorationProbability(0.0);
		fixture.config.setMaxExplorationProbability(0.0);
		fixture.store.prepareReplanning(7);
		assertEquals(0.5, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertTrue(fixture.store.snapshot(fixture.carrier.getId()).decision()
			.startsWith("WARM_START_EXPLOIT_REVISIT_TO_"));

		fixture.store.beginWarmStartExecution(7);
		setScores(fixture, 13.0, 9.0);
		fixture.store.observeIterationEnd(7);
		fixture.store.prepareReplanning(8);
		selectRelaxedPlan(fixture.receiver, 15 * 3600.0, 10.0, 0);
		setScores(fixture, 11.0, 10.0);
		fixture.store.observeIterationEnd(8);
		assertEquals(MutableAfPhase.EVALUATE,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		fixture.store.prepareReplanning(9);
		setScores(fixture, 11.0, 10.0);
		fixture.store.observeIterationEnd(9);

		fixture.store.prepareReplanning(10);
		assertEquals(0.6, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertEquals(FactorMaturity.MATURE_STABLE,
			fixture.store.factorSummaries(fixture.carrier.getId()).get(0).maturity(),
			"an unstable revisit must not replace a previously mature factor record");
		assertEquals(14.0,
			fixture.store.factorSummaries(fixture.carrier.getId()).get(0).carrierScoreMean());
	}

	@Test
	void observationsIncludeCoalitionSurplusAndSignedCarrierTransfer() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		MutableFreightCoalition coalition = new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(fixture.carrier));
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(fixture.receiver));
		fixture.coalitionManager.setMutableFreightCoalitions(List.of(coalition));
		fixture.dataStore.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(fixture.carrier.getId()), 4.0,
			Set.of(fixture.carrier.getId(), fixture.receiver.getId()), 25.0));
		fixture.dataStore.recordDistributorPlayerTransfer(
			new org.matsim.contrib.freightcollaboration.CollaboratorKey(
				CollaboratorRole.CARRIER, fixture.carrier.getId()), -3.5);

		fixture.store.initialize();
		fixture.store.observeIterationEnd(0);
		MutableAfLearningStore.CarrierSnapshot snapshot = fixture.store.snapshot(fixture.carrier.getId());
		assertTrue(snapshot.activeCoalition());
		assertEquals(1, snapshot.receiverCount());
		assertEquals(25.0, snapshot.totalSurplus());
		assertEquals(-3.5, snapshot.signedTransfer());
		assertNotNull(snapshot.incumbentProfile());
	}

	@Test
	void initializationAddsMissingOutsideOptionAndTrimsExcessPlans() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		fixture.config.setMaxReceiverPlansPerFactor(2);
		ReceiverPlan relaxed = MutableAfPlanUtils.copyReceiverPlan(fixture.receiver.getSelectedPlan(), false);
		relaxed.getTimeWindows().set(0, TimeWindow.newInstance(8 * 3600.0, 12 * 3600.0));
		relaxed.setScore(7.0);
		ReceiverPlan excess = MutableAfPlanUtils.copyReceiverPlan(relaxed, false);
		excess.getTimeWindows().set(0, TimeWindow.newInstance(8 * 3600.0, 13 * 3600.0));
		excess.setScore(6.0);
		fixture.receiver.getPlans().clear();
		fixture.receiver.addPlan(relaxed);
		fixture.receiver.addPlan(excess);
		fixture.receiver.setSelectedPlan(relaxed);

		fixture.store.initialize();

		assertEquals(2, fixture.receiver.getPlans().size());
		assertSame(relaxed, fixture.receiver.getSelectedPlan());
		assertEquals(1, fixture.receiver.getPlans().stream().filter(fixture.store::isOutsideOption).count());
		ReceiverPlan malformed = MutableAfPlanUtils.copyReceiverPlan(relaxed, false);
		malformed.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, "not-a-number");
		assertThrows(IllegalStateException.class, () -> fixture.store.contextIndex(malformed));
		assertThrows(IllegalArgumentException.class,
			() -> fixture.store.snapshot(Id.create("missing", Carrier.class)));
	}

	@Test
	void initializationAndLookupFailFastForMalformedRuntimeState() {
		Fixture missingOriginal = fixture(0.5, 0.6, 0.1, 2);
		MutableAfLearningStore withoutOriginal = new MutableAfLearningStore(
			missingOriginal.scenario, missingOriginal.config, new CollaborationDataStore(Map.of()),
			missingOriginal.coalitionManager);
		assertThrows(IllegalStateException.class, withoutOriginal::initialize);

		Fixture missingCarrier = fixture(0.5, 0.6, 0.1, 2);
		ReceiverPlan wrongOwner = FreightCollaborationTestFixtures.receiverPlan(missingCarrier.receiver,
			"missing-carrier", 600.0, TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		wrongOwner.setScore(5.0);
		missingCarrier.receiver.addPlan(wrongOwner);
		missingCarrier.receiver.setSelectedPlan(wrongOwner);
		assertThrows(IllegalStateException.class, missingCarrier.store::initialize);

		Fixture noOrders = fixture(0.5, 0.6, 0.1, 2);
		ReceiverPlan orderless = ReceiverPlan.Builder.newInstance(noOrders.receiver, true)
			.addTimeWindow(TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0)).build();
		orderless.setScore(5.0);
		noOrders.receiver.addPlan(orderless);
		noOrders.receiver.setSelectedPlan(orderless);
		assertThrows(IllegalStateException.class, noOrders.store::initialize);

		Fixture tooManyFactors = fixture(0.5, 0.7, 0.1, 2);
		for (double factor : List.of(0.6, 0.7)) {
			CarrierPlan plan = MutableAfPlanUtils.copyCarrierPlan(tooManyFactors.carrier.getSelectedPlan(), false);
			CarrierAllocationFactor.set(plan, factor, tooManyFactors.config);
			tooManyFactors.carrier.addPlan(plan);
		}
		assertThrows(IllegalStateException.class, tooManyFactors.store::initialize);
	}

	@Test
	void storeInitializesMissingFactorAndReportsUnownedReceiversAndRouteUpdates() {
		Fixture fixture = fixture(0.5, 0.6, 0.1, 2);
		CarrierPlan planWithoutFactor = new CarrierPlan(fixture.carrier, new java.util.ArrayList<>());
		planWithoutFactor.setScore(10.0);
		fixture.carrier.clearPlans();
		fixture.carrier.addPlan(planWithoutFactor);
		fixture.carrier.setSelectedPlan(planWithoutFactor);

		fixture.store.initialize();
		assertEquals(0.5, CarrierAllocationFactor.require(planWithoutFactor));
		fixture.store.recordRouteReplanned(fixture.carrier.getId(), false);
		fixture.store.recordRouteReplanned(fixture.carrier.getId(), true);
		assertTrue(fixture.store.snapshot(fixture.carrier.getId()).routeReplanned());
		assertEquals(MutableAfPhase.FINAL_VALIDATION,
			fixture.store.phaseForReceiver(Id.create("unowned", Receiver.class)));
		assertThrows(IllegalArgumentException.class,
			() -> fixture.store.activeFactorIndexForReceiver(Id.create("unowned", Receiver.class)));
	}

	private static void completeStableVisit(Fixture fixture, int firstAdaptIteration, double twEnd,
			double carrierScore, double receiverScore) {
		MutableAfLearningStore store = fixture.store;
		if (store.snapshot(fixture.carrier.getId()).phase() == MutableAfPhase.BASELINE) {
			store.prepareReplanning(firstAdaptIteration);
		}
		if (store.snapshot(fixture.carrier.getId()).phase() == MutableAfPhase.WARM_START_TRIAL) {
			assertNotNull(fixture.receiver.getSelectedPlan().getScore());
			assertTrue(MutableAfPlanUtils.isPendingEvaluation(fixture.receiver.getSelectedPlan()));
			store.beginWarmStartExecution(firstAdaptIteration);
			setScores(fixture, carrierScore, receiverScore);
			store.observeIterationEnd(firstAdaptIteration);
			assertEquals(MutableAfPhase.ADAPT, store.snapshot(fixture.carrier.getId()).phase());
			store.prepareReplanning(firstAdaptIteration + 1);
			if (selectedEnd(fixture.receiver) != twEnd) {
				selectRelaxedPlan(fixture.receiver, twEnd, receiverScore,
					store.activeFactorIndexForReceiver(fixture.receiver.getId()));
			}
			setScores(fixture, carrierScore, receiverScore);
			store.observeIterationEnd(firstAdaptIteration + 1);
			assertEquals(MutableAfPhase.EVALUATE, store.snapshot(fixture.carrier.getId()).phase());
			store.prepareReplanning(firstAdaptIteration + 2);
			setScores(fixture, carrierScore, receiverScore);
			store.observeIterationEnd(firstAdaptIteration + 2);
			assertEquals(MutableAfPhase.SWITCH_PENDING, store.snapshot(fixture.carrier.getId()).phase());
			return;
		}
		if (selectedEnd(fixture.receiver) != twEnd) {
			selectRelaxedPlan(fixture.receiver, twEnd, receiverScore,
				store.activeFactorIndexForReceiver(fixture.receiver.getId()));
		}
		setScores(fixture, carrierScore, receiverScore);
		store.observeIterationEnd(firstAdaptIteration);
		store.prepareReplanning(firstAdaptIteration + 1);
		setScores(fixture, carrierScore, receiverScore);
		store.observeIterationEnd(firstAdaptIteration + 1);
		assertEquals(MutableAfPhase.EVALUATE, store.snapshot(fixture.carrier.getId()).phase());
		store.prepareReplanning(firstAdaptIteration + 2);
		setScores(fixture, carrierScore, receiverScore);
		store.observeIterationEnd(firstAdaptIteration + 2);
		assertEquals(MutableAfPhase.SWITCH_PENDING, store.snapshot(fixture.carrier.getId()).phase());
	}

	private static void selectRelaxedPlan(Receiver receiver, double end, double score, int factorIndex) {
		ReceiverPlan plan = MutableAfPlanUtils.copyReceiverPlan(receiver.getSelectedPlan(), false);
		TimeWindow current = plan.getTimeWindows().getFirst();
		plan.getTimeWindows().set(0, TimeWindow.newInstance(current.getStart(), end));
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, factorIndex);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION, 1);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, false);
		plan.setScore(score);
		receiver.addPlan(plan);
		receiver.setSelectedPlan(plan);
	}

	private static void setScores(Fixture fixture, double carrierScore, double receiverScore) {
		fixture.carrier.getSelectedPlan().setScore(carrierScore);
		fixture.receiver.getSelectedPlan().setScore(receiverScore);
	}

	private static double selectedEnd(Receiver receiver) {
		return receiver.getSelectedPlan().getTimeWindows().getFirst().getEnd();
	}

	private static Fixture fixture(double min, double max, double step, int factorCap) {
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		mutableConfig.setMinAllocationFactor(min);
		mutableConfig.setMaxAllocationFactor(max);
		mutableConfig.setAllocationFactorStep(step);
		mutableConfig.setInitialAllocationFactor(min);
		mutableConfig.setMaxFactorPlans(factorCap);
		mutableConfig.setNewFactorMinDwell(1);
		mutableConfig.setRevisitFactorMinDwell(1);
		mutableConfig.setStabilityWindow(1);
		mutableConfig.setMaxAdaptDwell(2);
		mutableConfig.setEvaluationWindow(1);
		Config matsimConfig = ConfigUtils.createConfig(mutableConfig);
		matsimConfig.controller().setLastIteration(100);
		Scenario scenario = ScenarioUtils.createScenario(matsimConfig);

		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		carrier.clearPlans();
		CarrierPlan initialPlan = new CarrierPlan(carrier, new java.util.ArrayList<>());
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
		return new Fixture(mutableConfig, scenario, carrier, receiver, dataStore, coalitionManager, store);
	}

	private record Fixture(MutableAllocationFactorConfigGroup config, Scenario scenario,
			Carrier carrier, Receiver receiver, CollaborationDataStore dataStore,
			FreightCoalitionManager coalitionManager, MutableAfLearningStore store) {
	}
}
