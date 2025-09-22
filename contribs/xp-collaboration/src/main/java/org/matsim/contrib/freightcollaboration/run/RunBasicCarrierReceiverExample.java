package org.matsim.contrib.freightcollaboration.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controler.CollaborationModule;
import org.matsim.contrib.freightcollaboration.controler.CollaboratorModules;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.receiver.*;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.vehicles.VehicleType;

import java.net.URL;
import java.util.*;

import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

public class RunBasicCarrierReceiverExample {

	public static void main(String[] args) {
		Config config = createExampleConfigWithDefaultNetwork();

		// add freight collaboration config group
		// Now using proper CollaborationStrategies enum instead of strings
		CollaborationParamSet collaborationParamSet = new CollaborationParamSet(CollaborationTypes.CARRIER_RECEIVER,
			Set.of(CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION, CollaborationStrategies.COLLABORATION_STATUS_MUTATION));
		/** @FIXME: It seems very strange to specify the input network file here again, because it is already specified in the main config file.
		 * But in the final version, users should specify all related params in the freight collaboration config group.
		 * Hence, it is still kept here for now.
		 */
		FreightCollaborationConfigGroup fcConfigGroup = new FreightCollaborationConfigGroup(Set.of(collaborationParamSet), config.network().getInputFile());
		config.addModule(fcConfigGroup); // add the freight collaboration config group into the main config

		// Create scenario
		Scenario scenario = ScenarioUtils.loadScenario(config);

		// Generate carriers
		Carriers carriers = generateExampleCarriers();
		// Add carriers into scenario - FIXED: Actually add the generated carriers
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (var carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		// Add receiver module (config group)
		// FIXME: The user is not supposed to do this manually, it should be done by the freight collaboration module/config group automatically.
		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);
//		config.addModule(receiverConfigGroup);  // This line is not needed, as the module is already added by ConfigUtils.addOrGetModule()
		// Generate receivers
		Receivers receivers = generateExampleReceivers();
		// Add receivers into scenario
		ReceiverUtils.setReceivers(receivers, scenario);

		// Generate receiver orders and plans and carrier shipments
		ReceiverOrderGeneration receiverOrderGeneration = new ReceiverOrderGeneration(receivers, carriers);
		receiverOrderGeneration.generateAllReceiverOrders();

		// Write the freight scenario into output directory
		writeFreightScenario(scenario);
		// Ensure that the receivers are linked to the carriers
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario), CarriersUtils.getCarriers(scenario));

		// Create coalition and add carriers and receivers into the coalition
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		// Create a controler
		Controler controler = new Controler(scenario);

		// Add proper ReceiverModule to handle receiver simulation
		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(100.0));
		receiverModule.setReplanningType(ReceiverReplanningType.timeWindow);
		// Add collaboration modules
		CollaboratorModules collaboratorModules = new CollaboratorModules(Map.of(CollaboratorRole.RECEIVER, receiverModule));
		CollaborationModule collaborationModule = new CollaborationModule(collaboratorModules);
		// Install all collaborator modules
		collaborationModule.installAllCollaboratorModules(controler);
		// Install the collaboration module itself
		controler.addOverridingModule(collaborationModule);

		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()), controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();

	}



	public static Config createExampleConfigWithDefaultNetwork() {
		URL context = ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9");
		Config config = ConfigUtils.createConfig();
		config.setContext(context);
		config.network().setInputFile("grid9x9.xml");
		config.controller().setOutputDirectory("output/basic-carrier-receiver-example/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(10);
		return config;
	}

	public static Carriers generateExampleCarriers() {
		Carriers carriers = new Carriers();
		// Create 2 vehicle types
		VehicleType lightVanType = CarrierVehicleType.Builder.newInstance(Id.create("light", VehicleType.class))
			.setCapacity(3000)  // in kg
			.setFixCost(100)
			.setCostPerDistanceUnit(4.22E-3)
			.setCostPerTimeUnit(0.089)
			.build();
		lightVanType.setNetworkMode("car");

		VehicleType heavyVanType = CarrierVehicleType.Builder.newInstance(Id.create("heavy", VehicleType.class))
			.setCapacity(5000)  // in kg
			.setFixCost(150)
			.setCostPerDistanceUnit(5.22E-3)
			.setCostPerTimeUnit(0.109)
			.build();
		heavyVanType.setNetworkMode("car");

		// Create Carrier 1
		Carrier carrier1 = CarriersUtils.createCarrier(Id.create("carrier1", Carrier.class));
		CarrierVehicle lightVan = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId("lightVan1"),
				Id.createLinkId("i(3,4)"),
				lightVanType)
			.build();
		CarrierVehicle heavyVan = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId("heavyVan1"),
				Id.createLinkId("i(3,4)"),
				heavyVanType)
			.build();
		CarrierCapabilities carrierCapabilities1 = CarrierCapabilities.Builder.newInstance()
			.addVehicle(lightVan)
			.addVehicle(heavyVan)
			.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
			.build();
		carrier1.setCarrierCapabilities(carrierCapabilities1);
		carriers.addCarrier(carrier1);

		// Create Carrier 2
		Carrier carrier2 = CarriersUtils.createCarrier(Id.create("carrier2", Carrier.class));
		CarrierVehicle lightVan2 = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId("lightVan2"),
				Id.createLinkId("i(7,7)R"),
				lightVanType)
			.build();
		CarrierVehicle heavyVan2 = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId("heavyVan2"),
				Id.createLinkId("i(7,7)R"),
				heavyVanType)
			.build();
		CarrierCapabilities carrierCapabilities2 = CarrierCapabilities.Builder.newInstance()
			.addVehicle(lightVan2)
			.addVehicle(heavyVan2)
			.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
			.build();
		carrier2.setCarrierCapabilities(carrierCapabilities2);
		carriers.addCarrier(carrier2);

		return carriers;
	}

	public static Receivers generateExampleReceivers() {

		Receivers receivers = ReceiverUtils.createReceivers();

		// 3 receivers in the coalition
		Set<Id<Link>> collaborativeReceiversLocations = Set.of(
			Id.createLinkId("j(4,1)R"),
			Id.createLinkId("j(8,3)R"),
			Id.createLinkId("i(9,2)"));

		for (Id<Link> location : collaborativeReceiversLocations) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create("collaborativeReceiver_" + location, Receiver.class));
			receiver.setLinkId(location);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receivers.addReceiver(receiver);
		}

		// 2 receivers not in the coalition
		Set<Id<Link>> nonCollaborativeReceiversLocations = Set.of(
			Id.createLinkId("j(1,7)"),
			Id.createLinkId("j(0,4)R"));

		for (Id<Link> location : nonCollaborativeReceiversLocations) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create("nonCollaborativeReceiver_" + location, Receiver.class));
			receiver.setLinkId(location);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, false);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, false);
			receivers.addReceiver(receiver);
		}
		return receivers;
	}

	public static class ReceiverOrderGeneration {
		static Id<Link> carrier1OriginId = Id.createLinkId("i(3,4)");
		static Id<Link> carrier2OriginId = Id.createLinkId("i(7,7)R");

		static Id<Carrier> carrier1Id = Id.create("carrier1", Carrier.class);
		static Id<Carrier> carrier2Id = Id.create("carrier2", Carrier.class);

		private final Carriers carriers;
		private final Receivers receivers;

		Map<String, ProductType> productTypes = new HashMap<>();
		Map<Id<Receiver>, ReceiverPlan> receiverPlans = new HashMap<>();

		public ReceiverOrderGeneration(Receivers receivers, Carriers carriers) {
			this.receivers = receivers;
			this.carriers = carriers;
		}

		/**
		 * Create 2 Product Types:
		 *  ProductType 1:
		 *  ProductType 2:
		 */
		void generateProductTypes(){
			ProductType productType1 = ReceiverUtils.createAndGetProductType(this.receivers, Id.create("productType1", ProductType.class), carrier1OriginId);
			productType1.setDescription("Product Type 1");
			productType1.setRequiredCapacity(5);

			ProductType productType2 = ReceiverUtils.createAndGetProductType(this.receivers, Id.create("productType2", ProductType.class), carrier2OriginId);
			productType2.setDescription("Product Type 2");
			productType2.setRequiredCapacity(10);

			this.productTypes.put("productType1", productType1);
			this.productTypes.put("productType2", productType2);
		}

		public void generateAllReceiverOrders(){
			generateProductTypes();
			generateCollaborativeReceiverOrders();
			generateNonCollaborativeReceiverOrders();
		}

		void generateReceiverOrders(Receiver receiver, boolean status){
			// Generate receiver product for collaborative receivers
			ReceiverProduct receiverProduct1 = ReceiverProduct.Builder.newInstance()
				.setProductType(this.productTypes.get("productType1"))
				.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 500))
				.build();
			receiver.addProduct(receiverProduct1);

			ReceiverProduct receiverProduct2 = ReceiverProduct.Builder.newInstance()
				.setProductType(this.productTypes.get("productType2"))
				.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(200, 1000))
				.build();
			receiver.addProduct(receiverProduct2);

			// generate orders for collaborative receivers
			Collection<Order> orders1 = new ArrayList<>();
			Collection<Order> orders2 = new ArrayList<>();

			Order Order1 = Order.Builder.newInstance(Id.create("Order1", Order.class), receiver, receiverProduct1)
				.setServiceTime(5*60)
				.buildWithCalculatedOrderQuantity();

			Order Order2 = Order.Builder.newInstance(Id.create("Order2", Order.class), receiver, receiverProduct2)
				.setServiceTime(10*60)
				.buildWithCalculatedOrderQuantity();

			orders1.add(Order1);
			orders2.add(Order2);

			// assign orders to receiver
			ReceiverOrder receiverOrder1 = new ReceiverOrder(receiver.getId(), orders1, carrier1Id);
			ReceiverOrder receiverOrder2 = new ReceiverOrder(receiver.getId(), orders2, carrier2Id);

			ReceiverPlan receiverPlan = ReceiverPlan.Builder.newInstance(receiver, status)
				.addReceiverOrder(receiverOrder1)
				.addReceiverOrder(receiverOrder2)
				.addTimeWindow(TimeWindow.newInstance(6*60*60, 10*60*60))
				.addTimeWindow(TimeWindow.newInstance(14*60*60, 18*60*60))
				.build();

			this.receiverPlans.put(receiver.getId(), receiverPlan);

			// add the receiver plan to the receiver
			receiver.addPlan(receiverPlan);
			// set the selected plan for the receiver
			receiver.setSelectedPlan(receiverPlan);

			// add the time window cost to the receiver
			receiver.getAttributes().putAttribute(
				ReceiverUtils.ATTR_RECEIVER_TW_COST,
				1);

			convertReceiverOrdersToInitialCarrierShipments(this.carriers, receiverOrder1, receiverPlan);
			convertReceiverOrdersToInitialCarrierShipments(this.carriers, receiverOrder2, receiverPlan);
		}

		void generateCollaborativeReceiverOrders(){
			for (Receiver receiver : this.receivers.getReceivers().values()) {
				if ((boolean) receiver.getAttributes().getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS)) {
					generateReceiverOrders(receiver, true);
				}
			}
		}

		void generateNonCollaborativeReceiverOrders(){
			for (Receiver receiver : this.receivers.getReceivers().values()) {
				if (!(boolean) receiver.getAttributes().getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS)) {
					generateReceiverOrders(receiver, false);
				}
			}
		}

		public Map<String, ProductType> getProductTypes() {
			return productTypes;
		}

		public Map<Id<Receiver>, ReceiverPlan> getReceiverPlans() {
			return receiverPlans;
		}

		/**
		 * Custom implementation to replace ReceiverChessboardScenario.convertReceiverOrdersToInitialCarrierShipments()
		 * This method converts receiver orders to carrier shipments with compatible API calls.
		 */
		private void convertReceiverOrdersToInitialCarrierShipments(Carriers carriers, ReceiverOrder receiverOrder, ReceiverPlan receiverPlan) {
			Carrier carrier = carriers.getCarriers().get(receiverOrder.getCarrierId());
			if (carrier == null) {
				throw new IllegalStateException("Carrier not found: " + receiverOrder.getCarrierId());
			}

			// For now, create a simple shipment based on the receiver order
			// Since ReceiverOrder might contain multiple orders, create one shipment per order
			Id<CarrierShipment> shipmentId = Id.create("shipment_" + receiverOrder.getReceiverId().toString() + "_" + receiverOrder.getCarrierId().toString(), CarrierShipment.class);

			// Get the receiver to access its link ID
			Receiver receiver = this.receivers.getReceivers().get(receiverOrder.getReceiverId());
			Id<Link> toLink = receiver != null ? receiver.getLinkId() : Id.createLinkId("defaultLink");

			// For simplicity, assume one main delivery per ReceiverOrder
			CarrierShipment shipment = CarrierShipment.Builder.newInstance(
					shipmentId,
					Id.createLinkId("i(3,4)"), // from - use a default origin link for now
					toLink, // to - receiver's link ID
					1 // size - simplified for now
				)
				.setPickupServiceTime(300) // 5 minutes pickup
				.setDeliveryServiceTime(300) // 5 minutes delivery
				.build();

			CarriersUtils.addShipment(carrier, shipment);
		}
	}
}
