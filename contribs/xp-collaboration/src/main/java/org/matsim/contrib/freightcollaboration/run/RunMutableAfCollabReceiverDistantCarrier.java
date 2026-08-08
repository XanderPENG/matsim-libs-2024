package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.MutableAfCarrierScoringFunctionFactory;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.CollaborationModule;
import org.matsim.contrib.freightcollaboration.controller.CollaboratorModules;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.contrib.freightcollaboration.listener.MutableAfLearningListener;
import org.matsim.contrib.freightcollaboration.listener.MutableAfReplanningCoordinator;
import org.matsim.contrib.freightcollaboration.listener.MutableAllocationFactorStatsListener;
import org.matsim.contrib.freightcollaboration.listener.PreservingReceiverTriggeredCarrierReplanningListener;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.contrib.freightcollaboration.strategy.MutableAfCarrierStrategyManagerProvider;
import org.matsim.contrib.freightcollaboration.strategy.MutableAfReceiverStrategyManager;
import org.matsim.contrib.freightcollaboration.utils.JspritCarrierRouteSolver;
import org.matsim.contrib.freightcollaboration.utils.LinkFreightAgentToFreightCollaborator;
import org.matsim.contrib.freightcollaboration.utils.MutableAfCarrierShipmentBuilder;
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
import org.matsim.freight.receiver.replanning.ReceiverStrategyManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.matsim.contrib.freightcollaboration.run.RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig;
import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * Opt-in experiment entry point in which each carrier learns an allocation factor through MATSim replanning.
 * Existing fixed-factor run classes deliberately do not install any of the components used here.
 */
public final class RunMutableAfCollabReceiverDistantCarrier {

	private static final Logger LOG = LogManager.getLogger(RunMutableAfCollabReceiverDistantCarrier.class);
	private static final int RECEIVER_COUNT = 10;
	private static final int DEFAULT_CARRIER_SCENARIOS = 10;
	private static final Path DEFAULT_OUTPUT_BASE_DIR = Path.of("output", "mutableAfCollabReceiverDistantCarrier");
	static final String EXPERIMENT_COMPLETE_MARKER = ".experiment-complete";

	private RunMutableAfCollabReceiverDistantCarrier() {
	}

	record MutableOptions(RunCollabReceiverDistantCarrier.ExperimentOptions experimentOptions,
			double initialAllocationFactor, double minAllocationFactor, double maxAllocationFactor,
			double allocationFactorStep, double mutationWeight, double freezeFraction,
			int maxFactorPlans, int newFactorMinDwell, int revisitFactorMinDwell,
			int stabilityWindow, int maxAdaptDwell, int evaluationWindow,
			double stabilityRelativeTolerance, double minExplorationProbability,
			double maxExplorationProbability, double exploitationBeta,
			int receiverPlansPerFactor, int lastIteration) {

		MutableAllocationFactorConfigGroup createConfigGroup() {
			MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
			config.setMinAllocationFactor(minAllocationFactor);
			config.setMaxAllocationFactor(maxAllocationFactor);
			config.setAllocationFactorStep(allocationFactorStep);
			config.setMutationWeight(mutationWeight);
			config.setDisableInnovationFraction(freezeFraction);
			config.setMaxFactorPlans(maxFactorPlans);
			config.setNewFactorMinDwell(newFactorMinDwell);
			config.setRevisitFactorMinDwell(revisitFactorMinDwell);
			config.setStabilityWindow(stabilityWindow);
			config.setMaxAdaptDwell(maxAdaptDwell);
			config.setEvaluationWindow(evaluationWindow);
			config.setStabilityRelativeTolerance(stabilityRelativeTolerance);
			config.setMinExplorationProbability(minExplorationProbability);
			config.setMaxExplorationProbability(maxExplorationProbability);
			config.setExploitationBeta(exploitationBeta);
			config.setMaxReceiverPlansPerFactor(receiverPlansPerFactor);
			config.setInitialAllocationFactor(initialAllocationFactor);
			config.validateGrid();
			config.finalizationIteration(0, lastIteration);
			return config;
		}
	}

	private record DepotScenario(String label, Id<Link> depotLinkId) {
	}

	public static void main(String[] args) {
		for (String argument : args) {
			if (argument.equals("--help") || argument.equals("-h")) {
				printUsage();
				return;
			}
		}
		MutableOptions options = parseOptions(args, defaultOptions());
		runExperiments(options);
	}

	static MutableOptions defaultOptions() {
		int networkSize = CreateFreightChessboardNetwork.DEFAULT_GRID_SIZE;
		RunCollabReceiverDistantCarrier.ExperimentOptions experimentOptions =
			new RunCollabReceiverDistantCarrier.ExperimentOptions(
				networkSize,
				DEFAULT_CARRIER_SCENARIOS,
				1,
				CreateFreightChessboardNetwork.defaultNetworkPath(networkSize),
				DEFAULT_OUTPUT_BASE_DIR,
				RunCollabReceiverDistantCarrier.ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA,
				true);
		return new MutableOptions(experimentOptions, 0.8, 0.1, 0.9, 0.05, 1.0, 0.9,
			5, 6, 3, 3, 15, 3, 1e-3, 0.10, 0.80, 4.0, 5, 100);
	}

	static MutableOptions parseOptions(String[] args, MutableOptions defaults) {
		List<String> sharedArguments = new ArrayList<>();
		double initial = defaults.initialAllocationFactor();
		double min = defaults.minAllocationFactor();
		double max = defaults.maxAllocationFactor();
		double step = defaults.allocationFactorStep();
		double mutationWeight = defaults.mutationWeight();
		double freezeFraction = defaults.freezeFraction();
		int maxFactorPlans = defaults.maxFactorPlans();
		int newFactorMinDwell = defaults.newFactorMinDwell();
		int revisitFactorMinDwell = defaults.revisitFactorMinDwell();
		int stabilityWindow = defaults.stabilityWindow();
		int maxAdaptDwell = defaults.maxAdaptDwell();
		int evaluationWindow = defaults.evaluationWindow();
		double stabilityTolerance = defaults.stabilityRelativeTolerance();
		double minExploration = defaults.minExplorationProbability();
		double maxExploration = defaults.maxExplorationProbability();
		double exploitationBeta = defaults.exploitationBeta();
		int receiverPlans = defaults.receiverPlansPerFactor();
		int lastIteration = defaults.lastIteration();

		for (String argument : args) {
			if (argument.startsWith("--initial-allocation-factor=")) {
				initial = parseFiniteDouble(argument, "--initial-allocation-factor");
			} else if (argument.startsWith("--allocation-factor-min=")) {
				min = parseFiniteDouble(argument, "--allocation-factor-min");
			} else if (argument.startsWith("--allocation-factor-max=")) {
				max = parseFiniteDouble(argument, "--allocation-factor-max");
			} else if (argument.startsWith("--allocation-factor-step=")) {
				step = parseFiniteDouble(argument, "--allocation-factor-step");
			} else if (argument.startsWith("--allocation-factor-mutation-weight=")) {
				mutationWeight = parseFiniteDouble(argument, "--allocation-factor-mutation-weight");
			} else if (argument.startsWith("--allocation-factor-freeze-fraction=")) {
				freezeFraction = parseFiniteDouble(argument, "--allocation-factor-freeze-fraction");
			} else if (argument.startsWith("--allocation-factor-max-plans=")) {
				maxFactorPlans = parsePositiveInt(argument, "--allocation-factor-max-plans");
			} else if (argument.startsWith("--allocation-factor-new-dwell=")) {
				newFactorMinDwell = parsePositiveInt(argument, "--allocation-factor-new-dwell");
			} else if (argument.startsWith("--allocation-factor-revisit-dwell=")) {
				revisitFactorMinDwell = parsePositiveInt(argument, "--allocation-factor-revisit-dwell");
			} else if (argument.startsWith("--allocation-factor-stability-window=")) {
				stabilityWindow = parsePositiveInt(argument, "--allocation-factor-stability-window");
			} else if (argument.startsWith("--allocation-factor-max-dwell=")) {
				maxAdaptDwell = parsePositiveInt(argument, "--allocation-factor-max-dwell");
			} else if (argument.startsWith("--allocation-factor-evaluation-window=")) {
				evaluationWindow = parsePositiveInt(argument, "--allocation-factor-evaluation-window");
			} else if (argument.startsWith("--allocation-factor-stability-relative-tolerance=")) {
				stabilityTolerance = parseFiniteDouble(argument,
					"--allocation-factor-stability-relative-tolerance");
			} else if (argument.startsWith("--allocation-factor-min-exploration-probability=")) {
				minExploration = parseFiniteDouble(argument,
					"--allocation-factor-min-exploration-probability");
			} else if (argument.startsWith("--allocation-factor-max-exploration-probability=")) {
				maxExploration = parseFiniteDouble(argument,
					"--allocation-factor-max-exploration-probability");
			} else if (argument.startsWith("--allocation-factor-exploitation-beta=")) {
				exploitationBeta = parseFiniteDouble(argument, "--allocation-factor-exploitation-beta");
			} else if (argument.startsWith("--receiver-plans-per-factor=")) {
				receiverPlans = parsePositiveInt(argument, "--receiver-plans-per-factor");
			} else if (argument.startsWith("--last-iteration=")) {
				lastIteration = parsePositiveInt(argument, "--last-iteration");
			} else {
				sharedArguments.add(argument);
			}
		}

		RunCollabReceiverDistantCarrier.ExperimentOptions experimentOptions =
			RunCollabReceiverDistantCarrier.parseOptions(sharedArguments.toArray(String[]::new),
				defaults.experimentOptions());
		MutableOptions parsed = new MutableOptions(experimentOptions, initial, min, max, step, mutationWeight,
			freezeFraction, maxFactorPlans, newFactorMinDwell, revisitFactorMinDwell, stabilityWindow,
			maxAdaptDwell, evaluationWindow, stabilityTolerance, minExploration, maxExploration,
			exploitationBeta, receiverPlans, lastIteration);
		parsed.createConfigGroup();
		return parsed;
	}

	private static double parseFiniteDouble(String argument, String optionName) {
		String value = argument.substring(argument.indexOf('=') + 1);
		try {
			double parsed = Double.parseDouble(value);
			if (!Double.isFinite(parsed)) {
				throw new IllegalArgumentException(optionName + " must be finite, got " + value);
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(optionName + " must be a number, got " + value, e);
		}
	}

	private static int parsePositiveInt(String argument, String optionName) {
		String value = argument.substring(argument.indexOf('=') + 1);
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 1) {
				throw new IllegalArgumentException(optionName + " must be positive, got " + value);
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(optionName + " must be an integer, got " + value, e);
		}
	}

	private static void runExperiments(MutableOptions mutableOptions) {
		RunCollabReceiverDistantCarrier.ExperimentOptions options = mutableOptions.experimentOptions();
		ensureNetworkFile(options);
		List<DepotScenario> depotScenarios = createDepotScenarios(options.networkSize(),
			options.carrierScenarioCount());
		List<Double> penalties = List.of(0.0014, 0.0028, 0.0056);
		List<RunCollabReceiverDistantCarrier.CustomerDistributionScenario> distributions = List.of(
			RunCollabReceiverDistantCarrier.CustomerDistributionScenario.CLUSTERED,
			RunCollabReceiverDistantCarrier.CustomerDistributionScenario.DISPERSED);

		for (int instance : IntStream.range(0, options.instanceCount()).toArray()) {
			for (RunCollabReceiverDistantCarrier.CustomerDistributionScenario distribution : distributions) {
				for (DepotScenario depot : depotScenarios) {
					for (double penalty : penalties) {
						runSingleExperiment(mutableOptions, penalty, instance, depot, distribution);
					}
				}
			}
		}
	}

	private static void runSingleExperiment(MutableOptions mutableOptions, double receiverPenalty, int instance,
			DepotScenario depot, RunCollabReceiverDistantCarrier.CustomerDistributionScenario distribution) {
		RunCollabReceiverDistantCarrier.ExperimentOptions options = mutableOptions.experimentOptions();
		String distributionLabel = distribution.name().toLowerCase(Locale.ROOT);
		String areaLabel = switch (options.receiverAreaPolicy()) {
			case SCALED_TO_NETWORK -> "scaledArea";
			case CHESSBOARD_EXAMPLE_AREA -> "chessboardArea";
			case CENTERED_CHESSBOARD_EXAMPLE_AREA -> "centeredChessboardArea";
		};
		String runId = ("%s-%s-%s-mutableAf-init%.2f-min%.2f-max%.2f-step%.2f-w%.2f-freeze%.2f-"
			+ "cap%d-dwell%d-%d-%d-last%d-p%.4f-exactShapley-i%02d").formatted(
			depot.label(), distributionLabel, areaLabel, mutableOptions.initialAllocationFactor(),
			mutableOptions.minAllocationFactor(), mutableOptions.maxAllocationFactor(),
			mutableOptions.allocationFactorStep(), mutableOptions.mutationWeight(),
			mutableOptions.freezeFraction(), mutableOptions.maxFactorPlans(),
			mutableOptions.newFactorMinDwell(), mutableOptions.revisitFactorMinDwell(),
			mutableOptions.evaluationWindow(), mutableOptions.lastIteration(), receiverPenalty, instance);

		Config config = createChessboardConfig(mutableOptions, runId);
		Path outputDirectory = Path.of(config.controller().getOutputDirectory());
		if (isExperimentComplete(outputDirectory)) {
			LOG.warn("Complete result marker exists in {}. Skipping this experiment.", outputDirectory);
			return;
		}
		config.global().setRandomSeed(4711L + instance);

		FreightCollaborationConfigGroup freightConfig = createExampleFreightCollaborationConfig();
		freightConfig.ALLOCATION_FACTOR = mutableOptions.initialAllocationFactor();
		freightConfig.RECEIVER_RELAXATION_PENALTY = receiverPenalty;
		freightConfig.ALLOCATION_MODEL = AllocationModels.SHAPLEY;
		freightConfig.setVrpMaxIterations(100);
		freightConfig.setPsimScoringModeString(
			FreightCollaborationConfigGroup.PsimScoringMode.BASIC_COST.toString());
		freightConfig.setIter0BaselineModeString(
			FreightCollaborationConfigGroup.Iter0BaselineMode.FEE_FREE.toString());
		config.addModule(freightConfig);

		MutableAllocationFactorConfigGroup mutableConfig = mutableOptions.createConfigGroup();
		config.addModule(mutableConfig);
		runSingleControler(config, mutableConfig, options, depot, distribution, instance);
		markExperimentComplete(outputDirectory);
	}

	private static Config createChessboardConfig(MutableOptions mutableOptions,
			String runId) {
		RunCollabReceiverDistantCarrier.ExperimentOptions options = mutableOptions.experimentOptions();
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(options.networkFile().toString());
		config.controller().setOutputDirectory(options.outputBaseDir()
			.resolve("grid%dx%d".formatted(options.networkSize(), options.networkSize()))
			.resolve(runId).toString() + "/");
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(mutableOptions.lastIteration());
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		config.global().setNumberOfThreads(4);
		config.qsim().setNumberOfThreads(1);
		return config;
	}

	private static void runSingleControler(Config config, MutableAllocationFactorConfigGroup mutableConfig,
			RunCollabReceiverDistantCarrier.ExperimentOptions options, DepotScenario depot,
			RunCollabReceiverDistantCarrier.CustomerDistributionScenario distribution, int instance) {
		Scenario scenario = ScenarioUtils.loadScenario(config);
		validateDepotLinkExists(scenario.getNetwork(), depot);

		Carriers sourceCarriers = RunCarrierReceiverCollabChessboardExample.generateExampleCarriers(
			depot.depotLinkId().toString());
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (Carrier carrier : sourceCarriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		ReceiverConfigGroup receiverConfig = ConfigUtils.addOrGetModule(scenario.getConfig(),
			ReceiverConfigGroup.class);
		receiverConfig.setReplanningType(ReceiverReplanningType.timeWindow);
		receiverConfig.setReceiverTriggerCarrierReplanning(false);
		FreightCollaborationConfigGroup freightConfig = ConfigUtils.addOrGetModule(scenario.getConfig(),
			FreightCollaborationConfigGroup.class);

		Receivers receivers = RunCollabReceiverDistantCarrier.generateReceivers(scenario.getNetwork(), RECEIVER_COUNT,
			distribution, instance, options.networkSize(), options.receiverAreaPolicy());
		ReceiverUtils.setReceivers(receivers, scenario);

		RunCarrierReceiverCollabChessboardExample.ReceiverOrderGeneration orderGeneration =
			new RunCarrierReceiverCollabChessboardExample.ReceiverOrderGeneration(receivers, sourceCarriers,
				depot.depotLinkId(), Id.create("carrier1", Carrier.class));
		orderGeneration.generateAllReceiverOrders();

		writeFreightScenario(scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario),
			CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		CarrierVehicleTypes sourceTypes = CarrierVehicleTypes.getVehicleTypes(scenarioCarriers);
		CarrierVehicleTypes scenarioTypes = CarriersUtils.getCarrierVehicleTypes(scenario);
		scenarioTypes.getVehicleTypes().putAll(sourceTypes.getVehicleTypes());
		bootstrapInitialCarrierPlans(scenario, mutableConfig, freightConfig.getVrpMaxIterations());

		Controler controler = new Controler(scenario);
		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(
			freightConfig.RECEIVER_FIXED_FEE));
		receiverModule.setReplanningType(ReceiverReplanningType.timeWindow);

		FreightCollaborators collaborators = createCollaborators(scenario);
		CollaboratorModules collaboratorModules = new CollaboratorModules(Map.of(
			CollaboratorRole.RECEIVER, receiverModule,
			CollaboratorRole.CARRIER, new CarrierModule()));
		CollaborationModule collaborationModule = new CollaborationModule(collaboratorModules, collaborators,
			scenario);
		collaborationModule.installAllCollaboratorModules(controler);
		controler.addOverridingModule(collaborationModule);

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				// Config groups added via config.addModule(...) are already bound by MATSim's
				// ExplodedConfigModule. Binding mutableConfig again here makes Guice fail with
				// BindingAlreadySet during controller injector creation.
				bind(CarrierStrategyManager.class).toProvider(
					new MutableAfCarrierStrategyManagerProvider(mutableConfig));
				bind(ReceiverStrategyManager.class).to(MutableAfReceiverStrategyManager.class);
				bind(MutableAfLearningStore.class).asEagerSingleton();
				bind(CarrierScoringFunctionFactory.class).to(MutableAfCarrierScoringFunctionFactory.class);
				bind(ReceiverScoringFunctionFactory.class).to(
					ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase.class);
				addControlerListenerBinding().to(MutableAfReplanningCoordinator.class);
				addControlerListenerBinding().to(PreservingReceiverTriggeredCarrierReplanningListener.class);
				addControlerListenerBinding().to(MutableAfLearningListener.class);
				addControlerListenerBinding().to(MutableAllocationFactorStatsListener.class);
			}
		});

		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()),
			controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();
	}

	static void bootstrapInitialCarrierPlans(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, int vrpIterations) {
		MutableAfCarrierShipmentBuilder.rebuild(scenario);
		JspritCarrierRouteSolver solver = new JspritCarrierRouteSolver(vrpIterations);
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			var initial = solver.solve(carrier, scenario);
			CarrierAllocationFactor.set(initial, mutableConfig.getInitialAllocationFactor(), mutableConfig);
			initial.setScore(null);
			initial.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
				MutableAfPlanUtils.selectedReceiverProfile(carrier,
					ReceiverUtils.getReceivers(scenario).getReceivers().values()));
			carrier.clearPlans();
			carrier.addPlan(initial);
			carrier.setSelectedPlan(initial);
		}
	}

	private static FreightCollaborators createCollaborators(Scenario scenario) {
		FreightCollaborators collaborators = new FreightCollaborators();
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			collaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(carrier, true));
		}
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			collaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(receiver, true));
		}
		return collaborators;
	}

	private static List<DepotScenario> createDepotScenarios(int networkSize, int scenarioCount) {
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
			scenarios.add(new DepotScenario(label,
				Id.createLinkId(CreateFreightChessboardNetwork.verticalLinkId(center, y))));
		}
		return List.copyOf(scenarios);
	}

	private static void validateDepotLinkExists(Network network, DepotScenario depot) {
		if (!network.getLinks().containsKey(depot.depotLinkId())) {
			throw new IllegalStateException("Depot link for " + depot.label() + " is missing in network: "
				+ depot.depotLinkId());
		}
	}

	private static void ensureNetworkFile(RunCollabReceiverDistantCarrier.ExperimentOptions options) {
		if (Files.exists(options.networkFile()) && !options.regenerateNetworkFile()) {
			LOG.info("Using existing chessboard network file: {}", options.networkFile());
			return;
		}
		CreateFreightChessboardNetwork.writeNetwork(options.networkSize(), options.networkFile());
	}

	static boolean isExperimentComplete(Path outputDirectory) {
		return Files.isRegularFile(outputDirectory.resolve(EXPERIMENT_COMPLETE_MARKER));
	}

	private static void markExperimentComplete(Path outputDirectory) {
		try {
			Files.writeString(outputDirectory.resolve(EXPERIMENT_COMPLETE_MARKER), "complete\n");
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write experiment completion marker in " + outputDirectory, e);
		}
	}

	private static void printUsage() {
		System.out.println("""
			Usage:
			  RunMutableAfCollabReceiverDistantCarrier [shared distant-carrier options]
			    --initial-allocation-factor=0.8
			    --allocation-factor-min=0.1
			    --allocation-factor-max=1.0
			    --allocation-factor-step=0.05
			    --allocation-factor-mutation-weight=1.0
			    --allocation-factor-freeze-fraction=0.9
			    --allocation-factor-max-plans=5
			    --allocation-factor-new-dwell=6
			    --allocation-factor-revisit-dwell=3
			    --allocation-factor-stability-window=3
			    --allocation-factor-max-dwell=15
			    --allocation-factor-evaluation-window=3
			    --allocation-factor-stability-relative-tolerance=0.001
			    --allocation-factor-min-exploration-probability=0.10
			    --allocation-factor-max-exploration-probability=0.80
			    --allocation-factor-exploitation-beta=4.0
			    --receiver-plans-per-factor=5
			    --last-iteration=100

			Shared options include --network-size, --carrier-scenarios, --instances,
			--network-file, --output-base, --receiver-area, and --reuse-network-file.
			""");
	}
}
