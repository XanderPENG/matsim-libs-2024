package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPResource;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.logistics.LogisticChain;
import org.matsim.freight.logistics.LogisticChainElement;
import org.matsim.freight.logistics.LogisticChainScheduler;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.LSPCarrierResource;
import org.matsim.freight.logistics.resourceImplementations.CarrierSchedulerUtils;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.freight.carriers.*;
import org.matsim.freight.receiver.*;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.logistics.shipment.LspShipmentUtils;
import org.matsim.vehicles.VehicleType;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class LinkReceiverAndLspTest {

	@Test
	void receiversTriggerLspReplan() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		var network = scenario.getNetwork();
		createSingleLinkNetwork(network, "link_1");

		LSP originalLsp = createMinimalHubLsp(scenario);
		originalLsp.getAttributes().putAttribute("marker", "orig");
		FreightCollaborator<LSP> lspCollab = FreightCollaboratorFactory.createCollaborator(originalLsp);

		TravelTime zeroTt = (link, time, person, vehicle) -> 0.0;
		LSP replanned = LinkReceiverAndLsp.receiversTriggerLspReplan(
				lspCollab,
				Set.of(), // no collaborating receivers -> no shipments
				network,
				zeroTt,
				10,
				scenario);

		// Basic sanity: new LSP instance and plan not the same as original
		assertNotSame(originalLsp, replanned);
		assertNotSame(originalLsp.getSelectedPlan(), replanned.getSelectedPlan());

		// Resources are deep copied: same ids, different object refs
		assertEquals(originalLsp.getResources().size(), replanned.getResources().size());
		LSPResource origRes = originalLsp.getResources().iterator().next();
		LSPResource newRes = replanned.getResources().iterator().next();
		assertEquals(origRes.getId(), newRes.getId());
		assertNotSame(origRes, newRes);

		// Attribute copied over
		assertEquals("orig", replanned.getAttributes().getAttribute("marker"));

		// Original plan was not modified by scheduling the clone (still zero shipments tracked)
		LogisticChainElement origEl = originalLsp.getSelectedPlan().getLogisticChains().iterator().next().getLogisticChainElements().iterator().next();
		assertTrue(origEl.getIncomingShipments().getLspShipmentsWTime().isEmpty());
		assertTrue(origEl.getOutgoingShipments().getLspShipmentsWTime().isEmpty());

		// Replanned LSP still has a valid plan and resource wiring
		LSPPlan newPlan = replanned.getSelectedPlan();
		assertFalse(newPlan.getLogisticChains().isEmpty());
		LogisticChainElement newEl = newPlan.getLogisticChains().iterator().next().getLogisticChainElements().iterator().next();
		assertEquals(newRes, newEl.getResource());
	}

	@Test
	void twoEchelonReplanDoesNotMutateOriginal() {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(String.valueOf(IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9"), "grid9x9.xml")));
		Scenario scenario = ScenarioUtils.loadScenario(config);

//		new MatsimNetworkReader(scenario.getNetwork())
//				.readFile("examples/scenarios/freight-chessboard-9x9/grid9x9.xml");

		Receivers receivers = createExampleReceiversWithOrders();
		ReceiverUtils.setReceivers(receivers, scenario);

		LSP original = createTwoEchelonLsp(scenario, receivers);
		original.scheduleLogisticChains();

		Carrier originalDistCarrier = getCarrierResource(original, "distributionCarrier").getCarrier();
		CarrierPlan origPlanRef = originalDistCarrier.getSelectedPlan();

		// mutate receiver time windows to force replanning
		receivers.getReceivers().values().forEach(r -> {
			ReceiverPlan plan = r.getSelectedPlan();
			TimeWindow old = plan.getTimeWindows().getFirst();
			plan.getTimeWindows().clear();
			plan.getTimeWindows().add(TimeWindow.newInstance(old.getStart()+2*3600, old.getEnd()+2*3600));
		});

		Set<FreightCollaborator<Receiver>> receiverCollaborators = receivers.getReceivers().values().stream()
				.map(FreightCollaboratorFactory::createCollaborator)
				.peek(FreightCollaborator::enableCollaboration)
				.collect(java.util.stream.Collectors.toSet());

		FreightCollaborator<LSP> lspCollab = FreightCollaboratorFactory.createCollaborator(original);
		TravelTime zeroTt = (link, time, person, vehicle) -> 0.0;
		LSP replanned = LinkReceiverAndLsp.receiversTriggerLspReplan(lspCollab, receiverCollaborators,
				scenario.getNetwork(), zeroTt, 20, scenario);

		// original not mutated
		assertSame(origPlanRef, originalDistCarrier.getSelectedPlan());
		// new LSP has distinct carrier plan object
		Carrier newDistCarrier = getCarrierResource(replanned, "distributionCarrier").getCarrier();
		assertNotSame(origPlanRef, newDistCarrier.getSelectedPlan());
	}

	private static void createSingleLinkNetwork(org.matsim.api.core.v01.network.Network network, String linkId){
		var n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), CoordUtils.createCoord(0,0));
		var n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), CoordUtils.createCoord(100,0));
		NetworkUtils.createAndAddLink(network, Id.createLinkId(linkId), n1, n2, 1000, 10, 1000, 1);
	}

	private static LSP createMinimalHubLsp(Scenario scenario){
		var hubScheduler = ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance()
				.setCapacityNeedFixed(0)
				.setCapacityNeedLinear(0)
				.build();
		var hub = ResourceImplementationUtils.TransshipmentHubBuilder
				.newInstance(Id.create("hub", LSPResource.class), Id.createLinkId("link_1"), scenario)
				.setTransshipmentHubScheduler(hubScheduler)
				.build();

		var element = LSPUtils.LogisticChainElementBuilder
				.newInstance(Id.create("el", org.matsim.freight.logistics.LogisticChainElement.class))
				.setResource(hub)
				.build();

		var chain = LSPUtils.LogisticChainBuilder
				.newInstance(Id.create("chain", LogisticChain.class))
				.addLogisticChainElement(element)
				.build();

		LSPPlan plan = LSPUtils.createLSPPlan();
		plan.addLogisticChain(chain);
		plan.setInitialShipmentAssigner(ResourceImplementationUtils.createSingleLogisticChainShipmentAssigner());

		LogisticChainScheduler scheduler = ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(
				List.of(hub));
		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create("lsp", LSP.class))
				.setLogisticChainScheduler(scheduler)
				.setInitialPlan(plan)
				.build();
		plan.setLSP(lsp);
		return lsp;
	}

	private static LSPCarrierResource getCarrierResource(LSP lsp, String carrierId){
		for (LSPResource res : lsp.getResources()){
			if (res instanceof LSPCarrierResource cr && cr.getCarrier().getId().toString().equals(carrierId)){
				return cr;
			}
		}
		throw new IllegalStateException("No distribution carrier resource found");
	}

	private static Receivers createExampleReceiversWithOrders() {
		Receivers receivers = ReceiverUtils.createReceivers();
		Id<Carrier> distributionCarrierId = Id.create("distributionCarrier", Carrier.class);
		Map<String, Id<Link>> locs = Map.of(
				"r_i(3,7)R", Id.createLinkId("i(3,7)R"),
				"r_i(7,7)R", Id.createLinkId("i(7,7)R"),
				"r_i(3,5)R", Id.createLinkId("i(3,5)R"),
				"r_i(7,5)R", Id.createLinkId("i(7,5)R"),
				"r_i(3,3)R", Id.createLinkId("i(3,3)R"),
				"r_i(7,3)R", Id.createLinkId("i(7,3)R")
		);
		for (var entry : locs.entrySet()) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create(entry.getKey(), Receiver.class));
			receiver.setLinkId(entry.getValue());
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);

			ProductType pType = ReceiverUtils.createAndGetProductType(receivers, Id.create("product_" + entry.getKey(), ProductType.class),
					Id.createLinkId("j(0,1)R"));
			pType.setRequiredCapacity(10);

			ReceiverProduct receiverProduct = ReceiverProduct.Builder.newInstance()
					.setProductType(pType)
					.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 300.0))
					.build();
			receiver.addProduct(receiverProduct);

			Order order = Order.Builder.newInstance(Id.create("order_" + entry.getKey(), Order.class), receiver, receiverProduct)
					.setServiceTime(20*60.0)
					.buildWithCalculatedOrderQuantity();

			Collection<Order> orders = new ArrayList<>();
			orders.add(order);
			ReceiverOrder receiverOrder = new ReceiverOrder(receiver.getId(), orders, distributionCarrierId);

			ReceiverPlan plan = ReceiverPlan.Builder.newInstance(receiver, true)
					.addReceiverOrder(receiverOrder)
					.addTimeWindow(TimeWindow.newInstance(6*3600, 8 * 3600))
					.build();

			receiver.addPlan(plan);
			receiver.setSelectedPlan(plan);
			receivers.addReceiver(receiver);
		}
		return receivers;
	}

	private static LSP createTwoEchelonLsp(Scenario scenario, Receivers receivers) {
		Carrier mainRunCarrier = buildSimpleCarrier("mainRunCarrier", "j(0,1)R");
		CarrierSchedulerUtils.setVrpLogic(mainRunCarrier, LSPUtils.LogicOfVrp.shipmentBased);

		Carrier distributionCarrier = buildSimpleCarrier("distributionCarrier", "j(5,3)");
		CarrierSchedulerUtils.setVrpLogic(distributionCarrier, LSPUtils.LogicOfVrp.shipmentBased);

		var mainRunCarrierResource = ResourceImplementationUtils.MainRunCarrierResourceBuilder
				.newInstance(mainRunCarrier)
				.setFromLinkId(mainRunCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
				.setToLinkId(distributionCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
				.setVehicleReturn(ResourceImplementationUtils.VehicleReturn.returnToFromLink)
				.setMainRunCarrierScheduler(ResourceImplementationUtils.createDefaultMainRunCarrierScheduler(scenario))
				.build();

		var hub = ResourceImplementationUtils.TransshipmentHubBuilder.newInstance(
				Id.create("hub_1", LSPResource.class),
				Id.createLinkId("j(5,3)"), scenario)
				.setTransshipmentHubScheduler(ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance()
						.setCapacityNeedFixed(0)
						.setCapacityNeedLinear(0)
						.build())
				.build();
		LSPUtils.setFixedCost(hub, 100.0);

		var distributionResource = ResourceImplementationUtils.DistributionCarrierResourceBuilder
				.newInstance(distributionCarrier)
				.setLocationLinkId(distributionCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
				.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler(scenario))
				.build();

		var mainRunElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("mainRunElement", org.matsim.freight.logistics.LogisticChainElement.class))
				.setResource(mainRunCarrierResource)
				.build();
		var hubElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("hubElement", org.matsim.freight.logistics.LogisticChainElement.class))
				.setResource(hub)
				.build();
		var distribElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("distribElement", org.matsim.freight.logistics.LogisticChainElement.class))
				.setResource(distributionResource)
				.build();
		mainRunElement.connectWithNextElement(hubElement);
		hubElement.connectWithNextElement(distribElement);

		List<LspShipment> lspShipments = buildShipmentsFromReceivers(receivers);

		LogisticChain chain = LSPUtils.LogisticChainBuilder.newInstance(Id.create("chain_1", LogisticChain.class))
				.addLogisticChainElement(mainRunElement)
				.addLogisticChainElement(hubElement)
				.addLogisticChainElement(distribElement)
				.build();
		lspShipments.forEach(sh -> chain.getLspShipmentIds().add(sh.getId()));

		List<LSPResource> resourcesList = List.of(mainRunCarrierResource, hub, distributionResource);
		LogisticChainScheduler scheduler = ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(resourcesList);
		LSPPlan plan = LSPUtils.createLSPPlan();
		plan.addLogisticChain(chain);
		plan.setInitialShipmentAssigner(ResourceImplementationUtils.createSingleLogisticChainShipmentAssigner());

		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create("lsp_1", LSP.class))
				.setLogisticChainScheduler(scheduler)
				.setInitialPlan(plan)
				.build();
		plan.setLSP(lsp);
		lspShipments.forEach(lsp::assignShipmentToLSP);
		return lsp;
	}

	private static Carrier buildSimpleCarrier(String id, String depotLink) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
		VehicleType type = CarrierVehicleType.Builder.newInstance(Id.create(id + "_type", org.matsim.vehicles.VehicleType.class))
				.setCapacity(5000)
				.setFixCost(100)
				.setCostPerDistanceUnit(5.22E-3)
				.setCostPerTimeUnit(0.109)
				.build();
		type.setNetworkMode("car");
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId(id + "_veh"), Id.createLinkId(depotLink), type)
				.setEarliestStart(0).build();
		CarrierCapabilities caps = CarrierCapabilities.Builder.newInstance()
				.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
				.addVehicle(vehicle)
				.build();
		carrier.setCarrierCapabilities(caps);
		CarriersUtils.setJspritIterations(carrier, 50);
		return carrier;
	}

	private static List<LspShipment> buildShipmentsFromReceivers(Receivers receivers) {
		List<LspShipment> shipments = new ArrayList<>();
		int counter = 0;
		for (Receiver receiver : receivers.getReceivers().values()) {
			ReceiverPlan plan = receiver.getSelectedPlan();
			for (ReceiverOrder order : plan.getReceiverOrders()) {
				var builder = LspShipmentUtils.LspShipmentBuilder.newInstance(Id.create("lspShipment_" + counter++, LspShipment.class));
				builder.setFromLinkId(Id.createLinkId("j(0,1)R"));
				builder.setToLinkId(receiver.getLinkId());
				builder.setCapacityDemand((int) Math.max(1, Math.round(order.getReceiverProductOrders().stream()
						.mapToDouble(o -> o.getDailyOrderQuantity() * o.getProduct().getProductType().getRequiredCapacity()).sum())));
				builder.setStartTimeWindow(TimeWindow.newInstance(0, 12 * 3600));
				builder.setEndTimeWindow(plan.getTimeWindows().getFirst());
				builder.setDeliveryServiceTime(order.getReceiverProductOrders().stream()
						.mapToDouble(Order::getServiceDuration).sum());
				shipments.add(builder.build());
			}
		}
		return shipments;
	}
}
