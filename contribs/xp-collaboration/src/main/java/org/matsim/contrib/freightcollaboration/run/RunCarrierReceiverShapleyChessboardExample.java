package org.matsim.contrib.freightcollaboration.run;

import com.google.inject.Provider;
import jakarta.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.CollaborationModule;
import org.matsim.contrib.freightcollaboration.controller.CollaboratorModules;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.contrib.freightcollaboration.utils.LinkFreightAgentToFreightCollaborator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.ExpBetaPlanChanger;
import org.matsim.core.replanning.selectors.KeepSelected;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.*;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.carriers.usecases.chessboard.CarrierTravelDisutilities;
import org.matsim.freight.receiver.*;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.vehicles.VehicleType;

import java.net.URL;
import java.util.*;
import java.util.stream.IntStream;

import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * Chessboard variant of RunCarrierReceiverShapleyAllocationExample.
 * - Uses the MATSim freight chessboard network.
 * - For each run, 10 receivers are placed at random links (locations differ, other attributes stay the same).
 * - Two carrier depot scenarios are evaluated: center and left-corner.
 * - Research designs, allocation methods, and 10 instances are identical to the original example.
 */
public class RunCarrierReceiverShapleyChessboardExample {

	private enum DepotScenario {
		CENTER("center", "i(5,5)R"),
		LEFT_CORNER("left", "i(1,0)");

		final String label;
		final String depotLinkId;

		DepotScenario(String label, String depotLinkId) {
			this.label = label;
			this.depotLinkId = depotLinkId;
		}
	}

	public static void main(String[] args) {

		List<Integer> instances = IntStream.range(0, 10).boxed().toList();

		// Design 1: finer penalty sweep
		double[] penaltySweep = {0.0, 0.003, 0.005, 0.01, 0.015, 0.02, 0.025, 0.03, 0.035, 0.04, 0.045, 0.05};
		double[] allocSweepShort = {0.6, 0.75, 0.9};

		// Design 2: finer allocation factor sweep
		double fixedPenalty = 0.01;
		double[] allocSweepLong = IntStream.range(0, 12).mapToDouble(i -> 0.4 + 0.05 * i).toArray();

		List<AllocationMethodChoice> methods = List.of(
			new AllocationMethodChoice("exactShapley", AllocationModels.SHAPLEY, null),
			new AllocationMethodChoice("marginal", AllocationModels.MARGINAL, null),
			new AllocationMethodChoice("proportional", AllocationModels.PROPORTIONAL, null),
			new AllocationMethodChoice("approxShapMC", AllocationModels.APPROX_SHAPLEY, AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO),
			new AllocationMethodChoice("approxShapStrat", AllocationModels.APPROX_SHAPLEY, AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED)
		);

		for (DepotScenario depotScenario : DepotScenario.values()) {
			for (int instance : instances) {
				for (double penalty : penaltySweep) {
					for (double allocFactor : allocSweepShort) {
						for (AllocationMethodChoice method : methods) {
							runSingleExperiment("penSweep", allocFactor, penalty, method, instance, depotScenario);
						}
					}
				}
				for (double allocFactor : allocSweepLong) {
					for (AllocationMethodChoice method : methods) {
						runSingleExperiment("allocSweep", allocFactor, fixedPenalty, method, instance, depotScenario);
					}
				}
			}
		}
	}

	private static void runSingleExperiment(String tag, double allocationFactor, double receiverPenalty, AllocationMethodChoice methodChoice,
											 int instance, DepotScenario depotScenario) {
		String runId = "%s-%s-af%.2f-p%.3f-%s-i%02d".formatted(depotScenario.label, tag, allocationFactor, receiverPenalty, methodChoice.label, instance);

		Config config = createChessboardConfig(runId);
		config.global().setRandomSeed(4711 + instance); // identical seed across depot scenarios for comparability
		FreightCollaborationConfigGroup freightCfg = createExampleFreightCollaborationConfig();
		freightCfg.ALLOCATION_FACTOR = allocationFactor;
		freightCfg.RECEIVER_RELAXATION_PENALTY = receiverPenalty;
		freightCfg.ALLOCATION_MODEL = methodChoice.model;
		if (methodChoice.model == AllocationModels.APPROX_SHAPLEY && methodChoice.approxMethod != null) {
			freightCfg.APPROX_SHAPLEY_METHOD = methodChoice.approxMethod.name();
		}
		config.addModule(freightCfg);

		runSingleControler(config, depotScenario, instance);
	}

	private static void runSingleControler(Config config, DepotScenario depotScenario, int instance) {
		Scenario scenario = ScenarioUtils.loadScenario(config);

		Carriers carriers = generateExampleCarriers(depotScenario.depotLinkId);
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (var carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);
		FreightCollaborationConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCollaborationConfigGroup.class);

		Receivers receivers = generateRandomReceivers(scenario.getNetwork(), 10, instance);
		ReceiverUtils.setReceivers(receivers, scenario);

		ReceiverOrderGeneration receiverOrderGeneration = new ReceiverOrderGeneration(receivers, carriers,
			Id.createLinkId(depotScenario.depotLinkId), Id.create("carrier1", Carrier.class));
		receiverOrderGeneration.generateAllReceiverOrders();

		writeFreightScenario(scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario), CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		Controler controler = new Controler(scenario);

		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(freightConfigGroup.RECEIVER_FIXED_FEE));
		receiverModule.setReplanningType(ReceiverReplanningType.timeWindow);

		FreightCollaborators freightCollaborators = new FreightCollaborators();
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			var carrierCollaborator = LinkFreightAgentToFreightCollaborator.map(carrier, true);
			freightCollaborators.addFreightCollaborator(carrierCollaborator);
		}
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			var receiverCollaborator = LinkFreightAgentToFreightCollaborator.map(receiver, true);
			freightCollaborators.addFreightCollaborator(receiverCollaborator);
		}

		CollaboratorModules collaboratorModules = new CollaboratorModules(Map.of(CollaboratorRole.RECEIVER, receiverModule,
			CollaboratorRole.CARRIER, new CarrierModule()));
		CollaborationModule collaborationModule = new CollaborationModule(collaboratorModules, freightCollaborators, scenario);

		collaborationModule.installAllCollaboratorModules(controler);
		controler.addOverridingModule(collaborationModule);

		CarrierVehicleTypes carrierVehicleTypes = CarrierVehicleTypes.getVehicleTypes(scenarioCarriers);
		CarrierVehicleTypes types = CarriersUtils.getCarrierVehicleTypes(scenario);
		types.getVehicleTypes().putAll(carrierVehicleTypes.getVehicleTypes());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(CarrierStrategyManager.class).toProvider(new MyCarrierPlanStrategyManagerProvider(types));
				bind(CarrierScoringFunctionFactory.class).to(ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.class);
				bind(ReceiverScoringFunctionFactory.class).to(ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase.class);
			}
		});

		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()), controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();
	}

	private record AllocationMethodChoice(String label, AllocationModels model,
										 AllocationModelApproxShapleyValue.ApproximationMethod approxMethod) { }

	static Config createChessboardConfig(String runId) {
		URL context = ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9");
		Config config = ConfigUtils.createConfig();
		config.setContext(context);
		config.network().setInputFile("grid9x9.xml");
		config.controller().setOutputDirectory("output/chessboardCarrierReceiverCollab/" + runId + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(80);
		return config;
	}

	static FreightCollaborationConfigGroup createExampleFreightCollaborationConfig() {
		CollaborationParamSet collaborationParamSet = new CollaborationParamSet(CollaborationTypes.CARRIER_RECEIVER,
			Set.of(CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION, CollaborationStrategies.COLLABORATION_STATUS_MUTATION));
		var fccg = new FreightCollaborationConfigGroup(Set.of(collaborationParamSet), null);
		fccg.RECEIVER_FIXED_FEE = 100.0;
		fccg.ALLOCATION_MODEL = AllocationModels.APPROX_SHAPLEY;
		fccg.APPROX_SHAPLEY_METHOD = AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED.name();
		fccg.ALLOCATION_FACTOR = 0.9;
		fccg.CARRIER_CHARGED_FEE = 100.0;
		fccg.RECEIVER_RELAXATION_PENALTY = 0.01;
		fccg.setAllocationStrategyString(AllocationValueTypes.COST_SAVINGS.name());
		return fccg;
	}

	static Carriers generateExampleCarriers(String depotLinkId) {
		Carriers carriers = new Carriers();

		VehicleType lightVanType = CarrierVehicleType.Builder.newInstance(Id.create("light", VehicleType.class))
			.setCapacity(3000)
			.setFixCost(100)
			.setCostPerDistanceUnit(4.22E-3)
			.setCostPerTimeUnit(0.089)
			.build();
		lightVanType.setNetworkMode("car");

		VehicleType heavyVanType = CarrierVehicleType.Builder.newInstance(Id.create("heavy", VehicleType.class))
			.setCapacity(5000)
			.setFixCost(150)
			.setCostPerDistanceUnit(5.22E-3)
			.setCostPerTimeUnit(0.109)
			.build();
		heavyVanType.setNetworkMode("car");

		Carrier carrier1 = CarriersUtils.createCarrier(Id.create("carrier1", Carrier.class));
		CarrierVehicle lightVan = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("lightVan1"),
			Id.createLinkId(depotLinkId),
			lightVanType)
			.setEarliestStart(5 * 60 * 60)
			.build();
		CarrierVehicle heavyVan = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("heavyVan1"),
			Id.createLinkId(depotLinkId),
			heavyVanType)
			.setEarliestStart(5 * 60 * 60)
			.build();
		CarrierCapabilities carrierCapabilities1 = CarrierCapabilities.Builder.newInstance()
			.addVehicle(lightVan)
			.addVehicle(heavyVan)
			.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
			.build();
		carrier1.setCarrierCapabilities(carrierCapabilities1);
		carriers.addCarrier(carrier1);

		return carriers;
	}

	static Receivers generateRandomReceivers(Network network, int count, int seed) {
		Receivers receivers = ReceiverUtils.createReceivers();
		List<Id<Link>> candidateLinks = new ArrayList<>(network.getLinks().size());
		network.getLinks().values().stream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.car))
			.forEach(l -> candidateLinks.add(l.getId()));
		candidateLinks.sort(Comparator.comparing(Id::toString)); // deterministic base order

		if (candidateLinks.size() < count) {
			throw new IllegalStateException("Not enough links in network to place receivers");
		}

		Collections.shuffle(candidateLinks, new Random(13_579 + seed));

		for (int i = 0; i < count; i++) {
			Id<Link> location = candidateLinks.get(i);
			Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver_%02d".formatted(i), Receiver.class));
			receiver.setLinkId(location);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receivers.addReceiver(receiver);
		}

		return receivers;
	}

	public static class ReceiverOrderGeneration {
		private final Carriers carriers;
		private final Receivers receivers;
		private final Id<Link> carrierOriginId;
		private final Id<Carrier> carrierId;

		Map<String, ProductType> productTypes = new HashMap<>();
		Map<Id<Receiver>, ReceiverPlan> receiverPlans = new HashMap<>();

		public ReceiverOrderGeneration(Receivers receivers, Carriers carriers, Id<Link> carrierOriginId, Id<Carrier> carrierId) {
			this.receivers = receivers;
			this.carriers = carriers;
			this.carrierOriginId = carrierOriginId;
			this.carrierId = carrierId;
		}

		void generateProductTypes(){
			ProductType productType1 = ReceiverUtils.createAndGetProductType(this.receivers, Id.create("productType1", ProductType.class), carrierOriginId);
			productType1.setDescription("Product Type 1");
			productType1.setRequiredCapacity(5);
			this.productTypes.put("productType1", productType1);
		}

		public void generateAllReceiverOrders(){
			generateProductTypes();
			for (Receiver receiver : this.receivers.getReceivers().values()) {
				generateReceiverOrders(receiver, true);
			}
		}

		void generateReceiverOrders(Receiver receiver, boolean status){
			ReceiverProduct receiverProduct1 = ReceiverProduct.Builder.newInstance()
				.setProductType(this.productTypes.get("productType1"))
				.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 500))
				.build();
			receiver.addProduct(receiverProduct1);

			Collection<Order> orders1 = new ArrayList<>();
			Order Order1 = Order.Builder.newInstance(Id.create("Order1", Order.class), receiver, receiverProduct1)
				.setServiceTime(20*60)
				.buildWithCalculatedOrderQuantity();
			orders1.add(Order1);

			ReceiverOrder receiverOrder1 = new ReceiverOrder(receiver.getId(), orders1, carrierId);

			ReceiverPlan receiverPlan = ReceiverPlan.Builder.newInstance(receiver, status)
				.addReceiverOrder(receiverOrder1)
				.addTimeWindow(TimeWindow.newInstance(6*60*60, 8*60*60))
				.build();

			this.receiverPlans.put(receiver.getId(), receiverPlan);
			receiver.addPlan(receiverPlan);
			receiver.setSelectedPlan(receiverPlan);
			receiver.getAttributes().putAttribute(ReceiverUtils.ATTR_RECEIVER_TW_COST, 1);

			convertReceiverOrdersToInitialCarrierShipments(this.carriers, receiverOrder1, receiverPlan);
		}

		private void convertReceiverOrdersToInitialCarrierShipments(Carriers carriers, ReceiverOrder receiverOrder, ReceiverPlan receiverPlan) {
			Carrier carrier = carriers.getCarriers().get(receiverOrder.getCarrierId());
			if (carrier == null) {
				throw new IllegalStateException("Carrier not found: " + receiverOrder.getCarrierId());
			}

			Id<CarrierShipment> shipmentId = Id.create("shipment_" + receiverOrder.getReceiverId(), CarrierShipment.class);
			Receiver receiver = this.receivers.getReceivers().get(receiverOrder.getReceiverId());
			Id<Link> toLink = receiver != null ? receiver.getLinkId() : carrierOriginId;

			CarrierShipment shipment = CarrierShipment.Builder.newInstance(
					shipmentId,
					carrierOriginId,
					toLink,
					1)
				.setPickupServiceTime(300)
				.setDeliveryServiceTime(300)
				.build();

			CarriersUtils.addShipment(carrier, shipment);
		}
	}

	private static class MyCarrierPlanStrategyManagerProvider implements Provider<CarrierStrategyManager> {
		private final CarrierVehicleTypes types;
		@Inject
		private org.matsim.api.core.v01.network.Network network;
		@Inject
		private LeastCostPathCalculatorFactory leastCostPathCalculatorFactory;
		@Inject
		private Map<String, TravelTime> modeTravelTimes;

		MyCarrierPlanStrategyManagerProvider(CarrierVehicleTypes types) {
			this.types = types;
		}

		@Override
		public CarrierStrategyManager get() {
			final CarrierStrategyManager strategyManager = CarrierControllerUtils.createDefaultCarrierStrategyManager();
			strategyManager.setMaxPlansPerAgent(5);
			{
				GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>(new ExpBetaPlanChanger.Factory<CarrierPlan, Carrier>().build());
				strategyManager.addStrategy(strategy, null, 1.0);
			}
			{
				final TravelDisutility travelDisutility = CarrierTravelDisutilities.createBaseDisutility(types, modeTravelTimes.get(TransportMode.car));
				final LeastCostPathCalculator router = leastCostPathCalculatorFactory.createPathCalculator(network, travelDisutility, modeTravelTimes.get(TransportMode.car));

				GenericPlanStrategyImpl<CarrierPlan, Carrier> strategy = new GenericPlanStrategyImpl<>(new KeepSelected<>());
				strategy.addStrategyModule(new CarrierTimeAllocationMutator.Factory().build());
				strategy.addStrategyModule(new CarrierReRouteVehicles.Factory(router, network, modeTravelTimes.get(TransportMode.car)).build());
				strategyManager.addStrategy(strategy, null, 0.5);
			}
			return strategyManager;
		}
	}
}
