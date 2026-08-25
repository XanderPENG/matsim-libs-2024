package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPhase;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.contrib.freightcollaboration.strategy.MutableAfReceiverStrategyManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverConfigGroup;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-component regression for the score-null crash at the first mutable-AF switch. */
class MutableAfWarmStartTrialLifecycleIT {

	@Test
	void firstFactorSwitchExecutesOneFrozenTrialBeforeReceiverAdaptation() {
		Fixture fixture = fixture();
		assertEquals(200.0, new MutableAfReplanningCoordinator(fixture.store).priority());
		assertEquals(100.0, new MutableAfLearningListener(fixture.store).priority());
		advanceInitialFactorToSwitch(fixture);
		fixture.store.prepareReplanning(4);

		ReceiverPlan warmStart = fixture.receiver.getSelectedPlan();
		CarrierPlan active = fixture.carrier.getSelectedPlan();
		assertEquals(0.6, CarrierAllocationFactor.require(active));
		assertEquals(MutableAfPhase.WARM_START_TRIAL,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		assertTrue(Double.isFinite(warmStart.getScore()));
		double valueReadByDefaultReceiverListener = warmStart.getScore();
		assertTrue(Double.isFinite(valueReadByDefaultReceiverListener));

		int planCount = fixture.receiver.getPlans().size();
		double twEnd = warmStart.getTimeWindows().getFirst().getEnd();
		fixture.receiverStrategy.run(List.of(fixture.receiver), 4, null);
		assertSame(warmStart, fixture.receiver.getSelectedPlan());
		assertEquals(planCount, fixture.receiver.getPlans().size());
		assertEquals(twEnd, fixture.receiver.getSelectedPlan().getTimeWindows().getFirst().getEnd());

		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener routeListener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				fixture.store, (carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));
		routeListener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 4, false));
		assertNull(warmStart.getScore());
		assertEquals(0, solves.get(),
			"an unchanged Receiver profile may reuse the copied route during the real trial");
		assertEquals(12.0, fixture.initialPlan.getScore(),
			"the dormant source-factor plan must remain untouched");

		fixture.dataStore.reset(4);
		fixture.dataStore.addSimulatedCoalitionScores(fixture.coalition, Map.of(
			Set.of(), 10.0, Set.of(fixture.receiver.getId()), 20.0));
		new AllocationModelShapleyValue(fixture.dataStore,
			ignored -> CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan(), fixture.mutableConfig))
			.allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(0.6, fixture.dataStore.getAppliedAllocationFactors().get(fixture.coalition), 1e-12);
		assertEquals(6.0, fixture.dataStore.getAllocatedValue(
			CollaboratorRole.RECEIVER, fixture.receiver.getId()), 1e-12);
		active.setScore(13.0);
		warmStart.setScore(9.0);
		fixture.store.observeIterationEnd(4);

		assertFalse(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		assertEquals(9.0, warmStart.getScore());
		assertEquals(MutableAfPhase.ADAPT, fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(1, fixture.store.snapshot(fixture.carrier.getId()).dwell());
		assertEquals(1, fixture.store.snapshot(fixture.carrier.getId()).stabilityWindowSize());
		MutableAfLearningStore.CarrierSnapshot trialRow = fixture.store.snapshot(fixture.carrier.getId());
		assertEquals("WARM_START_TRIAL_COMPLETE", trialRow.decision());
		assertTrue(trialRow.warmStartTrial());
		assertEquals(0, trialRow.warmStartSourceFactorIndex());
		assertTrue(trialRow.warmStartScoreClearedBeforeMobsim());

		fixture.store.prepareReplanning(5);
		MutableAfLearningStore.CarrierSnapshot nextRow = fixture.store.snapshot(fixture.carrier.getId());
		assertEquals("NO_DECISION", nextRow.decision());
		assertFalse(nextRow.warmStartTrial());
		assertNull(nextRow.warmStartSourceFactorIndex());
		assertFalse(nextRow.warmStartScoreClearedBeforeMobsim());
	}

	private static Fixture fixture() {
		FreightCollaborationConfigGroup freightConfig = new FreightCollaborationConfigGroup();
		freightConfig.setAllocationModelString(AllocationModels.SHAPLEY.name());
		freightConfig.setAllocationStrategyString(AllocationValueTypes.COST_SAVINGS.name());
		freightConfig.setParallelism(1);
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
		Config config = ConfigUtils.createConfig(freightConfig, mutableConfig);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(100);
		ConfigUtils.addOrGetModule(config, ReceiverConfigGroup.class).setReceiverReplanningInterval(3);
		Scenario scenario = ScenarioUtils.createScenario(config);

		Carrier carrier = carrier();
		CarrierPlan initialPlan = solvedPlan(carrier, 0);
		initialPlan.setScore(10.0);
		CarrierAllocationFactor.set(initialPlan, 0.5, mutableConfig);
		carrier.addPlan(initialPlan);
		carrier.setSelectedPlan(initialPlan);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", carrier.getId().toString(), 600.0,
			TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		receiver.getSelectedPlan().setScore(5.0);
		receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(receivers, CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);
		initialPlan.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			MutableAfPlanUtils.selectedReceiverProfile(carrier, receivers.getReceivers().values()));

		MutableFreightCoalition coalition = new MutableFreightCoalition(
			org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(carrier));
		coalition.addCollaborator(FreightCollaboratorFactory.createCollaborator(receiver));
		FreightCoalitionManager coalitionManager = new FreightCoalitionManager(scenario);
		coalitionManager.setMutableFreightCoalitions(List.of(coalition));
		ReceiverPlan original = MutableAfPlanUtils.copyReceiverPlan(receiver.getSelectedPlan(), false);
		Map<Id<?>, ? extends BasicPlan> originals = Map.of(receiver.getId(), original);
		CollaborationDataStore dataStore = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, originals));
		MutableAfLearningStore store = new MutableAfLearningStore(
			scenario, mutableConfig, dataStore, coalitionManager);
		store.initialize();
		return new Fixture(config, scenario, mutableConfig, carrier, receiver, initialPlan,
			coalition, dataStore, store,
			new MutableAfReceiverStrategyManager(store, mutableConfig, dataStore));
	}

	private static void advanceInitialFactorToSwitch(Fixture fixture) {
		fixture.store.observeIterationEnd(0);
		fixture.store.prepareReplanning(1);
		fixture.dataStore.reset(1);
		setScores(fixture, 12.0, 8.0);
		recordCollaborationResult(fixture, 10.0);
		fixture.store.observeIterationEnd(1);
		fixture.store.prepareReplanning(2);
		fixture.dataStore.reset(2);
		setScores(fixture, 12.0, 8.0);
		recordCollaborationResult(fixture, 10.0);
		fixture.store.observeIterationEnd(2);
		fixture.store.prepareReplanning(3);
		fixture.dataStore.reset(3);
		setScores(fixture, 12.0, 8.0);
		recordCollaborationResult(fixture, 10.0);
		fixture.store.observeIterationEnd(3);
	}

	private static void recordCollaborationResult(Fixture fixture, double surplus) {
		fixture.dataStore.addSimulatedCoalitionScores(fixture.coalition, Map.of(
			Set.of(), 10.0,
			Set.of(fixture.receiver.getId()), 10.0 + surplus));
		fixture.dataStore.recordAppliedAllocationFactor(fixture.coalition,
			CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan(), fixture.mutableConfig));
	}

	private static void setScores(Fixture fixture, double carrierScore, double receiverScore) {
		fixture.carrier.getSelectedPlan().setScore(carrierScore);
		fixture.receiver.getSelectedPlan().setScore(receiverScore);
	}

	private static Carrier carrier() {
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		org.matsim.vehicles.VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type", org.matsim.vehicles.VehicleType.class))
			.setCapacity(100).build();
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("vehicle"), Id.createLinkId("depot"), type).build();
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.addVehicle(vehicle).build());
		return carrier;
	}

	private static CarrierPlan solvedPlan(Carrier carrier, int sequence) {
		CarrierVehicle vehicle = carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next();
		Tour.Builder builder = Tour.Builder.newInstance(Id.create("solved-" + sequence, Tour.class));
		builder.scheduleStart(Id.createLinkId("depot"));
		builder.addLeg(new Tour.Leg());
		builder.scheduleEnd(Id.createLinkId("depot"));
		ScheduledTour tour = ScheduledTour.newInstance(builder.build(), vehicle, 7 * 3600.0);
		return new CarrierPlan(carrier, List.of(tour));
	}

	private record Fixture(Config config, Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, Carrier carrier, Receiver receiver,
			CarrierPlan initialPlan, MutableFreightCoalition coalition, CollaborationDataStore dataStore,
			MutableAfLearningStore store, MutableAfReceiverStrategyManager receiverStrategy) {
	}
}
