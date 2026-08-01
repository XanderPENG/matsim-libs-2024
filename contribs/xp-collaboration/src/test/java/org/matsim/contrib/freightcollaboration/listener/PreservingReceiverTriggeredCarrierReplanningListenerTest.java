package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
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
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PreservingReceiverTriggeredCarrierReplanningListenerTest {

	@Test
	void installsTheInitialFactorPlanWhenTheCarrierStartsWithoutPlans() {
		Fixture fixture = fixture();
		fixture.carrier.clearPlans();
		AtomicInteger solves = new AtomicInteger();
		PreservingReceiverTriggeredCarrierReplanningListener listener =
			new PreservingReceiverTriggeredCarrierReplanningListener(fixture.scenario, fixture.mutableConfig,
				(carrier, scenario) -> solvedPlan(carrier, solves.incrementAndGet()));

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
		assertEquals(22.0, fixture.selected.getScore());
		assertEquals(0.3, CarrierAllocationFactor.require(fixture.first));
		assertEquals(0.7, CarrierAllocationFactor.require(fixture.selected));
		ScheduledTour firstTour = fixture.first.getScheduledTours().iterator().next();
		ScheduledTour selectedTour = fixture.selected.getScheduledTours().iterator().next();
		assertEquals("solved-1", firstTour.getTour().getId().toString());
		assertEquals("solved-1", selectedTour.getTour().getId().toString());
		assertNotSame(firstTour, selectedTour);
		assertNotSame(firstTour.getTour(), selectedTour.getTour());
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
		assertEquals(22.0, fixture.selected.getScore());
		CarrierShipment changedShipment = fixture.carrier.getShipments().values().iterator().next();
		assertEquals(10 * 3600.0, changedShipment.getDeliveryStartingTimeWindow().getStart());
		for (CarrierPlan plan : fixture.carrier.getPlans()) {
			assertEquals("solved-2", plan.getScheduledTours().iterator().next().getTour().getId().toString());
		}
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
}
