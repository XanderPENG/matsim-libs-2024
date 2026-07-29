package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.vehicles.VehicleType;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LinkReceiverAndCarrierTest {

	@Test
	void rebuildsShipmentsAndPlanWithoutMutatingReceiverAndReusesCaches() {
		Network network = smallNetwork();
		Carrier carrier = carrierWithVehicle("carrier");
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder(
			"receiver", "carrier", 10, TimeWindow.newInstance(0, 1000));
		FreightCollaborator<Carrier> carrierCollaborator =
			FreightCollaboratorFactory.createCollaborator(carrier);
		FreightCollaborator<Receiver> receiverCollaborator =
			FreightCollaboratorFactory.createCollaborator(receiver);
		var originalReceiverPlan = receiver.getSelectedPlan();
		TravelTime firstTravelTime = (link, time, person, vehicle) -> 1.0;

		LinkReceiverAndCarrier.receiversTriggerCarrierReplan(carrierCollaborator,
			Set.of(receiverCollaborator), network, firstTravelTime, 1);
		assertEquals(1, carrier.getShipments().size());
		assertNotNull(carrier.getSelectedPlan());
		assertFalse(carrier.getSelectedPlan().getScheduledTours().isEmpty());
		assertSame(originalReceiverPlan, receiver.getSelectedPlan());

		LinkReceiverAndCarrier.receiversTriggerCarrierReplan(carrierCollaborator,
			Set.of(receiverCollaborator), network, firstTravelTime, 1);
		assertEquals(1, carrier.getShipments().size());
		assertNotNull(carrier.getSelectedPlan());

		TravelTime nextIterationTravelTime = (link, time, person, vehicle) -> 2.0;
		LinkReceiverAndCarrier.receiversTriggerCarrierReplan(carrierCollaborator,
			Set.of(receiverCollaborator), network, nextIterationTravelTime, 1);
		assertEquals(1, carrier.getShipments().size(),
			"A changed TravelTime invalidates caches but preserves the result");
	}

	@Test
	void linkedReceiverLookupHandlesMatchingUnrelatedAndMissingPlans() {
		Carrier carrier = carrierWithVehicle("carrier");
		Receiver linked = FreightCollaborationTestFixtures.receiverWithOrder(
			"linked", "carrier", 10, TimeWindow.newInstance(0, 100));
		Receiver unrelated = FreightCollaborationTestFixtures.receiverWithOrder(
			"unrelated", "other", 10, TimeWindow.newInstance(0, 100));
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(linked));
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(unrelated));

		Set<FreightCollaborator<Receiver>> result =
			LinkReceiverAndCarrier.findLinkedReceivers(carrier, collaborators);
		assertEquals(1, result.size());
		assertEquals(linked.getId(), result.iterator().next().getId());
	}

	@Test
	void rejectsInvalidReplanDependenciesBeforeMutatingCarrier() {
		Carrier carrier = carrierWithVehicle("carrier");
		FreightCollaborator<Carrier> collaborator =
			FreightCollaboratorFactory.createCollaborator(carrier);
		Network network = smallNetwork();
		TravelTime travelTime = (link, time, person, vehicle) -> 1;
		assertAll(
			() -> assertThrows(IllegalArgumentException.class,
				() -> LinkReceiverAndCarrier.receiversTriggerCarrierReplan(
					collaborator, Set.of(), network, travelTime, 0)),
			() -> assertThrows(NullPointerException.class,
				() -> LinkReceiverAndCarrier.receiversTriggerCarrierReplan(
					null, Set.of(), network, travelTime, 1)),
			() -> assertThrows(NullPointerException.class,
				() -> LinkReceiverAndCarrier.receiversTriggerCarrierReplan(
					collaborator, null, network, travelTime, 1))
		);
		assertNotNull(carrier.getSelectedPlan());
	}

	static Carrier carrierWithVehicle(String id) {
		Carrier carrier = FreightCollaborationTestFixtures.carrier(id);
		VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type-" + id, VehicleType.class))
			.setCapacity(10)
			.setFixCost(0)
			.setCostPerDistanceUnit(1)
			.setCostPerTimeUnit(1)
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
		return carrier;
	}

	static Network smallNetwork() {
		Network network = NetworkUtils.createNetwork();
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
		return network;
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
