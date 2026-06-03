package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
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
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.CarrierVehicleTypes;
import org.matsim.freight.carriers.Carriers;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.receiver.Order;
import org.matsim.freight.receiver.ProductType;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverConfigGroup;
import org.matsim.freight.receiver.ReceiverModule;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverProduct;
import org.matsim.freight.receiver.ReceiverReplanningType;
import org.matsim.freight.receiver.ReceiverScoringFunctionFactory;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;
import org.matsim.vehicles.VehicleType;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.matsim.contrib.freightcollaboration.run.RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig;
import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * Runs the Leuven carrier-receiver collaboration experiment with externally generated 20-receiver demand files.
 * The input hierarchy is data/randomDemand20Receivers_nni/<depotScenario>/insX/demandX.csv.gz.
 */
public class Run20ReceiversWithDistantSingleDepot {
	private static final Logger logger = LogManager.getLogger(Run20ReceiversWithDistantSingleDepot.class);

	private static final Path INPUT_BASE_DIR = Path.of("data", "randomDemand20Receivers_nni");
	private static final Path OUTPUT_BASE_DIR = Path.of("data", "randomDemand20ReceiversOutput");
	private static final String RUN_TAG = "leuvenCRCollab20Receivers";
	private static final Pattern INSTANCE_DIR_PATTERN = Pattern.compile("ins(\\d+)");

	private enum AllocationMethod {
		SHAPLEY("exact_shapley", AllocationModels.SHAPLEY, null),
		APPROX_SHAPLEY_MC("approx_shapley_mc", AllocationModels.APPROX_SHAPLEY,
			AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO),
		APPROX_SHAPLEY_STRATIFIED("approx_shapley_stratified", AllocationModels.APPROX_SHAPLEY,
			AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED),
		PROPORTIONAL("proportional", AllocationModels.PROPORTIONAL, null),
		MARGINAL("marginal", AllocationModels.MARGINAL, null);

		final String label;
		final AllocationModels model;
		final AllocationModelApproxShapleyValue.ApproximationMethod approxMethod;

		AllocationMethod(String label, AllocationModels model,
						 AllocationModelApproxShapleyValue.ApproximationMethod approxMethod) {
			this.label = label;
			this.model = model;
			this.approxMethod = approxMethod;
		}
	}

	private record ScenarioInput(String depotScenario, int instanceId, Path demandFile) {
		String instanceFolder() {
			return "ins" + instanceId;
		}
	}

	private record ReceiverLocation(int receiverIndex, String placeId, Id<Link> receiverLinkId, Id<Link> depotLinkId) {
	}

	public static void main(String[] args) {
		double[] penaltySweep = {0.0003, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.0167, 0.0222, 0.028};
		double allocationFactor = 0.8;

		List<AllocationMethod> methods = List.of(
			AllocationMethod.SHAPLEY,
			AllocationMethod.MARGINAL,
			AllocationMethod.PROPORTIONAL,
			AllocationMethod.APPROX_SHAPLEY_MC,
			AllocationMethod.APPROX_SHAPLEY_STRATIFIED
		);

		Map<Integer, List<ScenarioInput>> inputsByInstance = groupInputsByInstance(discoverScenarioInputs());
		int completedInstances = 0;
		for (Map.Entry<Integer, List<ScenarioInput>> instanceEntry : inputsByInstance.entrySet()) {
			for (ScenarioInput input : instanceEntry.getValue()) {
				for (AllocationMethod allocationMethod : methods) {
					boolean skipChain = false;
					for (int pIdx = 0; pIdx < penaltySweep.length; pIdx++) {
						double penalty = penaltySweep[pIdx];
						if (pIdx > 0 && skipChain) {
							String runId = buildRunId(input, allocationMethod, allocationFactor, penalty);
							logRunStatus(input, runId, "SKIPPED",
								"Previous penalty scenario was skipped; skipping higher penalty as well.");
							continue;
						}
						if (pIdx > 0) {
							double prevPenalty = penaltySweep[pIdx - 1];
							boolean noCollab = isNoCollaborationInPreviousPenalty(input, allocationMethod,
								allocationFactor, prevPenalty);
							if (noCollab) {
								String runId = buildRunId(input, allocationMethod, allocationFactor, penalty);
								logRunStatus(input, runId, "SKIPPED",
									"Previous penalty " + prevPenalty + " had no collaboration (avg.EXECUTED <= -100).");
								skipChain = true;
								continue;
							}
						}
						runSingleScenario(input, allocationMethod, allocationFactor, penalty);
					}
				}
			}
			completedInstances++;
			if (completedInstances < inputsByInstance.size()) {
				sleepBetweenInstances();
			}
		}
	}

	private static Map<Integer, List<ScenarioInput>> groupInputsByInstance(List<ScenarioInput> scenarioInputs) {
		Map<Integer, List<ScenarioInput>> inputsByInstance = new TreeMap<>();
		for (ScenarioInput input : scenarioInputs) {
			inputsByInstance.computeIfAbsent(input.instanceId(), ignored -> new ArrayList<>()).add(input);
		}
		for (List<ScenarioInput> inputs : inputsByInstance.values()) {
			inputs.sort(Comparator.comparing(ScenarioInput::depotScenario));
		}
		return inputsByInstance;
	}

	private static List<ScenarioInput> discoverScenarioInputs() {
		if (!Files.isDirectory(INPUT_BASE_DIR)) {
			throw new IllegalStateException("Input directory does not exist: " + INPUT_BASE_DIR);
		}

		List<ScenarioInput> inputs = new ArrayList<>();
		try (Stream<Path> depotDirsStream = Files.list(INPUT_BASE_DIR)) {
			List<Path> depotDirs = depotDirsStream
				.filter(Files::isDirectory)
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.toList();

			for (Path depotDir : depotDirs) {
				try (Stream<Path> instanceDirsStream = Files.list(depotDir)) {
					List<Path> instanceDirs = instanceDirsStream
						.filter(Files::isDirectory)
						.sorted(Comparator.comparing(path -> path.getFileName().toString()))
						.toList();

					for (Path instanceDir : instanceDirs) {
						Matcher matcher = INSTANCE_DIR_PATTERN.matcher(instanceDir.getFileName().toString());
						if (!matcher.matches()) {
							logger.warn("Ignoring unexpected instance directory: {}", instanceDir);
							continue;
						}
						int instanceId = Integer.parseInt(matcher.group(1));
						Path demandFile = instanceDir.resolve("demand" + instanceId + ".csv.gz");
						if (!Files.isRegularFile(demandFile)) {
							logger.warn("Ignoring scenario without demand file: {}", demandFile);
							continue;
						}
						inputs.add(new ScenarioInput(depotDir.getFileName().toString(), instanceId, demandFile));
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to discover random demand scenarios under " + INPUT_BASE_DIR, e);
		}

		if (inputs.isEmpty()) {
			throw new IllegalStateException("No demand files discovered under " + INPUT_BASE_DIR);
		}
		logger.info("Discovered {} scenarios under {}", inputs.size(), INPUT_BASE_DIR);
		return List.copyOf(inputs);
	}

	private static void runSingleScenario(ScenarioInput input, AllocationMethod allocationMethod,
										  double allocationFactor, double receiverCollaborationPenalty) {
		String runId = buildRunId(input, allocationMethod, allocationFactor, receiverCollaborationPenalty);
		Config config = createConfig(input, runId);

		String outputDir = config.controller().getOutputDirectory();
		if (new File(outputDir).exists()) {
			logger.warn("Output directory {} already exists. Skipping this experiment.", outputDir);
			logRunStatus(input, runId, "SKIPPED", "Output directory exists; assuming completed previously.");
			return;
		}
		logger.info("Running experiment with runId: {}", runId);

		FreightCollaborationConfigGroup freightCollaborationConfigGroup = createExampleFreightCollaborationConfig();
		freightCollaborationConfigGroup.ALLOCATION_FACTOR = allocationFactor;
		freightCollaborationConfigGroup.RECEIVER_RELAXATION_PENALTY = receiverCollaborationPenalty;
		freightCollaborationConfigGroup.ALLOCATION_MODEL = allocationMethod.model;
		if (allocationMethod.model == AllocationModels.APPROX_SHAPLEY && allocationMethod.approxMethod != null) {
			freightCollaborationConfigGroup.APPROX_SHAPLEY_METHOD = allocationMethod.approxMethod.name();
		}
		freightCollaborationConfigGroup.setVrpMaxIterations(100);
		freightCollaborationConfigGroup.setParallelism(4);
		freightCollaborationConfigGroup.setSamplesRatio(0.4);
		freightCollaborationConfigGroup.setMonteCarloSamples(10);
		freightCollaborationConfigGroup.setStratifiedSamplesPerLevel(10);
		freightCollaborationConfigGroup.setMaxStratifiedEvaluations(200);
		config.addModule(freightCollaborationConfigGroup);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		List<ReceiverLocation> receiverLocations = readReceiverLocations(input.demandFile());
		validateDemandLinksExist(scenario, input, receiverLocations);

		Carriers carriers = generateScenarioCarriers(receiverLocations);
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (Carrier carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);

		Receivers receivers = generateScenarioReceivers(carriers, receiverLocations);
		ReceiverUtils.setReceivers(receivers, scenario);

		writeFreightScenario(scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario), CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		Controler controler = new Controler(scenario);

		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(
			freightCollaborationConfigGroup.RECEIVER_FIXED_FEE));
		receiverModule.setReplanningType(ReceiverReplanningType.timeWindow);

		FreightCollaborators freightCollaborators = new FreightCollaborators();
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			freightCollaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(carrier, true));
		}
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			freightCollaborators.addFreightCollaborator(LinkFreightAgentToFreightCollaborator.map(receiver, true));
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
		logRunStatus(input, runId, "RUN", "Completed MATSim run.");
	}

	private static Config createConfig(ScenarioInput input, String runId) {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile("data/GemeenteLeuvenWithHbefaType/carGemeenteLeuvenWithHbefaType.xml.gz");
		config.controller().setOutputDirectory(outputDir(input, runId).toString() + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(30);
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		config.global().setNumberOfThreads(4);
		return config;
	}

	private static String buildRunId(ScenarioInput input, AllocationMethod allocationMethod,
									 double allocationFactor, double receiverCollaborationPenalty) {
		return "%s-%s-%s-af%.2f-p%.4f-i%02d".formatted(RUN_TAG, input.depotScenario(), allocationMethod.label,
			allocationFactor, receiverCollaborationPenalty, input.instanceId());
	}

	private static Path outputDir(ScenarioInput input, String runId) {
		return OUTPUT_BASE_DIR.resolve(input.depotScenario()).resolve(input.instanceFolder()).resolve(runId);
	}

	private static void logRunStatus(ScenarioInput input, String runId, String status, String reason) {
		Path outputDir = outputDir(input, runId);
		try {
			Files.createDirectories(outputDir);
			Path log = outputDir.resolve("run_log.txt");
			String line = "%s\t%s\t%s%n".formatted(status, runId, reason);
			Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			logger.warn("Failed to write run log for {}: {}", runId, e.getMessage());
		}
	}

	private static boolean isNoCollaborationInPreviousPenalty(ScenarioInput input,
															  AllocationMethod allocationMethod,
															  double allocationFactor,
															  double previousPenalty) {
		String prevRunId = buildRunId(input, allocationMethod, allocationFactor, previousPenalty);
		Path receiverScores = outputDir(input, prevRunId).resolve("receiver_scores.txt");
		if (!Files.exists(receiverScores)) {
			return false;
		}
		OptionalDouble lastAvgExecuted = readLastAvgExecuted(receiverScores);
		return lastAvgExecuted.isPresent() && lastAvgExecuted.getAsDouble() <= -100.0;
	}

	private static OptionalDouble readLastAvgExecuted(Path receiverScores) {
		try (BufferedReader reader = Files.newBufferedReader(receiverScores, StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			if (header == null) {
				return OptionalDouble.empty();
			}
			boolean tabDelimited = header.contains("\t");
			String[] headTokens = tabDelimited ? header.split("\t") : header.trim().split("\\s+");
			int avgExecIdx = -1;
			for (int i = 0; i < headTokens.length; i++) {
				String normalized = headTokens[i].replaceAll("[\\s\\.]", "").toLowerCase();
				if (normalized.equals("avgexecuted")) {
					avgExecIdx = i;
					break;
				}
			}
			if (avgExecIdx < 0 && headTokens.length >= 2) {
				for (int i = 0; i < headTokens.length - 1; i++) {
					if (headTokens[i].equalsIgnoreCase("avg.") && headTokens[i + 1].equalsIgnoreCase("EXECUTED")) {
						avgExecIdx = tabDelimited ? i : 1;
						break;
					}
				}
			}
			if (avgExecIdx < 0 && (tabDelimited ? headTokens.length >= 2 : true)) {
				avgExecIdx = 1;
			}

			String line;
			String last = null;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					last = line;
				}
			}
			if (last == null) {
				return OptionalDouble.empty();
			}
			String[] tokens = tabDelimited ? last.split("\t") : last.trim().split("\\s+");
			if (avgExecIdx >= tokens.length) {
				return OptionalDouble.empty();
			}
			return OptionalDouble.of(Double.parseDouble(tokens[avgExecIdx]));
		} catch (IOException | NumberFormatException e) {
			logger.warn("Failed to parse receiver_scores.txt at {}: {}", receiverScores, e.getMessage());
			return OptionalDouble.empty();
		}
	}

	private static Carriers generateScenarioCarriers(List<ReceiverLocation> receiverLocations) {
		Carriers carriers = new Carriers();
		Set<Id<Link>> depotLinkIds = new LinkedHashSet<>();
		for (ReceiverLocation receiverLocation : receiverLocations) {
			depotLinkIds.add(receiverLocation.depotLinkId());
		}

		VehicleType lightVanType = CarrierVehicleType.Builder.newInstance(Id.create("light", VehicleType.class))
			.setCapacity(3000)
			.setFixCost(300)
			.setMaxVelocity(25 / 3.6)
			.setCostPerDistanceUnit(8.5E-4)
			.setCostPerTimeUnit(0.0125)
			.build();
		lightVanType.setNetworkMode("car");

		VehicleType heavyVanType = CarrierVehicleType.Builder.newInstance(Id.create("heavy", VehicleType.class))
			.setCapacity(5000)
			.setFixCost(500)
			.setMaxVelocity(25 / 3.6)
			.setCostPerDistanceUnit(1.22E-3)
			.setCostPerTimeUnit(0.0167)
			.build();
		heavyVanType.setNetworkMode("car");

		for (Id<Link> depotLinkId : depotLinkIds) {
			String carrierIdStr = carrierIdForDepot(depotLinkId).toString();
			Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierIdStr, Carrier.class));

			CarrierVehicle lightVan = CarrierVehicle.Builder.newInstance(
					Id.createVehicleId("lightVan_" + carrierIdStr),
					depotLinkId,
					lightVanType)
				.setEarliestStart(5 * 60 * 60)
				.build();

			CarrierVehicle heavyVan = CarrierVehicle.Builder.newInstance(
					Id.createVehicleId("heavyVan_" + carrierIdStr),
					depotLinkId,
					heavyVanType)
				.setEarliestStart(5 * 60 * 60)
				.build();

			CarrierCapabilities carrierCapabilities = CarrierCapabilities.Builder.newInstance()
				.addVehicle(lightVan)
				.addVehicle(heavyVan)
				.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
				.build();
			carrier.setCarrierCapabilities(carrierCapabilities);
			carriers.addCarrier(carrier);
		}

		return carriers;
	}

	private static Receivers generateScenarioReceivers(Carriers carriers, List<ReceiverLocation> receiverLocations) {
		Receivers receivers = ReceiverUtils.createReceivers();
		for (ReceiverLocation receiverLocation : receiverLocations) {
			Receiver receiver = ReceiverUtils.newInstance(
				Id.create("receiver_%02d".formatted(receiverLocation.receiverIndex()), Receiver.class));
			receiver.setLinkId(receiverLocation.receiverLinkId());
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			receiver.getAttributes().putAttribute("affiliatedCarrierId", carrierIdForDepot(receiverLocation.depotLinkId()));
			receiver.getAttributes().putAttribute("depotLinkId", receiverLocation.depotLinkId().toString());
			if (!receiverLocation.placeId().isBlank()) {
				receiver.getAttributes().putAttribute("placeId", receiverLocation.placeId());
			}
			receivers.addReceiver(receiver);
		}

		for (Receiver receiver : receivers.getReceivers().values()) {
			@SuppressWarnings("unchecked")
			Id<Carrier> carrierId = (Id<Carrier>) receiver.getAttributes().getAttribute("affiliatedCarrierId");
			Carrier carrier = carriers.getCarriers().get(carrierId);
			if (carrier == null) {
				throw new IllegalStateException("Carrier not found for receiver " + receiver.getId() + ": " + carrierId);
			}
			generateReceiverOrders(carrier, receivers, receiver);
		}

		return receivers;
	}

	private static List<ReceiverLocation> readReceiverLocations(Path demandFile) {
		List<ReceiverLocation> locations = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new GZIPInputStream(Files.newInputStream(demandFile)), StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (header == null) {
				throw new IllegalStateException("Empty receiver location file: " + demandFile);
			}

			String[] headerTokens = header.split(",", -1);
			int placeIdIdx = findColumn(headerTokens, "place_id");
			int matchedLinkIdx = findColumn(headerTokens, "matched_link_id");
			int depotLinkIdx = findColumn(headerTokens, "depot_linkId");
			if ((matchedLinkIdx < 0 || depotLinkIdx < 0) && headerTokens.length >= 4) {
				matchedLinkIdx = 2;
				depotLinkIdx = 3;
			}
			if (matchedLinkIdx < 0 || depotLinkIdx < 0) {
				throw new IllegalArgumentException("Could not find matched_link_id and depot_linkId columns in " + demandFile);
			}
			int maxRequiredIndex = Math.max(matchedLinkIdx, depotLinkIdx);

			String line;
			int lineNo = 1;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				if (line.isBlank()) {
					continue;
				}

				String[] tokens = line.split(",", -1);
				if (tokens.length <= maxRequiredIndex) {
					throw new IllegalArgumentException("Invalid csv line at " + demandFile + ":" + lineNo + " -> " + line);
				}

				String receiverLinkId = tokens[matchedLinkIdx].trim();
				String depotLinkId = tokens[depotLinkIdx].trim();
				if (receiverLinkId.isEmpty() || depotLinkId.isEmpty()) {
					throw new IllegalArgumentException("Blank receiver or depot link at " + demandFile + ":" + lineNo);
				}

				String placeId = "";
				if (placeIdIdx >= 0 && placeIdIdx < tokens.length) {
					placeId = tokens[placeIdIdx].trim();
				}

				locations.add(new ReceiverLocation(locations.size(), placeId,
					Id.createLinkId(receiverLinkId), Id.createLinkId(depotLinkId)));
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read receiver location file: " + demandFile, e);
		}

		if (locations.isEmpty()) {
			throw new IllegalStateException("No receiver locations found in " + demandFile);
		}
		return List.copyOf(locations);
	}

	private static int findColumn(String[] headerTokens, String columnName) {
		String normalizedColumnName = normalizeHeader(columnName);
		for (int i = 0; i < headerTokens.length; i++) {
			if (normalizeHeader(headerTokens[i]).equals(normalizedColumnName)) {
				return i;
			}
		}
		return -1;
	}

	private static String normalizeHeader(String value) {
		return value.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private static void validateDemandLinksExist(Scenario scenario, ScenarioInput input, List<ReceiverLocation> receiverLocations) {
		Map<Id<Link>, String> missingLinks = new LinkedHashMap<>();
		for (ReceiverLocation receiverLocation : receiverLocations) {
			if (!scenario.getNetwork().getLinks().containsKey(receiverLocation.receiverLinkId())) {
				missingLinks.put(receiverLocation.receiverLinkId(), "receiver");
			}
			if (!scenario.getNetwork().getLinks().containsKey(receiverLocation.depotLinkId())) {
				missingLinks.put(receiverLocation.depotLinkId(), "depot");
			}
		}

		if (!missingLinks.isEmpty()) {
			List<String> examples = new ArrayList<>();
			for (Map.Entry<Id<Link>, String> entry : missingLinks.entrySet()) {
				examples.add(entry.getValue() + "=" + entry.getKey());
				if (examples.size() == 10) {
					break;
				}
			}
			throw new IllegalStateException("Demand file " + input.demandFile()
				+ " references links missing from the Leuven network: " + examples);
		}
	}

	private static Id<Carrier> carrierIdForDepot(Id<Link> depotLinkId) {
		return Id.create("carrier_" + depotLinkId, Carrier.class);
	}

	private static void generateReceiverOrders(Carrier carrier, Receivers receivers, Receiver receiver) {
		ProductType productType = ReceiverUtils.createAndGetProductType(receivers, Id.create("productType1", ProductType.class),
			carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId());
		productType.setRequiredCapacity(5);

		ReceiverProduct receiverProduct = ReceiverProduct.Builder.newInstance()
			.setProductType(productType)
			.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 200))
			.build();
		receiver.addProduct(receiverProduct);

		Collection<Order> orders = new ArrayList<>();
		Order order = Order.Builder.newInstance(Id.create("Order" + receiver.getId(), Order.class), receiver, receiverProduct)
			.setServiceTime(15 * 60)
			.buildWithCalculatedOrderQuantity();
		orders.add(order);

		ReceiverOrder receiverOrder = new ReceiverOrder(receiver.getId(), orders, carrier.getId());

		ReceiverPlan receiverPlan = ReceiverPlan.Builder.newInstance(receiver, true)
			.addReceiverOrder(receiverOrder)
			.addTimeWindow(TimeWindow.newInstance(6 * 60 * 60, 8 * 60 * 60))
			.build();

		receiver.addPlan(receiverPlan);
		receiver.setSelectedPlan(receiverPlan);

		Id<CarrierShipment> shipmentId = Id.create("shipment_" + receiverOrder.getReceiverId(), CarrierShipment.class);

		CarrierShipment shipment = CarrierShipment.Builder.newInstance(
				shipmentId,
				carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId(),
				receiver.getLinkId(),
				1)
			.setPickupDuration(300)
			.setDeliveryDuration(300)
			.build();

		CarriersUtils.addShipment(carrier, shipment);
	}

	private static void sleepBetweenInstances() {
		try {
			Thread.sleep(20 * 60 * 1000L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
