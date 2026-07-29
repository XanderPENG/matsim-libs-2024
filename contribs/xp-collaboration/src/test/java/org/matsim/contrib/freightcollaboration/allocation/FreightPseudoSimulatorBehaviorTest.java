package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.vehicles.VehicleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FreightPseudoSimulatorBehaviorTest {

	@Test
	void realCarrierReceiverPathReplansAndScoresCopiesOnly() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Network network = scenario.getNetwork();
		populateNetwork(network);
		Carrier carrier = carrierWithVehicle("carrier", false);
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", "carrier", 10, TimeWindow.newInstance(0, 1000));
		FreightCollaborator<Carrier> carrierCollaborator =
			FreightCollaboratorFactory.createCollaborator(carrier);
		FreightCollaborator<Receiver> receiverCollaborator =
			FreightCollaboratorFactory.createCollaborator(receiver);
		FreightCollaborators all = new FreightCollaborators();
		all.addFreightCollaborator(carrierCollaborator);
		all.addFreightCollaborator(receiverCollaborator);
		CollaborationDataStore store = storeWithOriginalPlans(carrier, receiver);
		store.setScenario(scenario);
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		FreightPseudoSimulator simulator = new FreightPseudoSimulator(store, scenario.getNetwork(), all,
			(link, time, person, vehicle) -> 1.0, ignored -> new SumScoringFunction(), config);
		simulator.setVrpMaxIterations(1);
		CarrierPlan inputPlan = carrier.getSelectedPlan();
		ReceiverPlan receiverInputPlan = receiver.getSelectedPlan();

		double score = simulator.runSingleSubCoalition(
			Map.of(carrier.getId(), carrierCollaborator),
			Map.of(receiver.getId(), receiverCollaborator), Set.of(receiver.getId()));

		assertEquals(0.0, score, 1e-12);
		assertSame(inputPlan, carrier.getSelectedPlan(),
			"PSim must replan only the deep copied carrier");
		assertSame(receiverInputPlan, receiver.getSelectedPlan(),
			"PSim must not modify the input receiver");
	}

	@Test
	void activitySimulationBuildsInterleavedScoringInputAndRejectsMalformedPlans() {
		FreightPseudoSimulator simulator = new FreightPseudoSimulator((d, p, s) -> 0);
		Carrier routed = carrierWithVehicle("routed", true);

		var result = simulator.runActivityBasedCarrierSimulation(routed);
		assertEquals(1, result.size());
		assertEquals(1, result.get(1).getFirst().size());
		assertEquals(1, result.get(1).getSecond().size());
		assertEquals(10.0,
			result.get(1).getFirst().getFirst().getEndTime().seconds(), 1e-12);
		assertEquals(Id.createVehicleId("vehicle-routed"),
			((NetworkRoute) result.get(1).getSecond().getFirst().getRoute()).getVehicleId());

		Carrier withoutPlan = FreightCollaborationTestFixtures.carrier("without-plan");
		withoutPlan.clearPlans();
		assertThrows(IllegalStateException.class,
			() -> simulator.runActivityBasedCarrierSimulation(withoutPlan));

		Carrier missingRoute = carrierWithVehicle("missing-route", false);
		missingRoute.clearPlans();
		missingRoute.addPlan(planWithLeg(missingRoute, false));
		missingRoute.setSelectedPlan(missingRoute.getPlans().getFirst());
		assertThrows(IllegalStateException.class,
			() -> simulator.runActivityBasedCarrierSimulation(missingRoute));
	}

	@Test
	void resetRestoresOriginalPlansForAllRolesAndReportsMissingState() {
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		Receiver receiver = FreightCollaborationTestFixtures.receiver("receiver");
		LSP lsp = FreightCollaborationTestFixtures.lsp("lsp");
		CarrierPlan originalCarrier = carrier.getSelectedPlan();
		ReceiverPlan originalReceiver = receiver.getSelectedPlan();
		var originalLsp = lsp.getSelectedPlan();
		Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originals = Map.of(
			CollaboratorRole.CARRIER, Map.of(carrier.getId(), originalCarrier),
			CollaboratorRole.RECEIVER, Map.of(receiver.getId(), originalReceiver),
			CollaboratorRole.LSP, Map.of(lsp.getId(), originalLsp));
		CollaborationDataStore store = new CollaborationDataStore(originals);
		FreightPseudoSimulator simulator = new FreightPseudoSimulator(store, smallNetwork(),
			new FreightCollaborators(), (link, time, person, vehicle) -> 1,
			ignored -> new SumScoringFunction(), new FreightCollaborationConfigGroup());
		FreightCollaborator<Carrier> carrierCollaborator =
			FreightCollaboratorFactory.createCollaborator(carrier);
		FreightCollaborator<Receiver> receiverCollaborator =
			FreightCollaboratorFactory.createCollaborator(receiver);
		FreightCollaborator<LSP> lspCollaborator =
			FreightCollaboratorFactory.createCollaborator(lsp);
		Map<Id<?>, FreightCollaborator<?>> players = new LinkedHashMap<>();
		players.put(carrier.getId(), carrierCollaborator);
		players.put(receiver.getId(), receiverCollaborator);
		players.put(lsp.getId(), lspCollaborator);

		CarrierPlan changedCarrierPlan = new CarrierPlan(carrier, List.of());
		carrier.addPlan(changedCarrierPlan);
		carrier.setSelectedPlan(changedCarrierPlan);
		receiver.setSelectedPlan(originalReceiver.createCopy());
		lsp.setSelectedPlan(org.matsim.freight.logistics.LSPUtils.createLSPPlan());
		simulator.resetNonCollaboratingMembersPlans(Set.copyOf(players.keySet()), players);
		assertSame(originalCarrier, carrier.getSelectedPlan());
		assertSame(originalReceiver, receiver.getSelectedPlan());
		assertSame(originalLsp, lsp.getSelectedPlan());

		assertThrows(IllegalStateException.class,
			() -> simulator.resetNonCollaboratingMembersPlans(
				Set.of(Id.create("missing", Object.class)), players));
	}

	@Test
	void requestedCoalitionsAreDeduplicatedAndInvalidRoleShapesFailClearly() {
		AtomicInteger evaluations = new AtomicInteger();
		FreightPseudoSimulator seam = new FreightPseudoSimulator((d, p, s) -> {
			evaluations.incrementAndGet();
			return s.size();
		});
		Id<?> receiverId = Id.create("receiver", Object.class);
		Map<Id<?>, FreightCollaborator<?>> players = Map.of(receiverId,
			FreightCollaborationTestFixtures.receiverCollaborator("receiver"));
		var values = seam.runSubCoalitions(Map.of(), players,
			List.of(Set.of(), Set.of(receiverId), Set.of(receiverId)));
		assertEquals(2, values.size());
		assertEquals(2, evaluations.get());
		assertThrows(IllegalArgumentException.class, () -> seam.setVrpMaxIterations(0));
		assertDoesNotThrow(() -> seam.setVrpSampleIterations(1));

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.setScenario(scenario);
		FreightPseudoSimulator real = new FreightPseudoSimulator(store, scenario.getNetwork(),
			new FreightCollaborators(), (link, time, person, vehicle) -> 1,
			ignored -> new SumScoringFunction(), new FreightCollaborationConfigGroup());
		assertThrows(IllegalArgumentException.class,
			() -> real.runSingleSubCoalition(Map.of(), players, Set.of(receiverId)));
		assertThrows(IllegalArgumentException.class,
			() -> real.runSingleSubCoalition(
				Map.of(Id.create("carrier", Object.class),
					FreightCollaborationTestFixtures.carrierCollaborator("carrier")),
				Map.of(), Set.of()));

		FreightCollaborator<Carrier> distributor =
			FreightCollaborationTestFixtures.carrierCollaborator("distributor");
		FreightCollaborator<Carrier> carrierPlayer =
			FreightCollaborationTestFixtures.carrierCollaborator("player");
		assertThrows(IllegalStateException.class,
			() -> real.runSingleSubCoalition(
				Map.of(distributor.getId(), distributor),
				Map.of(carrierPlayer.getId(), carrierPlayer),
				Set.of(carrierPlayer.getId())));
	}

	private static CollaborationDataStore storeWithOriginalPlans(Carrier carrier, Receiver receiver) {
		return new CollaborationDataStore(Map.of(
			CollaboratorRole.CARRIER, Map.of(carrier.getId(), carrier.getSelectedPlan()),
			CollaboratorRole.RECEIVER, Map.of(receiver.getId(), receiver.getSelectedPlan())));
	}

	private static Carrier carrierWithVehicle(String id, boolean routedPlan) {
		Carrier carrier = FreightCollaborationTestFixtures.carrier(id);
		VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type-" + id, VehicleType.class))
			.setCapacity(10)
			.setFixCost(0)
			.setCostPerDistanceUnit(0)
			.setCostPerTimeUnit(0)
			.build();
		type.setNetworkMode("car");
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("vehicle-" + id), Id.createLinkId("origin"), type)
			.setEarliestStart(0)
			.setLatestEnd(10_000)
			.build();
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.setFleetSize(CarrierCapabilities.FleetSize.FINITE)
			.addVehicle(vehicle)
			.build());
		if (routedPlan) {
			carrier.clearPlans();
			CarrierPlan plan = planWithLeg(carrier, true);
			carrier.addPlan(plan);
			carrier.setSelectedPlan(plan);
		}
		return carrier;
	}

	private static CarrierPlan planWithLeg(Carrier carrier, boolean withRoute) {
		CarrierVehicle vehicle = carrier.getCarrierCapabilities().getCarrierVehicles()
			.values().iterator().next();
		Tour.Leg leg = new Tour.Leg();
		leg.setDepartureTime(10);
		leg.setExpectedTransportTime(5);
		if (withRoute) {
			leg.setRoute(RouteUtils.createNetworkRoute(
				List.of(Id.createLinkId("origin"), Id.createLinkId("receiver-link"))));
		}
		Tour.Builder builder = Tour.Builder.newInstance(Id.create("tour", Tour.class));
		builder.scheduleStart(Id.createLinkId("origin"));
		builder.addLeg(leg);
		builder.scheduleEnd(Id.createLinkId("receiver-link"));
		return new CarrierPlan(carrier,
			List.of(ScheduledTour.newInstance(builder.build(), vehicle, 0)));
	}

	private static Network smallNetwork() {
		Network network = NetworkUtils.createNetwork();
		populateNetwork(network);
		return network;
	}

	private static void populateNetwork(Network network) {
		NetworkFactory factory = network.getFactory();
		Node a = factory.createNode(Id.createNodeId("a"), new Coord(0, 0));
		Node b = factory.createNode(Id.createNodeId("b"), new Coord(100, 0));
		Node c = factory.createNode(Id.createNodeId("c"), new Coord(200, 0));
		network.addNode(a);
		network.addNode(b);
		network.addNode(c);
		addLink(network, factory, "origin", a, b);
		addLink(network, factory, "receiver-link", b, c);
		addLink(network, factory, "return", c, a);
	}

	private static void addLink(Network network, NetworkFactory factory, String id, Node from, Node to) {
		Link link = factory.createLink(Id.createLinkId(id), from, to);
		link.setLength(100);
		link.setFreespeed(10);
		link.setCapacity(1000);
		link.setNumberOfLanes(1);
		network.addLink(link);
	}
}
