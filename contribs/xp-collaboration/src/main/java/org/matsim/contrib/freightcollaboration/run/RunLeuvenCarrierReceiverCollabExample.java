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
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierModule;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;
import org.matsim.freight.carriers.usecases.analysis.CarrierScoreStats;
import org.matsim.freight.receiver.*;
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
import java.util.*;
import java.util.OptionalDouble;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

import static org.matsim.contrib.freightcollaboration.run.RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig;
import static org.matsim.freight.receiver.run.chessboard.ReceiverChessboardScenario.writeFreightScenario;

/**
 * This run example aims to demonstrate how carrier-receiver collaborate in a pseudo-realistic scenario (Leuven).
 * 100 receivers are randomly generated based on POIs, according to two spatial distributions (clustered and uniform).
 * Accordingly, 4 carriers are created (1.four corners inside the city; 2. Four directions (east, west, north, south) outside the city).
 * Each carrier has 25 shipments to deliver to the receivers.
 * The collaboration is expected to improve the overall logistics cost by allowing receivers to relax their TWs,
 * and correspondingly carriers can optimize their routes and pay back receivers based on the cost savings.
 */
public class RunLeuvenCarrierReceiverCollabExample {
	// Logger
	private static final Logger logger = LogManager.getLogger(RunLeuvenCarrierReceiverCollabExample.class);

	private static final String OUTPUT_BASE_DIR = "leuvenCarrierReceiverCollab60Receivers/";
	// Spatial distribution of receivers
	private enum ReceiverSpatialDistribution {
//		FULLY_RANDOM,
		CLUSTERED,
		DISPERSED;
	}


	/* Depot scenarios for carriers
	 * 1. Inner city: Four depots in four corners inside the city ring
	 * 2. Outer city: Four depots in four directions outside the city ring
	 */
	private enum DepotLocation {
		INSIDE("inside",
			Set.of(
				Id.createLinkId("3390195_0"),
				Id.createLinkId("893781380_r_0-48247088_0"),
				Id.createLinkId("10149534_2"),
				Id.createLinkId("150056709_r_10")
			)
		),
		OUTSIDE("outside",
			Set.of(
				Id.createLinkId("333784188_r_3"),
				Id.createLinkId("27566523_11"),
				Id.createLinkId("25806807_r_1"),
				Id.createLinkId("131757263_1-131757263_2")
			)
		);

		final String label;
		final Set<Id<Link>> depotLinkIds;

		DepotLocation(String label, Set<Id<Link>> depotLinkIds) {
			this.label = label;
			this.depotLinkIds = depotLinkIds;
		}

	}

	private enum AllocationMethod {
		SHAPLEY("exact_shapley", AllocationModels.SHAPLEY, null),
		APPROX_SHAPLEY_MC("approx_shapley_mc", AllocationModels.APPROX_SHAPLEY,
				AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO),
		APPROX_SHAPLEY_STRATIFIED("approx_shapley_stratified", AllocationModels.APPROX_SHAPLEY,
				AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED),
		PROPORTIONAL("proportional", AllocationModels.PROPORTIONAL, null),
		MARGINAL("marginal", AllocationModels.MARGINAL, null);

		public final String label;
		public final AllocationModels model;
		public final AllocationModelApproxShapleyValue.ApproximationMethod approxMethod;

		AllocationMethod(String label, AllocationModels model,
						 AllocationModelApproxShapleyValue.ApproximationMethod approxMethod) {
			this.label = label;
			this.model = model;
			this.approxMethod = approxMethod;
		}
	}


	public static void main(String[] args) {

		List<Integer> instances = IntStream.range(0, 2).boxed().toList();
		int instance = 1;

		double[] penaltySweep = {0, 0.0003, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.028};
//		double[] allocSweepShort = {0.6, 0.75, 0.9};
		double allocSweepShort = 0.8;

		List<AllocationMethod> methods = List.of(
//			AllocationMethod.SHAPLEY,
			AllocationMethod.MARGINAL,
			AllocationMethod.PROPORTIONAL,
			AllocationMethod.APPROX_SHAPLEY_MC,
			AllocationMethod.APPROX_SHAPLEY_STRATIFIED
		);

		for (ReceiverSpatialDistribution distribution : ReceiverSpatialDistribution.values()) {
			for (DepotLocation depotLocation : DepotLocation.values()) {
				for (AllocationMethod allocationMethod : methods) {
					boolean skipChain = false; // once a penalty step is skipped, skip all higher penalties
					for (int pIdx = 0; pIdx < penaltySweep.length; pIdx++) {
						double penalty = penaltySweep[pIdx];
						if (pIdx > 0 && skipChain) {
							String runId = buildRunId(instance, "leuvenCRCollab", distribution, depotLocation,
								allocationMethod, allocSweepShort, penalty);
							logRunStatus(runId, "SKIPPED", "Previous penalty scenario was skipped; skipping higher penalty as well.");
							continue;
						}
						if (pIdx > 0) {
							double prevPenalty = penaltySweep[pIdx - 1];
							boolean noCollab = isNoCollaborationInPreviousPenalty(distribution, depotLocation, allocationMethod,
								allocSweepShort, prevPenalty, instance);
							if (noCollab) {
								String runId = buildRunId(instance, "leuvenCRCollab", distribution, depotLocation,
									allocationMethod, allocSweepShort, penalty);
								logRunStatus(runId, "SKIPPED", "Previous penalty " + prevPenalty + " had no collaboration (avg.EXECUTED <= -100).");
								skipChain = true;
								continue;
							}
						}
						runSingleLeuvenScenario(instance, "leuvenCRCollab", distribution, depotLocation,
								allocationMethod, allocSweepShort, penalty);
					}
				}
			}

		}
	}

	private static void runSingleLeuvenScenario(int instanceId, String tag,
												ReceiverSpatialDistribution distribution, DepotLocation depotLocation,
												AllocationMethod allocationMethod,
												double allocationFactor, double receiverCollaborationPenalty)  {
		String runId = buildRunId(instanceId, tag, distribution, depotLocation, allocationMethod, allocationFactor, receiverCollaborationPenalty);
		Config config = createConfig(runId);

		// Get the output directory and check if it already exists
		String outputDir = config.controller().getOutputDirectory();
		// If it exists, skip this experiment
		if (new File(outputDir).exists()) {
			logger.warn("Output directory {} already exists. Skipping this experiment.", outputDir);
			logRunStatus(runId, "SKIPPED", "Output directory exists; assuming completed previously.");
			return;
		} else {
			logger.info("Running experiment with runId: {}", runId);
		}

		// Create freight collaboration config group and set parameters
		FreightCollaborationConfigGroup freightCollaborationConfigGroup = createExampleFreightCollaborationConfig();
		freightCollaborationConfigGroup.ALLOCATION_FACTOR = allocationFactor;
		freightCollaborationConfigGroup.RECEIVER_RELAXATION_PENALTY = receiverCollaborationPenalty;
		freightCollaborationConfigGroup.ALLOCATION_MODEL = allocationMethod.model;
		if (allocationMethod.model == AllocationModels.APPROX_SHAPLEY && allocationMethod.approxMethod != null) {
			freightCollaborationConfigGroup.APPROX_SHAPLEY_METHOD = allocationMethod.approxMethod.name();
		}
		freightCollaborationConfigGroup.setVrpMaxIterations(50);
		freightCollaborationConfigGroup.setParallelism(4);
		freightCollaborationConfigGroup.setSamplesRatio(0.4);
		freightCollaborationConfigGroup.setMonteCarloSamples(10);
		freightCollaborationConfigGroup.setStratifiedSamplesPerLevel(10);
		freightCollaborationConfigGroup.setMaxStratifiedEvaluations(200);
		config.addModule(freightCollaborationConfigGroup);

		// Load scenario
		Scenario scenario = ScenarioUtils.loadScenario(config);

		// Generate carriers based on the specified depot locations
		Carriers carriers = generateSecenarioCarriers(depotLocation);
		// Add generated carriers to the scenario
		Carriers scenarioCarriers = CarriersUtils.addOrGetCarriers(scenario);
		for (var carrier : carriers.getCarriers().values()) {
			scenarioCarriers.addCarrier(carrier);
		}

		// Add receiver config group and set replanning type to timeWindow
		ReceiverConfigGroup receiverConfigGroup = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		receiverConfigGroup.setReplanningType(ReceiverReplanningType.timeWindow);

		// Add receivers and orders
		Receivers receivers = generateScenarioReceivers(scenario, carriers, depotLocation, distribution, instanceId);
		ReceiverUtils.setReceivers(receivers, scenario);

		// Some settings for the Receivers package
		writeFreightScenario(scenario);
		CollaborationUtils.linkReceiverOrdersToCarriers(ReceiverUtils.getReceivers(scenario), CarriersUtils.getCarriers(scenario));
		CollaborationUtils.createCoalitionWithCarriersAndAddCollaboratingReceivers(scenario);

		Controler controler = new Controler(scenario);

		ReceiverModule receiverModule = new ReceiverModule(ReceiverUtils.createFixedReceiverCostAllocation(freightCollaborationConfigGroup.RECEIVER_FIXED_FEE));
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
				bind(CarrierStrategyManager.class).toProvider(new RunCarrierReceiverCollabChessboardExample.MyCarrierPlanStrategyManagerProvider(types));
				bind(CarrierScoringFunctionFactory.class).to(ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.class);
				bind(ReceiverScoringFunctionFactory.class).to(ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase.class);
			}
		});

		CarrierScoreStats scoreStats = new CarrierScoreStats(CarriersUtils.getCarriers(controler.getScenario()), controler.getScenario().getConfig().controller().getOutputDirectory() + "/carrier_scores", true);
		controler.addControlerListener(scoreStats);
		controler.run();
		logRunStatus(runId, "RUN", "Completed MATSim run.");

	}

	private static Config createConfig(String runId) {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile("data/GemeenteLeuvenWithHbefaType/carGemeenteLeuvenWithHbefaType.xml.gz");
		config.controller().setOutputDirectory("output/" + OUTPUT_BASE_DIR + runId + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(30);
		// set writing output every 5 iterations
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		config.global().setNumberOfThreads(4);
		return config;
	}

	private static String buildRunId(int instanceId, String tag,
									 ReceiverSpatialDistribution distribution, DepotLocation depotLocation,
									 AllocationMethod allocationMethod,
									 double allocationFactor, double receiverCollaborationPenalty) {
		return "%s-%s-%s-%s-af%.2f-p%.4f-i%02d".formatted(tag, depotLocation.label, distribution.name().toLowerCase(),
			allocationMethod.label, allocationFactor, receiverCollaborationPenalty, instanceId);
	}

	private static void logRunStatus(String runId, String status, String reason) {
		Path outputDir = Path.of("output", OUTPUT_BASE_DIR, runId);
		try {
			Files.createDirectories(outputDir);
			Path log = outputDir.resolve("run_log.txt");
			String line = "%s\t%s\t%s%n".formatted(status, runId, reason);
			Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			logger.warn("Failed to write run log for {}: {}", runId, e.getMessage());
		}
	}

	private static boolean isNoCollaborationInPreviousPenalty(ReceiverSpatialDistribution distribution,
															  DepotLocation depotLocation,
															  AllocationMethod allocationMethod,
															  double allocationFactor,
															  double previousPenalty,
															  int instanceId) {
		String prevRunId = buildRunId(instanceId, "leuvenCRCollab", distribution, depotLocation, allocationMethod,
			allocationFactor, previousPenalty);
		Path receiverScores = Path.of("output", OUTPUT_BASE_DIR, prevRunId, "receiver_scores.txt");
		if (!Files.exists(receiverScores)) {
			return false;
		}
		OptionalDouble lastAvgExecuted = readLastAvgExecuted(receiverScores);
		return lastAvgExecuted.isPresent() && lastAvgExecuted.getAsDouble() <= -100.0;
	}

	private static OptionalDouble readLastAvgExecuted(Path receiverScores) {
		try (BufferedReader reader = Files.newBufferedReader(receiverScores, StandardCharsets.UTF_8)) {
			String header = reader.readLine();
			if (header == null) return OptionalDouble.empty();
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
			// fallback: header split by whitespace produced multiple tokens ("avg.", "EXECUTED")
			if (avgExecIdx < 0 && headTokens.length >= 2) {
				for (int i = 0; i < headTokens.length - 1; i++) {
					if (headTokens[i].equalsIgnoreCase("avg.") && headTokens[i + 1].equalsIgnoreCase("EXECUTED")) {
						avgExecIdx = tabDelimited ? i : 1; // data lines are single token per column when tab-delimited; otherwise numeric at index 1
						break;
					}
				}
			}
			// final fallback to the typical second column (ITERATION, avg.EXECUTED, ...)
			if (avgExecIdx < 0 && (tabDelimited ? headTokens.length >= 2 : true)) {
				avgExecIdx = 1;
			}

			String line;
			String last = null;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) continue;
				last = line;
			}
			if (last == null) return OptionalDouble.empty();
			String[] tokens = tabDelimited ? last.split("\t") : last.trim().split("\\s+");
			if (avgExecIdx >= tokens.length) return OptionalDouble.empty();
			return OptionalDouble.of(Double.parseDouble(tokens[avgExecIdx]));
		} catch (IOException | NumberFormatException e) {
			logger.warn("Failed to parse receiver_scores.txt at {}: {}", receiverScores, e.getMessage());
			return OptionalDouble.empty();
		}
	}

	private static Carriers generateSecenarioCarriers(DepotLocation depotLocation){
		Carriers carriers = new Carriers();

		VehicleType lightVanType = CarrierVehicleType.Builder.newInstance(Id.create("light", VehicleType.class))
			.setCapacity(3000)
			.setFixCost(300)
			.setMaxVelocity(25/3.6)
			.setCostPerDistanceUnit(8.5E-4)
			.setCostPerTimeUnit(0.0125)  // change to 0.005 euro/sec = 18 euro/hr?
			.build();
		lightVanType.setNetworkMode("car");

		VehicleType heavyVanType = CarrierVehicleType.Builder.newInstance(Id.create("heavy", VehicleType.class))
			.setCapacity(5000)
			.setFixCost(500)
			.setMaxVelocity(25/3.6)
			.setCostPerDistanceUnit(1.22E-3)
			.setCostPerTimeUnit(0.0167)  // change to 0.006 euro/sec = 21.6 euro/hr?
			.build();
		heavyVanType.setNetworkMode("car");

		for (Id<Link> depotLinkId : depotLocation.depotLinkIds) {
			String carrierIdStr = "carrier_" + depotLinkId.toString();
			Carrier carrier = CarriersUtils.createCarrier(Id.create(carrierIdStr, Carrier.class));

			CarrierVehicle lightVan = CarrierVehicle.Builder.newInstance(
					Id.createVehicleId("lightVan_" + carrierIdStr),
					Id.createLinkId(depotLinkId),
					lightVanType)
				.setEarliestStart(5 * 60 * 60)
				.build();

			CarrierVehicle heavyVan = CarrierVehicle.Builder.newInstance(
					Id.createVehicleId("heavyVan_" + carrierIdStr),
					Id.createLinkId(depotLinkId),
					heavyVanType)
				.setEarliestStart(5 * 60 * 60)
				.build();

			CarrierCapabilities carrierCapabilities1 = CarrierCapabilities.Builder.newInstance()
				.addVehicle(lightVan)
				.addVehicle(heavyVan)
				.setFleetSize(CarrierCapabilities.FleetSize.INFINITE)
				.build();
			carrier.setCarrierCapabilities(carrierCapabilities1);
			carriers.addCarrier(carrier);
		}

		return carriers;
	}

	private static Receivers generateScenarioReceivers(Scenario scenario,
													   Carriers carriers,
													   DepotLocation depotLocation,
													   ReceiverSpatialDistribution distribution,
													   int instanceId) {
		Receivers receivers = ReceiverUtils.createReceivers();

		Map<Id<Link>, String> receiverLocations = readReceiverLocation(instanceId, depotLocation, distribution);

		for (Id<Link> locationLinkId: receiverLocations.keySet()) {
			Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver_"+locationLinkId.toString(), Receiver.class));
			receiver.setLinkId(locationLinkId);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_GRANDCOALITION_MEMBER, true);
			receiver.getAttributes().putAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
			// Add affiliated carrier ID as an attribute
			receiver.getAttributes().putAttribute("affiliatedCarrierId", Id.create("carrier_" + receiverLocations.get(locationLinkId), Carrier.class));
			receivers.addReceiver(receiver);
		}



//		// Create a map <keys: carrierId (values in receiverLocations), values: Carrier object>
//		Map<String, Carrier> carrierMap = new HashMap<>();
//		Set<String> receiverLinkedCarrierIds = new HashSet<>(receiverLocations.values());
//		Iterator<String> receiverLinkedCarrierId = receiverLinkedCarrierIds.iterator();
//		for (Carrier carrier: carriers.getCarriers().values()){
//			carrierMap.put(receiverLinkedCarrierId.next(), carrier);
//		}

		// Generate orders for each receiver from its affiliated carrier
		for (Receiver receiver: receivers.getReceivers().values()) {
			generateReceiverOrders(carriers.getCarriers().get(receiver.getAttributes().getAttribute("affiliatedCarrierId")),
				receivers, receiver);
		}

		return receivers;
	}

	/**
	 * Read csv.gz file containing generated receiver locations and affiliated carriers
	 */
	private static Map<Id<Link>, String> readReceiverLocation (int instanceId,
															   DepotLocation depotLocation,
															   ReceiverSpatialDistribution distribution){
		final String filePath;
		if (distribution == ReceiverSpatialDistribution.CLUSTERED && depotLocation == DepotLocation.INSIDE) {
			filePath = "data/randomDemand60Receivers/inside_clustered/demand.csv.gz";
		} else if (distribution == ReceiverSpatialDistribution.CLUSTERED && depotLocation == DepotLocation.OUTSIDE) {
			filePath = "data/randomDemand60Receivers/outside_clustered/demand.csv.gz";
		} else if (distribution == ReceiverSpatialDistribution.DISPERSED && depotLocation == DepotLocation.INSIDE) {
			filePath = "data/randomDemand60Receivers/inside_dispersed/demand.csv.gz";
		} else if (distribution == ReceiverSpatialDistribution.DISPERSED && depotLocation == DepotLocation.OUTSIDE) {
			filePath = "data/randomDemand60Receivers/outside_dispersed/demand.csv.gz";
//			filePath = "data/randomDemand100Receivers/dispersed/location_i%02d.csv.gz".formatted(instanceId);
		} else {
			throw new IllegalArgumentException("Unsupported receiver spatial distribution: " + distribution);
		}

		// csv.gz: place_id, matched_link_id, depot_linkId
		// return: key=matched_link_id, value=depot_linkId (as String - carrier ID)
		Map<Id<Link>, String> linkId2CarrierId = new HashMap<>();
		Path path = Path.of(filePath);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (header == null) {
				throw new IllegalStateException("Empty receiver location file: " + filePath);
			}

			String line;
			int lineNo = 1;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				if (line.isBlank()) continue;

				String[] tokens = line.split(",");
				if (tokens.length < 3) {
					throw new IllegalArgumentException("Invalid csv line (expected 3 columns) at " + filePath + ":" + lineNo + " -> " + line);
				}

				String carrierId = tokens[2].trim();
				String matchedLinkIdStr = tokens[1].trim();
				Id<Link> linkId = Id.createLinkId(matchedLinkIdStr);

				String previous = linkId2CarrierId.put(linkId, carrierId);
				if (previous != null && !previous.equals(carrierId)) {
					logger.warn("Duplicate matched_link_id {} mapped to different carrier_id ({} -> {}). Keeping the latest.", linkId, previous, carrierId);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read receiver location file: " + filePath, e);
		}

		return linkId2CarrierId;
	}

	private static void generateReceiverOrders(Carrier carrier,
											   Receivers receivers,
											   Receiver receiver) {

		ProductType productType = ReceiverUtils.createAndGetProductType(receivers, Id.create("productType1", ProductType.class),
			carrier.getCarrierCapabilities().getCarrierVehicles().values().iterator().next().getLinkId());
		productType.setRequiredCapacity(5);

		ReceiverProduct receiverProduct = ReceiverProduct.Builder.newInstance()
			.setProductType(productType)
			.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(100, 200))
			.build();
		receiver.addProduct(receiverProduct);

		Collection<Order> orders = new ArrayList<>();
		Order order = Order.Builder.newInstance(Id.create("Order"+receiver.getId().toString(), Order.class), receiver, receiverProduct)
			.setServiceTime(15*60)
			.buildWithCalculatedOrderQuantity();
		orders.add(order);

		ReceiverOrder receiverOrder = new ReceiverOrder(receiver.getId(), orders, carrier.getId());

		ReceiverPlan receiverPlan = ReceiverPlan.Builder.newInstance(receiver, true)
			.addReceiverOrder(receiverOrder)
			.addTimeWindow(TimeWindow.newInstance(6*60*60, 8*60*60))
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


}
