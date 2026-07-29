package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.FreightActivity;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.vehicles.VehicleType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoringFunctionFactoryUsecaseTest {

	@Test
	void activityScoringChargesDurationAndLateArrival() {
		var scoring = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase
			.SimpleDriversActivityScoring();
		scoring.handleActivity(activity(50, 60, TimeWindow.newInstance(0, 100)));
		scoring.handleActivity(activity(120, 130, TimeWindow.newInstance(0, 100)));

		assertEquals(-(20 * 0.008) - (20 * 0.0278), scoring.getScore(), 1e-12);
	}

	@Test
	void activityScoringRejectsNegativeDuration() {
		var scoring = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase
			.SimpleDriversActivityScoring();
		assertThrows(IllegalStateException.class,
			() -> scoring.handleActivity(activity(20, 10, TimeWindow.newInstance(0, 100))));
	}

	@Test
	void receiverMoneyAndTypedAllocationScoringHandleEmptyAndValues() {
		var money = new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase
			.CarrierToReceiverCostAllocation();
		money.addMoney(-2);
		money.addMoney(5);
		assertEquals(3, money.getScore(), 1e-12);

		Receiver receiver = FreightCollaborationTestFixtures.receiver("same");
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		var allocation = new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase
			.AllocationFromDistributor(receiver, store);
		assertEquals(0, allocation.getScore(), 1e-12);
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.CARRIER, Id.create("same", Object.class)), 99.0,
			new CollaboratorKey(CollaboratorRole.RECEIVER, receiver.getId()), -4.0));
		assertEquals(-4, allocation.getScore(), 1e-12);
	}

	@Test
	void receiverRelaxationPenaltyUsesOriginalPlanAndHandlesUnequalListLengths() {
		Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver", Receiver.class));
		ReceiverPlan original = ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(TimeWindow.newInstance(100, 200))
			.build();
		ReceiverPlan current = ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(TimeWindow.newInstance(90, 220))
			.addTimeWindow(TimeWindow.newInstance(300, 400))
			.build();
		receiver.setSelectedPlan(current);
		Map<Id<?>, BasicPlan> originals = Map.of(receiver.getId(), original);
		CollaborationDataStore store =
			new CollaborationDataStore(Map.of(CollaboratorRole.RECEIVER, originals));

		var penalty = new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase
			.ReceiverRelaxationPenalty(receiver, 0.1, store);
		assertEquals(-3.0, penalty.getScore(), 1e-12);

		var missing = new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase
			.ReceiverRelaxationPenalty(receiver, 0.1, FreightCollaborationTestFixtures.emptyDataStore());
		assertThrows(IllegalStateException.class, missing::getScore);
	}

	@Test
	void activityEntryPointsIgnoreOrdinaryActivitiesAndFinishIsStable() {
		var scoring = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase
			.SimpleDriversActivityScoring();
		Activity ordinary = PopulationUtils.createActivityFromLinkId(
			"ordinary", Id.createLinkId("link"));
		scoring.handleFirstActivity(ordinary);
		scoring.handleLastActivity(activity(0, 10, TimeWindow.newInstance(0, 100)));
		scoring.finish();
		assertEquals(-0.08, scoring.getScore(), 1e-12);
	}

	@Test
	void carrierFeesAndCostSavingsUseOnlyLinkedPositiveReceiverAllocations() {
		Carrier carrier = carrierWithCapabilities("carrier");
		Receiver linked = FreightCollaborationTestFixtures.receiverWithOrder(
			"linked", "carrier", 10, TimeWindow.newInstance(0, 100));
		Receiver unrelated = FreightCollaborationTestFixtures.receiverWithOrder(
			"unrelated", "other", 10, TimeWindow.newInstance(0, 100));
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(linked));
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(unrelated));
		var charging = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase
			.SimpleChargingReceiverScoring(carrier, collaborators, 25);
		charging.finish();
		assertEquals(25.0, charging.getScore(), 1e-12);

		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		config.setAllocationStrategyString(AllocationValueTypes.COST_SAVINGS.name());
		var distribution = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase
			.distributeCostSavings(carrier, store, config, collaborators);
		assertEquals(0.0, distribution.getScore(), 1e-12);
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.RECEIVER, linked.getId()), 12.0,
			new CollaboratorKey(CollaboratorRole.RECEIVER, unrelated.getId()), 99.0));
		distribution.finish();
		assertEquals(-12.0, distribution.getScore(), 1e-12);

		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.RECEIVER, linked.getId()), -3.0));
		assertEquals(0.0, distribution.getScore(), 1e-12,
			"Negative allocations are not carrier payouts");
		config.setAllocationStrategyString(AllocationValueTypes.COST.name());
		assertEquals(0.0, distribution.getScore(), 1e-12);
	}

	@Test
	void configuredFactoriesCreateNormalAndPsimVariants() {
		Carrier carrier = carrierWithCapabilities("carrier");
		FreightCollaborators collaborators = new FreightCollaborators();
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		var factory = new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase(
			NetworkUtils.createNetwork(), collaborators, store, config);

		for (ScoringFunction scoring : java.util.List.of(
			factory.createScoringFunction(carrier),
			factory.createBasicCostScoringFunction(carrier),
			factory.createBasicCostPlusFeesScoringFunction(carrier),
			factory.createPsimScoringFunction(carrier,
				FreightCollaborationConfigGroup.PsimScoringMode.BASIC_COST),
			factory.createPsimScoringFunction(carrier,
				FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES))) {
			scoring.finish();
			assertTrue(Double.isFinite(scoring.getScore()));
		}

		var lspFactory =
			new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryForLspReceiverCollab(
				NetworkUtils.createNetwork(), collaborators, store, config);
		for (ScoringFunction scoring : java.util.List.of(
			lspFactory.createScoringFunction(carrier),
			lspFactory.createBasicCostScoringFunction(carrier),
			lspFactory.createPsimScoringFunction(carrier,
				FreightCollaborationConfigGroup.PsimScoringMode.BASIC_COST),
			lspFactory.createPsimScoringFunction(carrier,
				FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES))) {
			scoring.finish();
			assertTrue(Double.isFinite(scoring.getScore()));
		}
	}

	@Test
	void receiverFactoryCombinesMoneyRelaxationAndTypedAllocation() {
		Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver", Receiver.class));
		ReceiverPlan original = ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(TimeWindow.newInstance(100, 200))
			.build();
		ReceiverPlan current = ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(TimeWindow.newInstance(90, 210))
			.build();
		receiver.addPlan(current);
		receiver.setSelectedPlan(current);
		CollaborationDataStore store = new CollaborationDataStore(Map.of(
			CollaboratorRole.RECEIVER, Map.of(receiver.getId(), original)));
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.RECEIVER, receiver.getId()), 8.0));
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		config.RECEIVER_RELAXATION_PENALTY = 0.1;
		ScoringFunction scoring =
			new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase(store, config)
				.createScoringFunction(receiver);
		scoring.addMoney(-2.0);
		scoring.finish();
		assertEquals(4.0, scoring.getScore(), 1e-12);
	}

	@Test
	void missingDeliveryAndEmptyLspScoringAreExplicit() {
		Carrier carrier = carrierWithCapabilities("carrier");
		CarrierShipment shipment = CarrierShipment.Builder.newInstance(
				Id.create("shipment", CarrierShipment.class),
				Id.createLinkId("origin"), Id.createLinkId("receiver"), 1)
			.setDeliveryStartingTimeWindow(TimeWindow.newInstance(0, 100))
			.build();
		CarriersUtils.addShipment(carrier, shipment);
		var missing = new ScoringFunctionFactoryUsecase
			.CarrierScoringFunctionFactoryForLspReceiverCollab
			.missingDeliveryPenalty(carrier, 500);
		missing.finish();
		assertEquals(-500.0, missing.getScore(), 1e-12);

		var charging = new ScoringFunctionFactoryUsecase
			.CarrierScoringFunctionFactoryForLspReceiverCollab
			.SimpleChargingReceiverScoring(carrier, new FreightCollaborators(), 30);
		assertEquals(0.0, charging.getScore(), 1e-12);
		charging.finish();

		LSP lsp = FreightCollaborationTestFixtures.lsp("lsp");
		assertEquals(0.0,
			ScoringFunctionFactoryUsecase.LSPScoringFunctionFactory
				.scoreNonDeliveredShipments(lsp), 1e-12);
	}

	private static FreightActivity activity(double start, double end, TimeWindow timeWindow) {
		Activity activity = PopulationUtils.createActivityFromLinkId("service", Id.createLinkId("link"));
		activity.setStartTime(start);
		activity.setEndTime(end);
		return new FreightActivity(activity, timeWindow);
	}

	private static Carrier carrierWithCapabilities(String id) {
		Carrier carrier = FreightCollaborationTestFixtures.carrier(id);
		VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type-" + id, VehicleType.class))
			.setCapacity(10)
			.setFixCost(0)
			.build();
		type.setNetworkMode("car");
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("vehicle-" + id), Id.createLinkId("depot"), type).build();
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.addVehicle(vehicle)
			.build());
		return carrier;
	}
}
