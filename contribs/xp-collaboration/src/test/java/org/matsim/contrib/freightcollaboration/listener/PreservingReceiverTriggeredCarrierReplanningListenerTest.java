package org.matsim.contrib.freightcollaboration.listener;

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
import org.matsim.contrib.freightcollaboration.learning.MutableAfPhase;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierShipment;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreservingReceiverTriggeredCarrierReplanningListenerTest {

	@Test
	void installsTheInitialFactorPlanWhenTheCarrierStartsWithoutPlans() {
		Fixture fixture = fixture();
		fixture.carrier.clearPlans();
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));
		assertEquals(100.0, listener.priority());

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		assertEquals(1, solves.get());
		assertEquals(1, fixture.carrier.getPlans().size());
		assertSame(fixture.carrier.getPlans().getFirst(), fixture.carrier.getSelectedPlan());
		assertEquals(0.8, CarrierAllocationFactor.require(fixture.carrier.getSelectedPlan()));
		assertNull(fixture.carrier.getSelectedPlan().getScore());
	}

	@Test
	void rebuildsEachCarrierOnceAndPreservesFactorPlanMemory() {
		Fixture fixture = fixture();
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		assertEquals(1, solves.get());
		assertEquals(2, fixture.carrier.getPlans().size());
		assertSame(fixture.selected, fixture.carrier.getSelectedPlan());
		assertEquals(11.0, fixture.first.getScore());
		assertNull(fixture.selected.getScore());
		assertEquals(0.3, CarrierAllocationFactor.require(fixture.first));
		assertEquals(0.7, CarrierAllocationFactor.require(fixture.selected));
		assertEquals(0, fixture.first.getScheduledTours().size());
		ScheduledTour selectedTour = fixture.selected.getScheduledTours().iterator().next();
		assertEquals("solved-1", selectedTour.getTour().getId().toString());
	}

	@Test
	void receiverChangesReplaceRoutesAndShipmentsWithoutClearingFactorHistory() {
		Fixture fixture = fixture();
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));
		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		CarrierShipment firstShipment = fixture.carrier.getShipments().values().iterator().next();
		assertEquals(8 * 3600.0, firstShipment.getDeliveryStartingTimeWindow().getStart());

		ReceiverPlan changed = FreightCollaborationTestFixtures.receiverPlan(fixture.receiver,
			fixture.carrier.getId().toString(), 900.0, TimeWindow.newInstance(10 * 3600.0, 12 * 3600.0));
		firstProductOrder(changed).setDailyOrderQuantity(1.0);
		fixture.receiver.addPlan(changed);
		fixture.receiver.setSelectedPlan(changed);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(fixture.scenario),
			CarriersUtils.getCarriers(fixture.scenario));
		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));

		assertEquals(2, solves.get());
		assertEquals(2, fixture.carrier.getPlans().size());
		assertSame(fixture.selected, fixture.carrier.getSelectedPlan());
		assertEquals(0.3, CarrierAllocationFactor.require(fixture.first));
		assertEquals(0.7, CarrierAllocationFactor.require(fixture.selected));
		assertEquals(11.0, fixture.first.getScore());
		assertNull(fixture.selected.getScore());
		CarrierShipment changedShipment = fixture.carrier.getShipments().values().iterator().next();
		assertEquals(10 * 3600.0, changedShipment.getDeliveryStartingTimeWindow().getStart());
		assertEquals(0, fixture.first.getScheduledTours().size());
		assertEquals("solved-2",
			fixture.selected.getScheduledTours().iterator().next().getTour().getId().toString());
	}

	@Test
	void unchangedReceiverProfileReusesTheActiveRoute() {
		Fixture fixture = fixture();
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		ScheduledTour firstExecution = fixture.selected.getScheduledTours().iterator().next();
		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));

		assertEquals(1, solves.get());
		assertEquals("solved-1",
			fixture.selected.getScheduledTours().iterator().next().getTour().getId().toString());
		assertSame(firstExecution, fixture.selected.getScheduledTours().iterator().next());
	}

	@Test
	void warmStartTrialBypassesTheOrdinaryIntervalAndClearsScoreOnlyBeforeMobsim() {
		WarmFixture fixture = warmFixture();
		ReceiverPlan warmStart = fixture.receiver.getSelectedPlan();
		CarrierPlan dormant = fixture.dormantPlan;
		assertEquals(MutableAfPhase.WARM_START_TRIAL,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertTrue(Double.isFinite(warmStart.getScore()),
			"the default Receiver replanning listener must still be able to unbox this score");
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));

		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				fixture.store, (carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));
		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 4, false));

		assertNull(warmStart.getScore(), "BeforeMobsim must remove the cross-AF compatibility score");
		assertTrue(MutableAfPlanUtils.isPendingEvaluation(warmStart));
		assertEquals(0, solves.get(),
			"iteration 4 bypasses the interval, but the unchanged profile may reuse the copied route");
		assertSame(dormant, fixture.carrier.getPlans().stream()
			.filter(plan -> CarrierAllocationFactor.require(plan) == 0.5).findFirst().orElseThrow());
		assertEquals(12.0, dormant.getScore());
		assertEquals("solved-0", dormant.getScheduledTours().iterator().next().getTour().getId().toString());
		assertTrue(fixture.store.snapshot(fixture.carrier.getId())
			.warmStartScoreClearedBeforeMobsim());

		fixture.dataStore.reset(4);
		fixture.carrier.getSelectedPlan().setScore(13.0);
		warmStart.setScore(9.0);
		fixture.store.observeIterationEnd(4);
		assertEquals(MutableAfPhase.ADAPT, fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertEquals(1, fixture.store.snapshot(fixture.carrier.getId()).dwell());
		assertTrue(!MutableAfPlanUtils.isPendingEvaluation(warmStart));
	}

	@Test
	void matchingRouteProfileCanBeReusedWithoutSkippingWarmStartExecution() {
		WarmFixture fixture = warmFixture();
		CarrierPlan active = fixture.carrier.getSelectedPlan();
		String profile = MutableAfPlanUtils.selectedReceiverProfile(fixture.carrier,
			ReceiverUtils.getReceivers(fixture.scenario).getReceivers().values());
		active.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE, profile);
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				fixture.store, (carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 4, false));

		assertEquals(0, solves.get());
		assertNull(fixture.receiver.getSelectedPlan().getScore());
		assertTrue(fixture.store.snapshot(fixture.carrier.getId())
			.warmStartScoreClearedBeforeMobsim(),
			"route reuse must not make the trial look as if it was never executed");
	}

	@Test
	void finalRevisitForcesExecutionOutsideTheOrdinaryIntervalAndClearsHistoricalScores() {
		WarmFixture fixture = warmFixture();
		fixture.store.prepareReplanning(90);
		assertEquals(MutableAfPhase.FINAL_REVISIT,
			fixture.store.snapshot(fixture.carrier.getId()).phase());
		assertTrue(Double.isFinite(fixture.carrier.getSelectedPlan().getScore()));
		assertTrue(Double.isFinite(fixture.receiver.getSelectedPlan().getScore()));
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario,
				fixture.mutableConfig, fixture.store,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));

		listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 90, false));

		assertNull(fixture.carrier.getSelectedPlan().getScore());
		assertNull(fixture.receiver.getSelectedPlan().getScore());
		assertEquals(0, solves.get(), "the exact checkpoint route can be reused");
		fixture.dataStore.reset(90);
		fixture.carrier.getSelectedPlan().setScore(13.0);
		fixture.receiver.getSelectedPlan().setScore(9.0);
		fixture.store.observeIterationEnd(90);
		assertEquals(13.0, fixture.store.snapshot(fixture.carrier.getId()).carrierScore());
	}

	private static Fixture fixture() {
		Config config = ConfigUtils.createConfig();
		config.controller().setFirstIteration(0);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = carrierWithCapabilities("carrier");
		CarrierPlan first = new CarrierPlan(carrier, new ArrayList<>());
		first.setScore(11.0);
		CarrierPlan selected = new CarrierPlan(carrier, new ArrayList<>());
		selected.setScore(22.0);
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		CarrierAllocationFactor.set(first, 0.3, mutableConfig);
		CarrierAllocationFactor.set(selected, 0.7, mutableConfig);
		carrier.addPlan(first);
		carrier.addPlan(selected);
		carrier.setSelectedPlan(selected);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);

		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder("receiver", "carrier", 600.0,
			TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		firstProductOrder(receiver.getSelectedPlan()).setDailyOrderQuantity(1.0);
		receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(receivers, CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);
		return new Fixture(scenario, mutableConfig, carrier, receiver, first, selected);
	}

	private static WarmFixture warmFixture() {
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
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(100);
		ConfigUtils.addOrGetModule(config, ReceiverConfigGroup.class).setReceiverReplanningInterval(3);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = carrierWithCapabilities("warm-carrier");
		CarrierPlan initial = solvedPlan(carrier, 0);
		initial.setScore(10.0);
		CarrierAllocationFactor.set(initial, 0.5, mutableConfig);
		carrier.addPlan(initial);
		carrier.setSelectedPlan(initial);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);

		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"warm-receiver", carrier.getId().toString(), 600.0,
			TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		firstProductOrder(receiver.getSelectedPlan()).setDailyOrderQuantity(1.0);
		receiver.getSelectedPlan().setScore(5.0);
		receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		ReceiverUtils.setReceivers(receivers, scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(receivers, CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);
		initial.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			MutableAfPlanUtils.selectedReceiverProfile(carrier, receivers.getReceivers().values()));

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
		dataStore.reset(1);
		carrier.getSelectedPlan().setScore(12.0);
		receiver.getSelectedPlan().setScore(8.0);
		store.observeIterationEnd(1);
		store.prepareReplanning(2);
		dataStore.reset(2);
		carrier.getSelectedPlan().setScore(12.0);
		receiver.getSelectedPlan().setScore(8.0);
		store.observeIterationEnd(2);
		store.prepareReplanning(3);
		dataStore.reset(3);
		carrier.getSelectedPlan().setScore(12.0);
		receiver.getSelectedPlan().setScore(8.0);
		store.observeIterationEnd(3);
		store.prepareReplanning(4);
		return new WarmFixture(scenario, mutableConfig, carrier, receiver, initial, dataStore, store);
	}

	private static Carrier carrierWithCapabilities(String id) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
		org.matsim.vehicles.VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type", org.matsim.vehicles.VehicleType.class))
			.setCapacity(100)
			.build();
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("vehicle"), Id.createLinkId("depot"), type).build();
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.addVehicle(vehicle)
			.build());
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

	private static org.matsim.freight.receiver.Order firstProductOrder(ReceiverPlan plan) {
		return plan.getReceiverOrders().iterator().next().getReceiverProductOrders().iterator().next();
	}

	private record Fixture(Scenario scenario, MutableAllocationFactorConfigGroup mutableConfig,
			Carrier carrier, Receiver receiver, CarrierPlan first, CarrierPlan selected) {
	}

	private record WarmFixture(Scenario scenario, MutableAllocationFactorConfigGroup mutableConfig,
			Carrier carrier, Receiver receiver, CarrierPlan dormantPlan,
			CollaborationDataStore dataStore, MutableAfLearningStore store) {
	}
}
