package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.CollaborationModule;
import org.matsim.contrib.freightcollaboration.controller.CollaboratorModules;
import org.matsim.contrib.freightcollaboration.utils.LinkFreightAgentToFreightCollaborator;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverConfigGroup;
import org.matsim.freight.receiver.ReceiverModule;
import org.matsim.freight.receiver.ReceiverReplanningType;
import org.matsim.freight.receiver.ReceiverScoringFunctionFactory;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.matsim.contrib.freightcollaboration.run.RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig;
import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * Extends {@link RunCarrierReceiverCollabChessboardExample} to a scalable chessboard network.
 * Carrier depot scenarios are placed on the center column from the network center down to the bottom link.
 */
public class RunCollabReceiverDistantCarrier {
	private static final Logger logger = LogManager.getLogger(RunCollabReceiverDistantCarrier.class);

	private static final int RECEIVER_COUNT = 10;
	private static final int DEFAULT_CARRIER_SCENARIOS = 10;
	private static final Path DEFAULT_OUTPUT_BASE_DIR = Path.of("output", "collabReceiverDistantCarrier");
	private static final double CHESSBOARD_EXAMPLE_AREA_MIN = 2000.0;
	private static final double CHESSBOARD_EXAMPLE_AREA_MAX = 7000.0;
	private static final double EPS = 1e-6;

	private enum CustomerDistributionScenario {
		FULLY_RANDOM,
		CLUSTERED,
		DISPERSED
	}

	private enum ReceiverAreaPolicy {
		SCALED_TO_NETWORK,
		CHESSBOARD_EXAMPLE_AREA,
		CENTERED_CHESSBOARD_EXAMPLE_AREA
	}

	private enum RectangleEdge {
		TOP,
		BOTTOM,
		LEFT,
		RIGHT
	}

	private record AllocationMethodChoice(String label, AllocationModels model,
										  AllocationModelApproxShapleyValue.ApproximationMethod approxMethod) {
	}

	private record DepotScenario(String label, Id<Link> depotLinkId) {
	}

	private record ReceiverArea(double min, double max) {
	}

	private record ExperimentOptions(int networkSize, int carrierScenarioCount, int instanceCount, Path networkFile,
									 Path outputBaseDir, ReceiverAreaPolicy receiverAreaPolicy,
									 boolean regenerateNetworkFile) {
	}

	private record ExperimentConfig(ExperimentOptions options, String tag, double[] penaltySweep,
									double[] allocationSweep,
									List<CustomerDistributionScenario> customerDistributionScenarios,
									List<AllocationMethodChoice> methods) {
	}

	public static void main(String[] args) {
		ExperimentConfig experimentConfig = applyCommandLineOverrides(createDefaultExperimentConfig(), args);
		runExperiments(experimentConfig);
	}

	private static ExperimentConfig createDefaultExperimentConfig() {
		int networkSize = CreateFreightChessboardNetwork.DEFAULT_GRID_SIZE;
		ExperimentOptions options = new ExperimentOptions(
			networkSize,
			DEFAULT_CARRIER_SCENARIOS,
			1,
			CreateFreightChessboardNetwork.defaultNetworkPath(networkSize),
			DEFAULT_OUTPUT_BASE_DIR,
			ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA,
			true
		);

//		double[] penaltySweep = {0, 0.0003, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.0167, 0.0222, 0.028};
		double[] penaltySweep = {0.0014, 0.0028, 0.0056};
		double[] allocationSweep = {0.8};
		List<CustomerDistributionScenario> customerDistributionScenarios = List.of(
//			CustomerDistributionScenario.FULLY_RANDOM,
			CustomerDistributionScenario.CLUSTERED,
			CustomerDistributionScenario.DISPERSED
		);
		List<AllocationMethodChoice> methods = List.of(
			new AllocationMethodChoice("exactShapley", AllocationModels.SHAPLEY, null)
		);

		return new ExperimentConfig(options, "penSweep", penaltySweep, allocationSweep, customerDistributionScenarios,
			methods);
	}

	private static ExperimentConfig applyCommandLineOverrides(ExperimentConfig experimentConfig, String[] args) {
		return new ExperimentConfig(parseOptions(args, experimentConfig.options()), experimentConfig.tag(),
			experimentConfig.penaltySweep(), experimentConfig.allocationSweep(),
			experimentConfig.customerDistributionScenarios(), experimentConfig.methods());
	}

	private static void runExperiments(ExperimentConfig experimentConfig) {
		ExperimentOptions options = experimentConfig.options();
		ensureNetworkFile(options);
		List<DepotScenario> depotScenarios = createDepotScenarios(options.networkSize(), options.carrierScenarioCount());
		logger.info("Carrier depot scenarios: {}", depotScenarios);

		List<Integer> instances = IntStream.range(0, options.instanceCount()).boxed().toList();
		for (int instance : instances) {
			for (CustomerDistributionScenario customerDistributionScenario :
				experimentConfig.customerDistributionScenarios()) {
				for (DepotScenario depotScenario : depotScenarios) {
					for (double penalty : experimentConfig.penaltySweep()) {
						for (double allocationFactor : experimentConfig.allocationSweep()) {
							for (AllocationMethodChoice method : experimentConfig.methods()) {
								runSingleExperiment(options, experimentConfig.tag(), allocationFactor, penalty, method,
									instance, depotScenario, customerDistributionScenario);
							}
						}
					}
				}
			}
		}
	}

	private static void runSingleExperiment(ExperimentOptions options, String tag, double allocationFactor,
											double receiverPenalty, AllocationMethodChoice methodChoice, int instance,
											DepotScenario depotScenario,
											CustomerDistributionScenario customerDistributionScenario) {
		String distributionLabel = customerDistributionScenario.name().toLowerCase(Locale.ROOT);
		String areaLabel = switch (options.receiverAreaPolicy()) {
			case SCALED_TO_NETWORK -> "scaledArea";
			case CHESSBOARD_EXAMPLE_AREA -> "chessboardArea";
			case CENTERED_CHESSBOARD_EXAMPLE_AREA -> "centeredChessboardArea";
		};
		String runId = "%s-%s-%s-%s-af%.2f-p%.4f-%s-i%02d".formatted(depotScenario.label(), distributionLabel,
			areaLabel, tag, allocationFactor, receiverPenalty, methodChoice.label(), instance);

		Config config = createChessboardConfig(options, runId);
		String outputDir = config.controller().getOutputDirectory();
		if (new File(outputDir).exists()) {
			logger.warn("Output directory {} already exists. Skipping this experiment.", outputDir);
			return;
		}

		logger.info("Running experiment with runId: {}", runId);
		config.global().setRandomSeed(4711 + instance);

		FreightCollaborationConfigGroup freightCfg = createExampleFreightCollaborationConfig();
		freightCfg.ALLOCATION_FACTOR = allocationFactor;
		freightCfg.RECEIVER_RELAXATION_PENALTY = receiverPenalty;
		freightCfg.ALLOCATION_MODEL = methodChoice.model();
		freightCfg.setVrpMaxIterations(100);
		if (methodChoice.model() == AllocationModels.APPROX_SHAPLEY && methodChoice.approxMethod() != null) {
			freightCfg.APPROX_SHAPLEY_METHOD = methodChoice.approxMethod().name();
		}
		freightCfg.setPsimScoringModeString(FreightCollaborationConfigGroup.PsimScoringMode.BASIC_COST.toString());
		freightCfg.setIter0BaselineModeString(FreightCollaborationConfigGroup.Iter0BaselineMode.FEE_FREE.toString());
		config.addModule(freightCfg);

		runSingleControler(config, options, depotScenario, customerDistributionScenario, instance);
	}

	private static Config createChessboardConfig(ExperimentOptions options, String runId) {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(options.networkFile().toString());
		config.controller().setOutputDirectory(options.outputBaseDir()
			.resolve("grid%dx%d".formatted(options.networkSize(), options.networkSize()))
			.resolve(runId).toString() + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(30);
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		config.global().setNumberOfThreads(4);
		config.qsim().setNumberOfThreads(1);
		return config;
	}

	private static void runSingleControler(Config config, ExperimentOptions options, DepotScenario depotScenario,
										   CustomerDistributionScenario customerDistributionScenario, int instance) {
		Scenario scenario = ScenarioUtils.loadScenario(config);
		validateDepotLinkExists(scenario.getNetwork(), depotScenario);

		Carriers carriers = RunCarrierReceiverCollabChessboardExample.generateExampleCarriers(
			depotScenario.depotLinkId().toString());
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (Carrier carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(),
			ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);
		FreightCollaborationConfigGroup freightConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(),
			FreightCollaborationConfigGroup.class);

		Receivers receivers = generateReceivers(scenario.getNetwork(), RECEIVER_COUNT, customerDistributionScenario,
			instance, options.networkSize(), options.receiverAreaPolicy());
		ReceiverUtils.setReceivers(receivers, scenario);

		RunCarrierReceiverCollabChessboardExample.ReceiverOrderGeneration receiverOrderGeneration =
			new RunCarrierReceiverCollabChessboardExample.ReceiverOrderGeneration(receivers, carriers,
				depotScenario.depotLinkId(), Id.create("carrier1", Carrier.class));
		receiverOrderGeneration.generateAllReceiverOrders();

		writeFreightScenario(scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario),
			CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		Controler controler = new Controler(scenario);
		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(
			freightConfigGroup.RECEIVER_FIXED_FEE));
		receiverModule.setReplanningType(ReceiverReplanningType.timeWindow);

		FreightCollaborators freightCollaborators = new FreightCollaborators();
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			freightCollaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(carrier, true));
		}
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			freightCollaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(receiver, true));
		}

		CollaboratorModules collaboratorModules = new CollaboratorModules(Map.of(CollaboratorRole.RECEIVER,
			receiverModule, CollaboratorRole.CARRIER, new CarrierModule()));
		CollaborationModule collaborationModule = new CollaborationModule(collaboratorModules, freightCollaborators,
			scenario);

		collaborationModule.installAllCollaboratorModules(controler);
		controler.addOverridingModule(collaborationModule);

		CarrierVehicleTypes carrierVehicleTypes = CarrierVehicleTypes.getVehicleTypes(scenarioCarriers);
		CarrierVehicleTypes types = CarriersUtils.getCarrierVehicleTypes(scenario);
		types.getVehicleTypes().putAll(carrierVehicleTypes.getVehicleTypes());

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(CarrierStrategyManager.class).toProvider(
					new RunCarrierReceiverCollabChessboardExample.MyCarrierPlanStrategyManagerProvider(types));
				bind(CarrierScoringFunctionFactory.class).to(
					ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.class);
				bind(ReceiverScoringFunctionFactory.class).to(
					ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase.class);
			}
		});

		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()),
			controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();
	}

	static List<DepotScenario> createDepotScenarios(int networkSize, int scenarioCount) {
		if (scenarioCount < 1) {
			throw new IllegalArgumentException("carrierScenarioCount must be at least 1, got " + scenarioCount);
		}
		int center = (networkSize + 1) / 2;
		if (scenarioCount > center) {
			throw new IllegalArgumentException("Cannot create " + scenarioCount + " unique center-to-bottom depot "
				+ "positions on a " + networkSize + "x" + networkSize + " network. Maximum is " + center + ".");
		}

		List<DepotScenario> scenarios = new ArrayList<>();
		for (int index = 0; index < scenarioCount; index++) {
			int y = scenarioCount == 1 ? center
				: (int) Math.round(center - index * ((center - 1.0) / (scenarioCount - 1.0)));
			String label = index == 0 ? "dc%02d_center".formatted(index)
				: index == scenarioCount - 1 ? "dc%02d_bottom".formatted(index)
				: "dc%02d".formatted(index);
			scenarios.add(new DepotScenario(label, Id.createLinkId(CreateFreightChessboardNetwork.verticalLinkId(center, y))));
		}
		return List.copyOf(scenarios);
	}

	static Receivers generateReceivers(Network network, int count,
									   CustomerDistributionScenario customerDistributionScenario, int seed,
									   int networkSize) {
		return generateReceivers(network, count, customerDistributionScenario, seed, networkSize,
			ReceiverAreaPolicy.SCALED_TO_NETWORK);
	}

	static Receivers generateReceivers(Network network, int count,
									   CustomerDistributionScenario customerDistributionScenario, int seed,
									   int networkSize, ReceiverAreaPolicy receiverAreaPolicy) {
		Receivers receivers = ReceiverUtils.createReceivers();
		Set<Id<Link>> candidateLinks;

		switch (customerDistributionScenario) {
			case FULLY_RANDOM ->
				candidateLinks = generateFullyRandomReceiversWithinArea(network, count, seed, networkSize,
					receiverAreaPolicy);
			case CLUSTERED ->
				candidateLinks = generateClusteredReceiversWithinArea(network, count, seed, networkSize,
					receiverAreaPolicy);
			case DISPERSED ->
				candidateLinks = generateHierarchyDispersedReceiversWithinArea(network, count, seed, networkSize,
					receiverAreaPolicy);
			default -> throw new IllegalStateException("Unexpected value: " + customerDistributionScenario);
		}

		int receiverId = 0;
		for (Id<Link> location : candidateLinks) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver_%02d".formatted(receiverId),
				Receiver.class));
			receiver.setLinkId(location);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receivers.addReceiver(receiver);
			receiverId++;
		}

		return receivers;
	}

	static Set<Id<Link>> generateFullyRandomReceiversWithinArea(Network network, int count, int seed, int networkSize) {
		return generateFullyRandomReceiversWithinArea(network, count, seed, networkSize,
			ReceiverAreaPolicy.SCALED_TO_NETWORK);
	}

	static Set<Id<Link>> generateFullyRandomReceiversWithinArea(Network network, int count, int seed, int networkSize,
																ReceiverAreaPolicy receiverAreaPolicy) {
		Set<Id<Link>> receiverLinks = new LinkedHashSet<>();
		List<Link> candidates = getCarLinksWithinArea(network, receiverArea(networkSize, receiverAreaPolicy));
		if (candidates.size() < count) {
			throw new IllegalStateException("Not enough links in receiver area to place receivers: " + candidates.size());
		}
		Collections.shuffle(candidates, new Random(13_579 + seed));
		for (int i = 0; i < count; i++) {
			receiverLinks.add(candidates.get(i).getId());
		}
		return receiverLinks;
	}

	static Set<Id<Link>> generateClusteredReceiversWithinArea(Network network, int count, int seed, int networkSize) {
		return generateClusteredReceiversWithinArea(network, count, seed, networkSize,
			ReceiverAreaPolicy.SCALED_TO_NETWORK);
	}

	static Set<Id<Link>> generateClusteredReceiversWithinArea(Network network, int count, int seed, int networkSize,
															  ReceiverAreaPolicy receiverAreaPolicy) {
		Set<Id<Link>> receiverLinks = new LinkedHashSet<>();
		Random random = new Random(23_911 + seed);
		ReceiverArea area = receiverArea(networkSize, receiverAreaPolicy);
		double squareSize = 3 * CreateFreightChessboardNetwork.LINK_LENGTH;
		double maxStart = area.max() - squareSize;
		if (maxStart < area.min()) {
			throw new IllegalStateException("Receiver area is too small for a 3x3 km clustered square.");
		}

		List<Double> starts = new ArrayList<>();
		for (double v = area.min(); v <= maxStart + EPS; v += CreateFreightChessboardNetwork.LINK_LENGTH) {
			starts.add(v);
		}
		double startX = starts.get(random.nextInt(starts.size()));
		double startY = starts.get(random.nextInt(starts.size()));
		double maxX = startX + squareSize;
		double maxY = startY + squareSize;

		List<Link> candidates = new ArrayList<>();
		for (Link link : getCarLinksWithinArea(network, area)) {
			Coord mid = midpoint(link);
			if (mid.getX() + EPS >= startX && mid.getX() - EPS <= maxX
				&& mid.getY() + EPS >= startY && mid.getY() - EPS <= maxY) {
				candidates.add(link);
			}
		}
		if (candidates.size() < count) {
			throw new IllegalStateException("Not enough links in clustered square to place receivers: "
				+ candidates.size());
		}
		candidates.sort(Comparator.comparing(l -> l.getId().toString()));
		Collections.shuffle(candidates, random);
		for (int i = 0; i < count; i++) {
			receiverLinks.add(candidates.get(i).getId());
		}
		return receiverLinks;
	}

	static Set<Id<Link>> generateHierarchyDispersedReceiversWithinArea(Network network, int count, int seed,
																	   int networkSize) {
		return generateHierarchyDispersedReceiversWithinArea(network, count, seed, networkSize,
			ReceiverAreaPolicy.SCALED_TO_NETWORK);
	}

	static Set<Id<Link>> generateHierarchyDispersedReceiversWithinArea(Network network, int count, int seed,
																	   int networkSize,
																	   ReceiverAreaPolicy receiverAreaPolicy) {
		Set<Id<Link>> receiverLinks = new LinkedHashSet<>();
		if (count != RECEIVER_COUNT) {
			throw new IllegalStateException("Hierarchy dispersed scenario expects exactly " + RECEIVER_COUNT
				+ " receivers, got " + count);
		}
		Random random = new Random(41_317 + seed);
		Link centerLink = chooseReceiverCenterLink(network, networkSize, receiverAreaPolicy);
		receiverLinks.add(centerLink.getId());

		Coord center = midpoint(centerLink);
		List<Link> areaLinks = getAllCarLinks(network);
		double scale = receiverAreaPolicy == ReceiverAreaPolicy.SCALED_TO_NETWORK ? networkSize / 9.0 : 1.0;

		addBalancedEdgeLinks(receiverLinks, areaLinks,
			center.getX() - 2500.0 * scale, center.getX() + 2500.0 * scale,
			center.getY() - 2000.0 * scale, center.getY() + 2000.0 * scale,
			4, random);

		addBalancedEdgeLinks(receiverLinks, areaLinks,
			center.getX() - 4500.0 * scale, center.getX() + 4500.0 * scale,
			center.getY() - 4000.0 * scale, center.getY() + 4000.0 * scale,
			5, random);

		return receiverLinks;
	}

	private static ReceiverArea receiverArea(int networkSize, ReceiverAreaPolicy receiverAreaPolicy) {
		return switch (receiverAreaPolicy) {
			case SCALED_TO_NETWORK -> scaledReceiverArea(networkSize);
			case CHESSBOARD_EXAMPLE_AREA -> chessboardExampleReceiverArea();
			case CENTERED_CHESSBOARD_EXAMPLE_AREA -> centeredChessboardExampleReceiverArea(networkSize);
		};
	}

	private static ReceiverArea scaledReceiverArea(int networkSize) {
		double extent = networkSize * CreateFreightChessboardNetwork.LINK_LENGTH;
		return new ReceiverArea(extent * 2.0 / 9.0, extent * 7.0 / 9.0);
	}

	private static ReceiverArea chessboardExampleReceiverArea() {
		return new ReceiverArea(CHESSBOARD_EXAMPLE_AREA_MIN, CHESSBOARD_EXAMPLE_AREA_MAX);
	}

	private static ReceiverArea centeredChessboardExampleReceiverArea(int networkSize) {
		double extent = networkSize * CreateFreightChessboardNetwork.LINK_LENGTH;
		double size = CHESSBOARD_EXAMPLE_AREA_MAX - CHESSBOARD_EXAMPLE_AREA_MIN;
		double min = (extent - size) / 2.0;
		double max = min + size;
		if (min < 0 || max > extent) {
			throw new IllegalStateException("Network is too small for centered chessboard receiver area: "
				+ networkSize);
		}
		return new ReceiverArea(min, max);
	}

	private static List<Link> getCarLinksWithinArea(Network network, ReceiverArea area) {
		List<Link> links = new ArrayList<>();
		for (Link link : network.getLinks().values()) {
			if (!link.getAllowedModes().contains(TransportMode.car)) {
				continue;
			}
			if (isWithinArea(midpoint(link), area)) {
				links.add(link);
			}
		}
		links.sort(Comparator.comparing(l -> l.getId().toString()));
		return links;
	}

	private static List<Link> getAllCarLinks(Network network) {
		List<Link> links = new ArrayList<>();
		for (Link link : network.getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)) {
				links.add(link);
			}
		}
		links.sort(Comparator.comparing(l -> l.getId().toString()));
		return links;
	}

	private static boolean isWithinArea(Coord coord, ReceiverArea area) {
		return coord.getX() + EPS >= area.min() && coord.getX() - EPS <= area.max()
			&& coord.getY() + EPS >= area.min() && coord.getY() - EPS <= area.max();
	}

	private static Coord midpoint(Link link) {
		Coord from = link.getFromNode().getCoord();
		Coord to = link.getToNode().getCoord();
		return new Coord((from.getX() + to.getX()) / 2.0, (from.getY() + to.getY()) / 2.0);
	}

	private static Link chooseReceiverCenterLink(Network network, int networkSize,
												 ReceiverAreaPolicy receiverAreaPolicy) {
		if (receiverAreaPolicy == ReceiverAreaPolicy.CHESSBOARD_EXAMPLE_AREA) {
			String linkId = "i(5,4)";
			Link centerLink = network.getLinks().get(Id.createLinkId(linkId));
			if (centerLink == null) {
				throw new IllegalStateException("Receiver center link not found: " + linkId);
			}
			return centerLink;
		}
		if (receiverAreaPolicy == ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA) {
			return chooseLinkClosestToAreaCenter(network, centeredChessboardExampleReceiverArea(networkSize));
		}

		int center = (networkSize + 1) / 2;
		String linkId = CreateFreightChessboardNetwork.horizontalLinkId(center, Math.max(0, center - 1));
		Link centerLink = network.getLinks().get(Id.createLinkId(linkId));
		if (centerLink == null) {
			throw new IllegalStateException("Receiver center link not found: " + linkId);
		}
		return centerLink;
	}

	private static Link chooseLinkClosestToAreaCenter(Network network, ReceiverArea area) {
		double center = (area.min() + area.max()) / 2.0;
		return getAllCarLinks(network).stream()
			.min(Comparator
				.comparingDouble((Link link) -> squaredDistance(midpoint(link), center, center))
				.thenComparing(link -> link.getId().toString()))
			.orElseThrow(() -> new IllegalStateException("No car links found for receiver center."));
	}

	private static double squaredDistance(Coord coord, double x, double y) {
		double dx = coord.getX() - x;
		double dy = coord.getY() - y;
		return dx * dx + dy * dy;
	}

	private static void addBalancedEdgeLinks(Set<Id<Link>> receiverLinks, List<Link> areaLinks,
											 double minX, double maxX, double minY, double maxY, int count,
											 Random random) {
		Map<RectangleEdge, List<Link>> edgeLinks = new EnumMap<>(RectangleEdge.class);
		for (RectangleEdge edge : RectangleEdge.values()) {
			edgeLinks.put(edge, new ArrayList<>());
		}

		double edgeTolerance = CreateFreightChessboardNetwork.LINK_LENGTH / 2.0 + EPS;
		for (Link link : areaLinks) {
			Coord mid = midpoint(link);
			if (mid.getX() + edgeTolerance < minX || mid.getX() - edgeTolerance > maxX
				|| mid.getY() + edgeTolerance < minY || mid.getY() - edgeTolerance > maxY) {
				continue;
			}
			RectangleEdge edge = nearestRectangleEdge(mid, minX, maxX, minY, maxY, edgeTolerance);
			if (edge != null) {
				edgeLinks.get(edge).add(link);
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

	private static RectangleEdge nearestRectangleEdge(Coord mid, double minX, double maxX, double minY, double maxY,
													  double edgeTolerance) {
		double left = Math.abs(mid.getX() - minX);
		double right = Math.abs(mid.getX() - maxX);
		double bottom = Math.abs(mid.getY() - minY);
		double top = Math.abs(mid.getY() - maxY);
		double min = Math.min(Math.min(left, right), Math.min(bottom, top));
		if (min > edgeTolerance) {
			return null;
		}
		if (min == left) {
			return RectangleEdge.LEFT;
		}
		if (min == right) {
			return RectangleEdge.RIGHT;
		}
		if (min == bottom) {
			return RectangleEdge.BOTTOM;
		}
		return RectangleEdge.TOP;
	}

	private static void validateDepotLinkExists(Network network, DepotScenario depotScenario) {
		if (!network.getLinks().containsKey(depotScenario.depotLinkId())) {
			throw new IllegalStateException("Depot link for " + depotScenario.label() + " is missing in network: "
				+ depotScenario.depotLinkId());
		}
	}

	private static void ensureNetworkFile(ExperimentOptions options) {
		if (Files.exists(options.networkFile()) && !options.regenerateNetworkFile()) {
			logger.info("Using existing chessboard network file: {}", options.networkFile());
			return;
		}
		if (Files.exists(options.networkFile())) {
			logger.info("Regenerating chessboard network file: {}", options.networkFile());
		}
		CreateFreightChessboardNetwork.writeNetwork(options.networkSize(), options.networkFile());
	}

	private static ExperimentOptions parseOptions(String[] args, ExperimentOptions defaults) {
		int networkSize = defaults.networkSize();
		int carrierScenarioCount = defaults.carrierScenarioCount();
		int instanceCount = defaults.instanceCount();
		Path networkFile = defaults.networkFile();
		Path outputBaseDir = defaults.outputBaseDir();
		ReceiverAreaPolicy receiverAreaPolicy = defaults.receiverAreaPolicy();
		boolean regenerateNetworkFile = defaults.regenerateNetworkFile();
		boolean networkSizeOverridden = false;
		boolean networkFileOverridden = false;
		boolean regenerateNetworkFileOverridden = false;

		for (String arg : args) {
			if (arg.equals("--help") || arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.startsWith("--network-size=")) {
				networkSize = parsePositiveInt(arg.substring("--network-size=".length()), "--network-size");
				networkSizeOverridden = true;
			} else if (arg.startsWith("--carrier-scenarios=")) {
				carrierScenarioCount = parsePositiveInt(arg.substring("--carrier-scenarios=".length()),
					"--carrier-scenarios");
			} else if (arg.startsWith("--instances=")) {
				instanceCount = parsePositiveInt(arg.substring("--instances=".length()), "--instances");
			} else if (arg.startsWith("--network-file=")) {
				networkFile = Path.of(arg.substring("--network-file=".length()));
				networkFileOverridden = true;
			} else if (arg.startsWith("--output-base=")) {
				outputBaseDir = Path.of(arg.substring("--output-base=".length()));
			} else if (arg.startsWith("--receiver-area=")) {
				receiverAreaPolicy = parseReceiverAreaPolicy(arg.substring("--receiver-area=".length()));
			} else if (arg.startsWith("--scale-receiver-area=")) {
				receiverAreaPolicy = parseScaleReceiverArea(arg.substring("--scale-receiver-area=".length()));
			} else if (arg.startsWith("--regenerate-network-file=")) {
				regenerateNetworkFile = parseBoolean(arg.substring("--regenerate-network-file=".length()),
					"--regenerate-network-file");
				regenerateNetworkFileOverridden = true;
			} else if (arg.equals("--reuse-network-file")) {
				regenerateNetworkFile = false;
				regenerateNetworkFileOverridden = true;
			} else {
				throw new IllegalArgumentException("Unknown argument: " + arg);
			}
		}

		if (networkSizeOverridden && !networkFileOverridden
			&& (defaults.networkFile() == null
			|| defaults.networkFile().equals(CreateFreightChessboardNetwork.defaultNetworkPath(defaults.networkSize())))) {
			networkFile = CreateFreightChessboardNetwork.defaultNetworkPath(networkSize);
		}
		if (networkFile == null) {
			networkFile = CreateFreightChessboardNetwork.defaultNetworkPath(networkSize);
		}
		if (networkFileOverridden && !regenerateNetworkFileOverridden) {
			regenerateNetworkFile = false;
		}
		return new ExperimentOptions(networkSize, carrierScenarioCount, instanceCount, networkFile, outputBaseDir,
			receiverAreaPolicy, regenerateNetworkFile);
	}

	private static int parsePositiveInt(String value, String optionName) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 1) {
				throw new IllegalArgumentException(optionName + " must be at least 1, got " + parsed);
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(optionName + " must be an integer, got " + value, e);
		}
	}

	private static ReceiverAreaPolicy parseReceiverAreaPolicy(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "scaled", "scale", "network" -> ReceiverAreaPolicy.SCALED_TO_NETWORK;
			case "chessboard", "fixed", "example", "chessboard-example" ->
				ReceiverAreaPolicy.CHESSBOARD_EXAMPLE_AREA;
			case "centered", "centered-chessboard", "chessboard-centered", "centered-example",
				 "centered-chessboard-example" -> ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA;
			default -> throw new IllegalArgumentException("--receiver-area must be scaled, chessboard, or centered, got "
				+ value);
		};
	}

	private static ReceiverAreaPolicy parseScaleReceiverArea(String value) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "true", "yes", "1" -> ReceiverAreaPolicy.SCALED_TO_NETWORK;
			case "false", "no", "0" -> ReceiverAreaPolicy.CHESSBOARD_EXAMPLE_AREA;
			default -> throw new IllegalArgumentException("--scale-receiver-area must be true or false, got "
				+ value);
		};
	}

	private static boolean parseBoolean(String value, String optionName) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "true", "yes", "1" -> true;
			case "false", "no", "0" -> false;
			default -> throw new IllegalArgumentException(optionName + " must be true or false, got " + value);
		};
	}

	private static void printUsageAndExit() {
		System.out.println("""
			Usage:
			  RunCollabReceiverDistantCarrier
			  RunCollabReceiverDistantCarrier --network-size=20 --carrier-scenarios=10 --instances=1
			  RunCollabReceiverDistantCarrier --network-size=50 --carrier-scenarios=10 --network-file=data/freightChessboardRC/generatedNetworks/grid50x50.xml
			  RunCollabReceiverDistantCarrier --receiver-area=scaled
			  RunCollabReceiverDistantCarrier --receiver-area=chessboard
			  RunCollabReceiverDistantCarrier --receiver-area=centered
			  RunCollabReceiverDistantCarrier --scale-receiver-area=false
			  RunCollabReceiverDistantCarrier --reuse-network-file
			""");
		System.exit(0);
	}
}
