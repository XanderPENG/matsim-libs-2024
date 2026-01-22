package org.matsim.contrib.freightcollaboration.run;

import com.google.inject.Provider;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
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
import org.matsim.core.utils.collections.Tuple;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.*;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.carriers.usecases.chessboard.CarrierTravelDisutilities;
import org.matsim.freight.receiver.*;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.vehicles.VehicleType;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
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
public class RunCarrierReceiverCollabChessboardExample {
	// Logger
	private static Logger logger = LogManager.getLogger(RunCarrierReceiverCollabChessboardExample.class);
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

	/*
		Three spatial distributions of generated customer locations:
			1. Fully random: randomly generated customer locations within the area
			2. clustered: generate 10 customers within a 3*3 (3 single links) sub-square (also within the area)
			3. Hierarchy dispersed: generate 1, 3, 6 customers for each ring with a fixed center location (i(5,4) or i(5,5)R)
		The feasible area for generating customer is fixed, within the square of:
			upper left: j(2,7)R
			bottem left: i(3,2)
			bottem right: i(7,2)
			upper right: j(7,2)
	 */
	private enum CustomerDistributionScenario {
		// FULLY_RANDOM,
		CLUSTERED,
		DISPERSED;
	}


	public static void main(String[] args) {

		final Duration workWindow = Duration.ofHours(3);
		final Duration breakWindow = Duration.ofMinutes(15);
		Instant windowStart = Instant.now();

		List<Integer> instances = IntStream.range(0, 50).boxed().toList();

		/* Design 1 - finer penalty sweep: 0, 1, 2, 3, 5, 10, 20, 35, 50, 60, 80 100 euro/hr
		 * which equals to approx. 0.0003, 0.0006, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.0167, 0.0222, 0.028 euro/sec
		 */
		double[] penaltySweep = {0, 0.0003, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.0167, 0.0222, 0.028};
//		double[] allocSweepShort = {0.6, 0.75, 0.9};
		double[] allocSweepShort = {0.8};
		// Design 2: finer allocation factor sweep
		double fixedPenalty = 0.005;
		double[] allocSweepLong = IntStream.range(0, 12).mapToDouble(i -> 0.4 + 0.05 * i).toArray();

		List<AllocationMethodChoice> methods = List.of(
			new AllocationMethodChoice("exactShapley", AllocationModels.SHAPLEY, null)
			// NOTE: Other methods commented out to reduce computation time, only exact Shapley is run for test case
//			new AllocationMethodChoice("marginal", AllocationModels.MARGINAL, null),
//			new AllocationMethodChoice("proportional", AllocationModels.PROPORTIONAL, null),
//			new AllocationMethodChoice("approxShapMC", AllocationModels.APPROX_SHAPLEY, AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO),
//			new AllocationMethodChoice("approxShapStrat", AllocationModels.APPROX_SHAPLEY, AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED)
		);

		// Several instances/individual MATSim runs
		for (int instance : instances) {
			// Three customer distribution scenarios
			for (CustomerDistributionScenario customerDistributionScenario : CustomerDistributionScenario.values()) {
				// Two depot scenarios
				for (DepotScenario depotScenario : DepotScenario.values()) {
					// Design 1: penalty sweep
					for (double penalty : penaltySweep) {
						for (double allocFactor : allocSweepShort) {
							for (AllocationMethodChoice method : methods) {
								// Check if we need a break
//								if (Duration.between(windowStart, Instant.now()).compareTo(workWindow) >= 0) {
//									System.out.println("Cooldown: sleeping for " + breakWindow + " after " + workWindow + " of work.");
//									safeSleep(breakWindow);
//									windowStart = Instant.now();
//								}

								runSingleExperiment("penSweep", allocFactor, penalty, method, instance, depotScenario,
									customerDistributionScenario);
							}
						}
					}
					//				for (double allocFactor : allocSweepLong) {
					//					for (AllocationMethodChoice method : methods) {
					//						runSingleExperiment("allocSweep", allocFactor, fixedPenalty, method, instance, depotScenario);
					//					}
					//				}
				}
			}
		}
	}

	private static void runSingleExperiment(String tag, double allocationFactor, double receiverPenalty, AllocationMethodChoice methodChoice,
											int instance, DepotScenario depotScenario, CustomerDistributionScenario customerDistributionScenario) {
		String runId = "%s-%s-%s-af%.2f-p%.4f-%s-i%02d".formatted(depotScenario.label, customerDistributionScenario.name(),
			tag, allocationFactor, receiverPenalty, methodChoice.label, instance);

		Config config = createChessboardConfig(runId);
		// Get the output directory and check if it already exists
		String outputDir = config.controller().getOutputDirectory();
		// If it exists, skip this experiment
		if (new File(outputDir).exists()) {
			logger.warn("Output directory " + outputDir + " already exists. Skipping this experiment.");
			return;
		} else {
			logger.info("Running experiment with runId: " + runId);
//			Scanner scanner = new Scanner(System.in);
//			String answer = scanner.nextLine();
//
//			if (answer != null && answer.trim().equalsIgnoreCase("No")) {
//				logger.info("User chose to exit. Terminating.");
//				System.exit(0);
//			}
		}

		config.global().setRandomSeed(4711 + instance); // identical seed across depot scenarios for comparability
		FreightCollaborationConfigGroup freightCfg = createExampleFreightCollaborationConfig();
		freightCfg.ALLOCATION_FACTOR = allocationFactor;
		freightCfg.RECEIVER_RELAXATION_PENALTY = receiverPenalty;
		freightCfg.ALLOCATION_MODEL = methodChoice.model;
		freightCfg.setVrpMaxIterations(100);
		if (methodChoice.model == AllocationModels.APPROX_SHAPLEY && methodChoice.approxMethod != null) {
			freightCfg.APPROX_SHAPLEY_METHOD = methodChoice.approxMethod.name();
		}
		config.addModule(freightCfg);

		runSingleControler(config, depotScenario, customerDistributionScenario, instance);
	}

	private static void runSingleControler(Config config, DepotScenario depotScenario, CustomerDistributionScenario customerDistributionScenario,
										   int instance) {
		Scenario scenario = ScenarioUtils.loadScenario(config);

		Carriers carriers = generateExampleCarriers(depotScenario.depotLinkId);
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (var carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);
		FreightCollaborationConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCollaborationConfigGroup.class);

		Receivers receivers = generateRandomReceivers(scenario.getNetwork(), 10, customerDistributionScenario, instance);
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

	private static void safeSleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted during cooldown sleep", e);
		}
	}

	static Config createChessboardConfig(String runId) {
		URL context = ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9");
		Config config = ConfigUtils.createConfig();
		config.setContext(context);
		config.network().setInputFile("grid9x9.xml");
		config.controller().setOutputDirectory("output/chessboardCarrierReceiverCollab/" + runId + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(50);
		// set writing output every 5 iterations
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		// num of threads
		logger.info("available processors: {}", Runtime.getRuntime().availableProcessors());
		config.global().setNumberOfThreads(4);
		config.qsim().setNumberOfThreads(1);
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
			.setCostPerDistanceUnit(8.5E-4)
			.setCostPerTimeUnit(0.0125)  // change to 0.005 euro/sec = 18 euro/hr?
			.build();
		lightVanType.setNetworkMode("car");

		VehicleType heavyVanType = CarrierVehicleType.Builder.newInstance(Id.create("heavy", VehicleType.class))
			.setCapacity(5000)
			.setFixCost(150)
			.setCostPerDistanceUnit(1.22E-3)
			.setCostPerTimeUnit(0.0167)  // change to 0.006 euro/sec = 21.6 euro/hr?
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

	static Receivers generateRandomReceivers(Network network, int count,
											 CustomerDistributionScenario customerDistributionScenario,
											 int seed) {
		Receivers receivers = ReceiverUtils.createReceivers();
		Set<Id<Link>> candidateLinks;

		switch (customerDistributionScenario) {
//			case CustomerDistributionScenario.FULLY_RANDOM ->
//				candidateLinks = generateFullyRandomReceiversWithinArea(network, count, seed);
			case CustomerDistributionScenario.CLUSTERED ->
				candidateLinks = generateClusteredReceiversWithinArea(network, count, seed);
			case CustomerDistributionScenario.DISPERSED ->
				candidateLinks = generateHierarchyDispersedReceiversWithinArea(network, count, seed);
			default ->
				throw new IllegalStateException("Unexpected value: " + customerDistributionScenario);
		}

		int receiverId = 0;
		for (Id<Link> location: candidateLinks) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver_%02d".formatted(receiverId), Receiver.class));
			receiver.setLinkId(location);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receivers.addReceiver(receiver);
			receiverId++;
		}

		return receivers;
	}

	static Set<Id<Link>> generateFullyRandomReceiversWithinArea(Network network, int count, int seed){
		Set<Id<Link>> receiverLinks =  new HashSet<>();
		List<Link> candidates = getCarLinksWithinArea(network);
		if (candidates.size() < count) {
			throw new IllegalStateException("Not enough links in area to place receivers: " + candidates.size());
		}
		Collections.shuffle(candidates, new Random(13_579 + seed));
		for (int i = 0; i < count; i++) {
			receiverLinks.add(candidates.get(i).getId());
		}
		return receiverLinks;
	}

	static Set<Id<Link>> generateClusteredReceiversWithinArea(Network network, int count, int seed){
		Set<Id<Link>> receiverLinks =  new HashSet<>();
		Random random = new Random(23_911 + seed);
		double squareSize = 3000.0;
		double minStart = AREA_MIN;
		double maxStart = AREA_MAX - squareSize;
		List<Double> starts = new ArrayList<>();
		for (double v = minStart; v <= maxStart + EPS; v += LINK_LENGTH) {
			starts.add(v);
		}
		double startX = starts.get(random.nextInt(starts.size()));
		double startY = starts.get(random.nextInt(starts.size()));
		double maxX = startX + squareSize;
		double maxY = startY + squareSize;

		List<Link> candidates = new ArrayList<>();
		for (Link link : getCarLinksWithinArea(network)) {
			Coord mid = midpoint(link);
			if (mid.getX() + EPS >= startX && mid.getX() - EPS <= maxX &&
				mid.getY() + EPS >= startY && mid.getY() - EPS <= maxY) {
				candidates.add(link);
			}
		}
		if (candidates.size() < count) {
			throw new IllegalStateException("Not enough links in clustered square to place receivers: " + candidates.size());
		}
		candidates.sort(Comparator.comparing(l -> l.getId().toString()));
		Collections.shuffle(candidates, random);
		for (int i = 0; i < count; i++) {
			receiverLinks.add(candidates.get(i).getId());
		}
		return receiverLinks;
	}

	static  Set<Id<Link>> generateHierarchyDispersedReceiversWithinArea(Network network, int count, int seed){
		Set<Id<Link>> receiverLinks =  new HashSet<>();
		if (count != 10) {
			throw new IllegalStateException("Hierarchy dispersed scenario expects exactly 10 receivers, got " + count);
		}
		Random random = new Random(41_317 + seed);
		Link centerLink = chooseCenterLink(network, random);
		receiverLinks.add(centerLink.getId());

		Coord center = midpoint(centerLink);
		List<Link> areaLinks = getCarLinksWithinArea(network);

		addBalancedEdgeLinks(receiverLinks, areaLinks,
			center.getX() - 1500.0, center.getX() + 1500.0,
			center.getY() - 1000.0, center.getY() + 1000.0,
			3, random);

		addBalancedEdgeLinks(receiverLinks, areaLinks,
			center.getX() - 2500.0, center.getX() + 2500.0,
			center.getY() - 2000.0, center.getY() + 2000.0,
			6, random);

		return receiverLinks;
	}

	private static final double AREA_MIN = 2000.0;
	private static final double AREA_MAX = 7000.0;
	private static final double LINK_LENGTH = 1000.0;
	private static final double EPS = 1e-6;

	private enum RectangleEdge {
		TOP,
		BOTTOM,
		LEFT,
		RIGHT
	}

	private static List<Link> getCarLinksWithinArea(Network network) {
		List<Link> links = new ArrayList<>();
		for (Link link : network.getLinks().values()) {
			if (!link.getAllowedModes().contains(TransportMode.car)) {
				continue;
			}
			if (isWithinArea(midpoint(link))) {
				links.add(link);
			}
		}
		links.sort(Comparator.comparing(l -> l.getId().toString()));
		return links;
	}

	private static boolean isWithinArea(Coord coord) {
		return coord.getX() + EPS >= AREA_MIN && coord.getX() - EPS <= AREA_MAX &&
			coord.getY() + EPS >= AREA_MIN && coord.getY() - EPS <= AREA_MAX;
	}

	private static Coord midpoint(Link link) {
		Coord from = link.getFromNode().getCoord();
		Coord to = link.getToNode().getCoord();
		return new Coord((from.getX() + to.getX()) / 2.0, (from.getY() + to.getY()) / 2.0);
	}

	private static Link chooseCenterLink(Network network, Random random) {
		Link centerA = network.getLinks().get(Id.createLinkId("i(5,4)"));
		Link centerB = network.getLinks().get(Id.createLinkId("i(5,5)R"));
		if (centerA == null && centerB == null) {
			throw new IllegalStateException("Center link not found: i(5,4) or i(5,5)R");
		}
		if (centerA == null) {
			return centerB;
		}
		if (centerB == null) {
			return centerA;
		}
		return random.nextBoolean() ? centerA : centerB;
	}

	private static void addBalancedEdgeLinks(Set<Id<Link>> receiverLinks, List<Link> areaLinks,
											 double minX, double maxX, double minY, double maxY,
											 int count, Random random) {
		Map<RectangleEdge, List<Link>> edgeLinks = new EnumMap<>(RectangleEdge.class);
		for (RectangleEdge edge : RectangleEdge.values()) {
			edgeLinks.put(edge, new ArrayList<>());
		}

		for (Link link : areaLinks) {
			Coord mid = midpoint(link);
			if (mid.getX() + EPS < minX || mid.getX() - EPS > maxX ||
				mid.getY() + EPS < minY || mid.getY() - EPS > maxY) {
				continue;
			}
			if (Math.abs(mid.getX() - minX) < EPS) {
				edgeLinks.get(RectangleEdge.LEFT).add(link);
			} else if (Math.abs(mid.getX() - maxX) < EPS) {
				edgeLinks.get(RectangleEdge.RIGHT).add(link);
			} else if (Math.abs(mid.getY() - minY) < EPS) {
				edgeLinks.get(RectangleEdge.BOTTOM).add(link);
			} else if (Math.abs(mid.getY() - maxY) < EPS) {
				edgeLinks.get(RectangleEdge.TOP).add(link);
			}
		}

		List<RectangleEdge> edges = new ArrayList<>(List.of(RectangleEdge.values()));
		Collections.shuffle(edges, random);
		int base = count / edges.size();
		int remainder = count % edges.size();
		Map<RectangleEdge, Integer> targets = new EnumMap<>(RectangleEdge.class);
		for (int i = 0; i < edges.size(); i++) {
			targets.put(edges.get(i), base + (i < remainder ? 1 : 0));
		}

		int remaining = count;
		for (RectangleEdge edge : edges) {
			List<Link> candidates = edgeLinks.get(edge);
			candidates.sort(Comparator.comparing(l -> l.getId().toString()));
			Collections.shuffle(candidates, random);
			int target = targets.get(edge);
			for (Link link : candidates) {
				if (receiverLinks.contains(link.getId())) {
					continue;
				}
				receiverLinks.add(link.getId());
				target--;
				remaining--;
				if (target == 0 || remaining == 0) {
					break;
				}
			}
			if (remaining == 0) {
				return;
			}
		}

		if (remaining > 0) {
			List<Link> allCandidates = new ArrayList<>();
			for (List<Link> list : edgeLinks.values()) {
				allCandidates.addAll(list);
			}
			allCandidates.sort(Comparator.comparing(l -> l.getId().toString()));
			Collections.shuffle(allCandidates, random);
			for (Link link : allCandidates) {
				if (receiverLinks.contains(link.getId())) {
					continue;
				}
				receiverLinks.add(link.getId());
				remaining--;
				if (remaining == 0) {
					break;
				}
			}
		}

		if (remaining > 0) {
			throw new IllegalStateException("Not enough edge links to place receivers: missing " + remaining);
		}
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

	public static class MyCarrierPlanStrategyManagerProvider implements Provider<CarrierStrategyManager> {
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

