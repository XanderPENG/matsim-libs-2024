package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.matsim.contrib.freightcollaboration.run.RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig;

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

	}

	private static void runSingleLeuvenScenario(int instanceId, String tag,
												ReceiverSpatialDistribution distribution, DepotLocation depotLocation,
												AllocationMethod allocationMethod,
												double allocationFactor, double receiverCollaborationPenalty)  {
		String runId = "%s-%s-%s-%s-af%.2f-p%.4f-i%02d".formatted(tag, depotLocation.label, distribution.name().toLowerCase(),
				allocationMethod.label, allocationFactor, receiverCollaborationPenalty, instanceId);
		Config config = createConfig(runId);

		// Get the output directory and check if it already exists
		String outputDir = config.controller().getOutputDirectory();
		// If it exists, skip this experiment
		if (new File(outputDir).exists()) {
			logger.warn("Output directory {} already exists. Skipping this experiment.", outputDir);
			return;
		} else {
			logger.info("Running experiment with runId: {}", runId);
		}

		// Create freight collaboration config group and set parameters
		FreightCollaborationConfigGroup freightCollaborationConfigGroup = createExampleFreightCollaborationConfig();
	}

	private static Config createConfig(String runId) {
		Config config = ConfigUtils.createConfig();
		config.network().setInputFile("data/GemeenteLeuvenWithHbefaType/GemeenteLeuvenWithHbefaType.xml.gz");
		config.controller().setOutputDirectory("output/leuvenCarrierReceiverCollab/" + runId + "/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(50);
		// set writing output every 5 iterations
		config.controller().setWriteEventsInterval(10);
		config.controller().setWritePlansInterval(10);
		config.global().setNumberOfThreads(4);
		return config;
	}


}
