package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.CollaborationModule;
import org.matsim.contrib.freightcollaboration.controller.CollaboratorModules;
import org.matsim.contrib.freightcollaboration.utils.LinkFreightAgentToFreightCollaborator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.ExpBetaPlanChanger;
import org.matsim.core.replanning.selectors.ExpBetaPlanSelector;
import org.matsim.core.replanning.selectors.GenericWorstPlanForRemovalSelector;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierControllerUtils;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.logistics.*;
import org.matsim.freight.logistics.resourceImplementations.CarrierSchedulerUtils;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.resourceImplementations.TransshipmentHubResource;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.logistics.shipment.LspShipmentUtils;
import org.matsim.freight.receiver.*;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.vehicles.VehicleType;

import java.nio.file.Paths;
import java.util.*;

import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * Minimal runnable example of LSP–Receiver collaboration with cost allocation.
 * Uses a lightweight pseudo-sim (no full logistics chain execution) but exercises the allocation pipeline.
 */
public class RunLspReceiverCollaborationExample {

	public static void main(String[] args) {
		Config config = createConfig();
		Scenario scenario = ScenarioUtils.loadScenario(config);

		// Build LSP + receivers (with orders)
		Receivers receivers = createExampleReceiversWithOrders();
		ReceiverUtils.setReceivers(receivers, scenario);

		LSP lsp = createTwoEchelonLsp(scenario, receivers);

		// IMPORTANT: Schedule the LSP to convert LSP shipments to carrier shipments
		lsp.scheduleLogisticChains();

		// Register LSPs in scenario (also adds carrier resources)
		LSPs lsps = new LSPs(Set.of(lsp));
		LSPUtils.addLSPs(scenario, lsps);

		// Link receivers to carriers and create coalition
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario), CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		// Persist simple freight scenario
		writeFreightScenario(scenario);

		// Map to FreightCollaborators
		FreightCollaborators freightCollaborators = new FreightCollaborators();
		FreightCollaborator<LSP> lspCollaborator = LinkFreightAgentToFreightCollaborator.map(lsp, true);
		freightCollaborators.addFreightCollaborator(lspCollaborator);
		for (Receiver receiver : receivers.getReceivers().values()) {
			FreightCollaborator<Receiver> rc = LinkFreightAgentToFreightCollaborator.map(receiver, true);
			freightCollaborators.addFreightCollaborator(rc);
		}

		// Coalition manager needs to know LSP/Receiver roles
		CollaboratorModules collaboratorModules = new CollaboratorModules(Map.of(
			CollaboratorRole.RECEIVER, new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(100.0)),
			CollaboratorRole.LSP, new LSPModule(),
			CollaboratorRole.CARRIER, new CarrierModule()
		));

		CollaborationModule collaborationModule = new CollaborationModule(collaboratorModules, freightCollaborators, scenario);

		Controler controler = new Controler(scenario);
		controler.addOverridingModule(collaborationModule);
		collaborationModule.installAllCollaboratorModules(controler);


		// Bind coalition manager, listeners, and LSP components
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				// Provide LSP scorer factory - using simple scorer for collaboration scoring
				bind(LSPScorerFactory.class).toInstance(MyLSPScorer::new);

				// Provide LSP strategy manager - not needed for this simple example
//				bind(LSPStrategyManager.class).toInstance(new LSPModule.LSPStrategyManagerEmptyImpl());

				// Provide LSP strategy manager with keep-best strategy
				bind(LSPStrategyManager.class).toProvider( () -> {
					LSPStrategyManager strategyManager = new LSPStrategyManagerImpl();
					strategyManager.addStrategy( new GenericPlanStrategyImpl<>( new ExpBetaPlanSelector<>(new ScoringConfigGroup())), null, 1);
//					strategyManager.addStrategy( ProximityStrategyFactory.createStrategy(scenario.getNetwork()), null, 1);
					strategyManager.setMaxPlansPerAgent(5);
					strategyManager.setPlanSelectorForRemoval( new GenericWorstPlanForRemovalSelector<>());
					return strategyManager;
				});

				// Provide carrier strategy manager with chose plan strategy
				bind(CarrierStrategyManager.class).toProvider( () -> {
					CarrierStrategyManager strategyManager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
					strategyManager.setMaxPlansPerAgent(5);
					{
						GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>( new ExpBetaPlanChanger.Factory<CarrierPlan,Carrier>().build() );
						strategyManager.addStrategy(strategy, null, 1.0);
					}
					{
						GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>( new BestPlanSelector<>() );
						strategyManager.addStrategy(strategy, null, 1);
					}
					return strategyManager;
				});

				// bind carrier score factory
				bind(CarrierScoringFunctionFactory.class).to(ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.class);
			}
		});
		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()), controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();
	}

	private static Config createConfig() {
		Config config = ConfigUtils.createConfig();
//		Path networkPath = Paths.get("input", "example_octagonal_network.xml").toAbsolutePath().normalize();
//		config.network().setInputFile(networkPath.toString());
		config.network().setInputFile(String.valueOf(IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9"), "grid9x9.xml")));
		config.controller().setOutputDirectory(Paths.get("output", "twoEchelonLspReceiverCollab") +  "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(10);
		config.controller().setFirstIteration(0);
//		config.controller().setWriteEventsInterval(0);
//		config.controller().setWritePlansInterval(0);

		// Configure freight carriers (required by LSPModule)
		FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
		freightConfig.setTimeWindowHandling(FreightCarriersConfigGroup.TimeWindowHandling.ignore);

		// configure freight receivers
		ReceiverConfigGroup freightReceiversConfigGroup = ConfigUtils.addOrGetModule(config, ReceiverConfigGroup.class);
		freightReceiversConfigGroup.setReceiverReplanningInterval(2000);
		freightReceiversConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);

		CollaborationParamSet paramSet = new CollaborationParamSet(CollaborationTypes.LSP_RECEIVER,
			Set.of(org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies.COLLABORATION_STATUS_MUTATION));
		paramSet.setAdditionalParams( Map.of("MAIN_RUN_CARRIER_IDS", Set.of("mainRunCarrier")));
		FreightCollaborationConfigGroup fccg = new FreightCollaborationConfigGroup(Set.of(paramSet), null);
		fccg.ALLOCATION_MODEL = AllocationModels.SHAPLEY;
		fccg.APPROX_SHAPLEY_METHOD = AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO.name();
		fccg.ALLOCATION_FACTOR = 0.8;
		fccg.RECEIVER_RELAXATION_PENALTY = 0.01;
		fccg.setAllocationStrategyString(org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes.COST_SAVINGS.name());
		config.addModule(fccg);
		return config;
	}

	private static LSP createTwoEchelonLsp(Scenario scenario, Receivers receivers) {
		// Carriers for 1st echelon and distribution carrier (2nd echelon)
		Carrier mainRunCarrier = buildSimpleCarrier("mainRunCarrier", "j(0,1)R");
		CarrierSchedulerUtils.setVrpLogic(mainRunCarrier, LSPUtils.LogicOfVrp.serviceBased);

		Carrier distributionCarrier = buildSimpleCarrier("distributionCarrier", "j(5,3)");
		CarrierSchedulerUtils.setVrpLogic(distributionCarrier, LSPUtils.LogicOfVrp.shipmentBased);

		// Resources and schedulers
		var mainRunCarrierResource = ResourceImplementationUtils.MainRunCarrierResourceBuilder
			.newInstance(mainRunCarrier)
			.setFromLinkId(mainRunCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
			.setToLinkId(distributionCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
			.setVehicleReturn(ResourceImplementationUtils.VehicleReturn.returnToFromLink)
			.setMainRunCarrierScheduler(ResourceImplementationUtils.createDefaultMainRunCarrierScheduler(scenario))
			.build();

		var hub = ResourceImplementationUtils.TransshipmentHubBuilder.newInstance(
				Id.create("hub_1", org.matsim.freight.logistics.LSPResource.class),
				Id.createLinkId("j(5,3)"), scenario)
			.setTransshipmentHubScheduler(ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance()
				.setCapacityNeedFixed(0)
				.setCapacityNeedLinear(0)
				.build())
			.build();
		// set fixed cost for using the hub
		LSPUtils.setFixedCost(hub, 100.0);

		var distributionResource = ResourceImplementationUtils.DistributionCarrierResourceBuilder
			.newInstance(distributionCarrier)
			.setLocationLinkId(distributionCarrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId())
			.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler(scenario))
			.build();

		// Chain elements
		var mainRunElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("mainRunElement", LogisticChainElement.class))
			.setResource(mainRunCarrierResource)
			.build();
		var hubElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("hubElement", LogisticChainElement.class))
			.setResource(hub)
			.build();
		var distribElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("distribElement", LogisticChainElement.class))
			.setResource(distributionResource)
			.build();
		// Since it is a forward chain, connect elements
		mainRunElement.connectWithNextElement(hubElement);
		hubElement.connectWithNextElement(distribElement);

		// Build shipments from receiver orders
		List<LspShipment> lspShipments = buildShipmentsFromReceivers(receivers);

		// Chain wiring
		LogisticChain chain = LSPUtils.LogisticChainBuilder.newInstance(Id.create("chain_1", LogisticChain.class))
			.addLogisticChainElement(mainRunElement)
			.addLogisticChainElement(hubElement)
			.addLogisticChainElement(distribElement)
			.build();
		lspShipments.forEach(sh -> chain.getLspShipmentIds().add(sh.getId()));

		// Plan and LSP - create scheduler with resources list
		List<LSPResource> resourcesList = List.of(mainRunCarrierResource, hub, distributionResource);
		LogisticChainScheduler scheduler = ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(resourcesList);
		LSPPlan plan = LSPUtils.createLSPPlan();
		plan.addLogisticChain(chain);
		plan.setInitialShipmentAssigner(ResourceImplementationUtils.createSingleLogisticChainShipmentAssigner());
//		plan.setScore(-2000.0); // baseline cost placeholder

		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create("lsp_1", LSP.class))
			.setLogisticChainScheduler(scheduler)
			.setInitialPlan(plan)
			.build();
		plan.setLSP(lsp);

		// assign shipments to LSP (adds them into chains and shipment plans)
		lspShipments.forEach(lsp::assignShipmentToLSP);

		// Schedule LSP to convert LSP shipments to carrier shipments
//		lsp.scheduleLogisticChains();

		return lsp;
	}

	private static Carrier buildSimpleCarrier(String id, String depotLink) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
		VehicleType type = CarrierVehicleType.Builder.newInstance(Id.create(id + "_type", VehicleType.class))
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

		// Set jsprit iterations for carrier routing
		CarriersUtils.setJspritIterations(carrier, 200);


		return carrier;
	}

	private static List<LspShipment> buildShipmentsFromReceivers(Receivers receivers) {
		List<LspShipment> shipments = new ArrayList<>();
		int counter = 0;
		for (Receiver receiver : receivers.getReceivers().values()) {
			ReceiverPlan plan = receiver.getSelectedPlan();
			for (ReceiverOrder order : plan.getReceiverOrders()) {
				// Build a logistics shipment from origin (carrier depot) to receiver
				var builder = LspShipmentUtils.LspShipmentBuilder.newInstance(Id.create("lspShipment_" + counter++, LspShipment.class));
				builder.setFromLinkId(Id.createLinkId("j(0,1)R")); // main run carrier depot
				builder.setToLinkId(receiver.getLinkId());
				builder.setCapacityDemand((int) Math.max(1, Math.round(order.getReceiverProductOrders().stream()
					.mapToDouble(o -> o.getDailyOrderQuantity() * o.getProduct().getProductType().getRequiredCapacity()).sum())));
				builder.setStartTimeWindow(TimeWindow.newInstance(0, 12 * 3600));
				builder.setEndTimeWindow(TimeWindow.newInstance(6 * 3600, 8 * 3600));
				builder.setDeliveryServiceTime(order.getReceiverProductOrders().stream()
					.mapToDouble(Order::getServiceDuration).sum());
				shipments.add(builder.build());
			}
		}
		return shipments;
	}

	private static Receivers createExampleReceiversWithOrders() {
		Receivers receivers = ReceiverUtils.createReceivers();
		Id<Carrier> distributionCarrierId = Id.create("distributionCarrier", Carrier.class);
		// Three receivers on the ring
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

			// create orders (one product per receiver)
			ProductType pType = ReceiverUtils.createAndGetProductType(receivers, Id.create("product_" + entry.getKey(), ProductType.class),
				Id.createLinkId("j(0,1)R"));
			pType.setRequiredCapacity(10);

			// Create receiver product
			ReceiverProduct receiverProduct = ReceiverProduct.Builder.newInstance()
				.setProductType(pType)
				.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 300.0))
				.build();
			receiver.addProduct(receiverProduct);

			// Create order for the product
			Order order = Order.Builder.newInstance(Id.create("order_" + entry.getKey(), Order.class), receiver, receiverProduct)
				.setServiceTime(20*60.0)
				.buildWithCalculatedOrderQuantity();

			// Create collection of orders for the receiver order
			Collection<Order> orders = new ArrayList<>();
			orders.add(order);
			ReceiverOrder receiverOrder = new ReceiverOrder(receiver.getId(), orders, distributionCarrierId);

			// Build receiver plan with builder pattern
			ReceiverPlan plan = ReceiverPlan.Builder.newInstance(receiver, true)
				.addReceiverOrder(receiverOrder)
				.addTimeWindow(TimeWindow.newInstance(6*3600, 8 * 3600))
//				.setScore(0.0)
				.build();

			receiver.addPlan(plan);
			receiver.setSelectedPlan(plan);
			receivers.addReceiver(receiver);
		}
		return receivers;
	}

	static class MyLSPScorer implements LSPScorer {
		final Logger logger = LogManager.getLogger(MyLSPScorer.class);
		private double score = 0;
		private LSP lsp;

		@Override
		public void reset(int iteration) {
			score = 0.;
		}

		@Override
		public double getScoreForCurrentPlan() {
			scoreLspCarriers();
			scoreHub();
			scoreMissingShipments();
			return score;
		}

		private void scoreLspCarriers() {
			var lspPlan = lsp.getSelectedPlan();
			for (LogisticChain logisticChain : lspPlan.getLogisticChains()) {
				for (LogisticChainElement logisticChainElement : logisticChain.getLogisticChainElements()) {
					if (logisticChainElement.getResource() instanceof LSPCarrierResource carrierResource) {
						var carriersScore = carrierResource.getCarrier().getSelectedPlan().getScore();
						if (carriersScore != null) {
							score = score + carriersScore;
						}
					}
				}
			}
		}

		/**
		 * If a hub resource is in the selected plan of the LSP, it will get scored.
		 *
		 * <p>This is somehow a quickfix, because the hubs do **not** have any own events yet. This needs
		 * to be implemented later KMT oct'22
		 */
		private void scoreHub() {
			var lspPlan = lsp.getSelectedPlan();
			for (LogisticChain logisticChain : lspPlan.getLogisticChains()) {
				for (LogisticChainElement logisticChainElement : logisticChain.getLogisticChainElements()) {
					if (logisticChainElement.getResource() instanceof TransshipmentHubResource hub) {
						score = score - LSPUtils.getFixedCost(hub);
					}
				}
			}
		}

		private void scoreMissingShipments() {
			LSPPlan lspPlan = lsp.getSelectedPlan();
			int lspPlanShipmentCount =
				lspPlan.getLogisticChains().stream()
					.mapToInt(logisticChain -> logisticChain.getLspShipmentIds().size())
					.sum();
			int shipmentCountDifference = lsp.getLspShipments().size() - lspPlanShipmentCount;
			if (shipmentCountDifference > 0) {
				logger.error(
					"LspPlan contains less shipments than LSP, "
						+ "shipments probably lost during replanning.");
				score -= 10000 * shipmentCountDifference;
			}
		}

		@Override
		public void setEmbeddingContainer(LSP pointer) {
			this.lsp = pointer;
		}
	}

}
