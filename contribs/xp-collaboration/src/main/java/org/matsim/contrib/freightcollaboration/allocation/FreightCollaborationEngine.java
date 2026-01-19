package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.config.Config;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.*;

public class FreightCollaborationEngine {

	private final Scenario scenario;

	private final Config config;

	private final FreightCollaborators freightCollaborators;

	// Set it as package-private to be accessible from scorers and allocation models
	final CollaborationDataStore collaborationDataStore;

	private final List<MutableFreightCoalition> existingCoalitions;

	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborationEngine.class);

	private CarrierScoringFunctionFactory carrierScoringFunctionFactory;

	// FIXME: I do not see any reason to have this field if it is not used
//	private final EventsManager existingEventsManager;

	private final TravelTime travelTime;

	public FreightCollaborationEngine(Config config, Scenario scenario, FreightCollaborators freightCollaborators,
			CollaborationDataStore collaborationDataStore, List<MutableFreightCoalition> existingCoalitions, TravelTime travelTime,
									  CarrierScoringFunctionFactory carrierScoringFunctionFactory) {
		this.config = config;
		this.scenario = scenario;
		this.freightCollaborators = freightCollaborators;
		this.collaborationDataStore = collaborationDataStore;
		this.existingCoalitions = existingCoalitions;
		this.travelTime = travelTime;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
	}


	public void runCollaboration() {
		// Run the freight collaboration engine logic here
		// This may include:
		// - Forming coalitions based on the collaboration type
		// - Allocating tasks or resources among collaborators
		// - Updating plans and contributions in the CollaborationDataStore
		// - Interacting with the existing coalition and events manager as needed

		// Example pseudocode:
		// if (collaborationType == CollaborationType.SOME_TYPE) {
		//     // Perform specific logic for this collaboration type
		// }
		FreightCollaborationConfigGroup fcg = (FreightCollaborationConfigGroup) config.getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		int parallelism = Math.max(1, fcg.getParallelism());
		if (existingCoalitions.isEmpty()) {
			LOGGER.info("No valid coalitions found, skipping the allocation process.");
			return;
		}

		Supplier<FreightPseudoSimulator> pseudoSimulatorSupplier = () -> {
			FreightPseudoSimulator simulator = new FreightPseudoSimulator(collaborationDataStore, scenario.getNetwork(),
				freightCollaborators, travelTime, carrierScoringFunctionFactory);
			simulator.setVrpMaxIterations(fcg.getVrpMaxIterations());
			return simulator;
		};

		ExecutorService executor = Executors.newFixedThreadPool(parallelism,
			r -> {
				Thread t = new Thread(r);
				t.setName("freight-collab-" + t.getId());
				t.setDaemon(true);
				return t;
			});

		AllocationModel allocationModel = AllocationUtils.createAllocationModel(fcg.ALLOCATION_MODEL, collaborationDataStore,
			pseudoSimulatorSupplier, existingCoalitions, fcg.getAllocationFactor(), executor, parallelism);
		if (allocationModel instanceof AllocationModelApproxShapleyValue approx) {
			approx.setApproximationMethod(AllocationModelApproxShapleyValue.ApproximationMethod
				.valueOf(fcg.getApproxShapleyMethod()));
			approx.setMonteCarloSamples(fcg.getMonteCarloSamples());
			approx.setSamplesRatio(fcg.getSamplesRatio());
			approx.setStratifiedSamplesPerLevel(fcg.getStratifiedSamplesPerLevel());
			approx.setMaxStratifiedEvaluations(fcg.getMaxStratifiedEvaluations());
		}

		try {
			if (fcg.ALLOCATION_MODEL == AllocationModels.APPROX_SHAPLEY) {
				allocationModel.allocate(fcg.getAllocationStrategy());
				return;
			}

			// Evaluate coalitions in parallel before passing scores to allocation models
			Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> coalitionScores = new ConcurrentHashMap<>();
			List<Callable<Void>> tasks = existingCoalitions.stream()
				.<Callable<Void>>map(coalition -> () -> {
					FreightPseudoSimulator freightPsim = pseudoSimulatorSupplier.get();
					// Extract the valid collaborators (player and distributor) based on the collaboration type
					Map<Id<?>, FreightCollaborator<?>> validPlayer = AllocationUtils.extractValidPlayers(coalition);
					Map<Id<?>, FreightCollaborator<?>> validDistributor = AllocationUtils.extractValidDistributors(coalition);
					Map<Set<Id<?>>, Double> subCoalitionsScoreMap;
					if (fcg.ALLOCATION_MODEL == AllocationModels.PROPORTIONAL) {
						subCoalitionsScoreMap = freightPsim.runSubCoalitions(validDistributor, validPlayer,
							buildSubCoalitionsForProportional(validPlayer.keySet()));
					} else if (fcg.ALLOCATION_MODEL == AllocationModels.MARGINAL) {
						subCoalitionsScoreMap = freightPsim.runSubCoalitions(validDistributor, validPlayer,
							buildSubCoalitionsForMarginal(validPlayer.keySet()));
					} else {
						subCoalitionsScoreMap = freightPsim.runAllSubCoalitions(validDistributor, validPlayer);
					}
					coalitionScores.put(coalition, subCoalitionsScoreMap);
					return null;
				})
				.toList();

			for (Future<Void> future : executor.invokeAll(tasks)) {
				future.get();
			}

			coalitionScores.forEach(collaborationDataStore::addSimulatedCoalitionScores);
			allocationModel.allocate(fcg.getAllocationStrategy());

		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Freight collaboration was interrupted", ie);
		} catch (Exception e) {
			throw new RuntimeException("Error during freight collaboration execution", e);
		} finally {
			executor.shutdown();
		}

		// Something to do with triggering the MATSim scoring module
		/**
		 * Here, we may need to add a custom scoring function to each collaborator agent, by implementing the @BasicScoring,
		 * since it will definitely be called at the end the MATSim scoring phase.
		 *
		 * It seems we cannot directly inject the scoring function here, so that we have two options:
		 * 1. Create a custom ScoringFunctionFactory that creates a SumScoringFunction with our custom scoring function included at the start of the simulation
		 * 2. Modify the scores after the scoring phase using the iteration ends listener.
		 * Now, I temporarily choose the second option for simplicity.
		 */

	}

	/**
	 * Build sub-coalitions for proportional allocation model,
	 * it should be the full coalition and empty coalition (to get the baseline score).
	 * @param players
	 * @return
	 */
	private Collection<Set<Id<?>>> buildSubCoalitionsForProportional(Set<Id<?>> players) {
		List<Set<Id<?>>> subCoalitions = new ArrayList<>();
		subCoalitions.add(Set.of());
		subCoalitions.add(Set.copyOf(players));
//		for (Id<?> player : players) {
//			subCoalitions.add(Set.of(player));
//		}
		return subCoalitions;
	}

	private Collection<Set<Id<?>>> buildSubCoalitionsForMarginal(Set<Id<?>> players) {
		List<Set<Id<?>>> subCoalitions = new ArrayList<>();
		subCoalitions.add(Set.of());
		Set<Id<?>> fullCoalition = Set.copyOf(players);
		subCoalitions.add(fullCoalition);
		for (Id<?> player : players) {
			Set<Id<?>> withoutPlayer = new HashSet<>(players);
			withoutPlayer.remove(player);
			subCoalitions.add(withoutPlayer);
		}
		return subCoalitions;
	}

	private void injectScoringFunctionForValueAllocation(){

	}


}
