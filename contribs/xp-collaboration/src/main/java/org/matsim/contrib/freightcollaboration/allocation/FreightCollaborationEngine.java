package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.config.Config;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.function.IntFunction;
import java.util.*;

public class FreightCollaborationEngine {

	private final Scenario scenario;

	private final Config config;

	private final FreightCollaborators freightCollaborators;

	// Set it as package-private to be accessible from scorers and allocation models
	final CollaborationDataStore collaborationDataStore;

	private final List<MutableFreightCoalition> existingCoalitions;

	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborationEngine.class);

	private final CarrierScoringFunctionFactory carrierScoringFunctionFactory;

	// FIXME: I do not see any reason to have this field if it is not used
//	private final EventsManager existingEventsManager;

	private final TravelTime travelTime;
	private final Supplier<FreightPseudoSimulator> pseudoSimulatorOverride;
	private final IntFunction<ExecutorService> executorFactory;

	public FreightCollaborationEngine(Config config, Scenario scenario, FreightCollaborators freightCollaborators,
			CollaborationDataStore collaborationDataStore, List<MutableFreightCoalition> existingCoalitions, TravelTime travelTime,
									  CarrierScoringFunctionFactory carrierScoringFunctionFactory) {
		this(config, scenario, freightCollaborators, collaborationDataStore, existingCoalitions, travelTime,
			carrierScoringFunctionFactory, null, parallelism -> Executors.newFixedThreadPool(parallelism,
				r -> {
					Thread t = new Thread(r);
					t.setName("freight-collab-" + t.getId());
					t.setDaemon(true);
					return t;
				}));
	}

	FreightCollaborationEngine(Config config, Scenario scenario, FreightCollaborators freightCollaborators,
			CollaborationDataStore collaborationDataStore, List<MutableFreightCoalition> existingCoalitions,
			TravelTime travelTime, CarrierScoringFunctionFactory carrierScoringFunctionFactory,
			Supplier<FreightPseudoSimulator> pseudoSimulatorOverride,
			IntFunction<ExecutorService> executorFactory) {
		this.config = config;
		this.scenario = scenario;
		this.freightCollaborators = freightCollaborators;
		this.collaborationDataStore = collaborationDataStore;
		this.existingCoalitions = existingCoalitions == null ? List.of() : List.copyOf(existingCoalitions);
		this.travelTime = travelTime;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
		this.pseudoSimulatorOverride = pseudoSimulatorOverride;
		this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
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

		Supplier<FreightPseudoSimulator> pseudoSimulatorSupplier = pseudoSimulatorOverride != null
			? pseudoSimulatorOverride
			: () -> {
			FreightPseudoSimulator simulator = new FreightPseudoSimulator(collaborationDataStore, scenario.getNetwork(),
				freightCollaborators, travelTime, carrierScoringFunctionFactory, fcg);
			simulator.setVrpMaxIterations(fcg.getVrpMaxIterations());
			return simulator;
		};

		ExecutorService executor = Objects.requireNonNull(executorFactory.apply(parallelism),
			"executorFactory returned null");
		try {
			AllocationModel allocationModel = createAllocationModel(fcg, pseudoSimulatorSupplier, executor, parallelism);
			if (allocationModel instanceof AllocationModelApproxShapleyValue approx) {
				approx.setApproximationMethod(AllocationModelApproxShapleyValue.ApproximationMethod
					.valueOf(fcg.getApproxShapleyMethod()));
				approx.setMonteCarloSamples(fcg.getMonteCarloSamples());
				approx.setSamplesRatio(fcg.getSamplesRatio());
				approx.setStratifiedSamplesPerLevel(fcg.getStratifiedSamplesPerLevel());
				approx.setMaxStratifiedEvaluations(fcg.getMaxStratifiedEvaluations());
			}

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
			executor.shutdownNow();
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

	private AllocationModel createAllocationModel(FreightCollaborationConfigGroup fcg,
			Supplier<FreightPseudoSimulator> pseudoSimulatorSupplier,
			ExecutorService executor, int parallelism) {
		Object mutableModule = config.getModules().get(MutableAllocationFactorConfigGroup.GROUP_NAME);
		if (!(mutableModule instanceof MutableAllocationFactorConfigGroup mutableConfig)) {
			// Deliberately retain the legacy fixed-double path when the opt-in module is absent.
			return AllocationUtils.createAllocationModel(fcg.ALLOCATION_MODEL,
				collaborationDataStore, pseudoSimulatorSupplier, existingCoalitions,
				fcg.getAllocationFactor(), executor, parallelism);
		}

		mutableConfig.validateGrid();
		if (fcg.getAllocationStrategy() != AllocationValueTypes.COST_SAVINGS) {
			throw new IllegalArgumentException(
				"Mutable carrier allocation factors currently require COST_SAVINGS allocation.");
		}
		CoalitionAllocationFactorResolver resolver = coalition -> {
			if (coalition.getCollaborationType() != CollaborationTypes.CARRIER_RECEIVER) {
				return fcg.getAllocationFactor();
			}
			var distributor = AllocationUtils.extractSingleDistributor(coalition);
			if (!(distributor.getDelegate() instanceof Carrier carrier)) {
				throw new IllegalStateException("CARRIER_RECEIVER coalition distributor is not a Carrier: "
					+ distributor.getId());
			}
			CarrierPlan selectedPlan = carrier.getSelectedPlan();
			if (selectedPlan == null) {
				throw new IllegalStateException("Carrier has no selected plan: " + carrier.getId());
			}
			return CarrierAllocationFactor.require(selectedPlan, mutableConfig);
		};
		// Reject malformed carrier coalitions before starting any costly pseudo-simulation work.
		for (MutableFreightCoalition coalition : existingCoalitions) {
			if (coalition.getCollaborationType()
				== CollaborationTypes.CARRIER_RECEIVER) {
				CoalitionAllocationFactorResolver.requireValid(resolver, coalition);
			}
		}
		return AllocationUtils.createAllocationModel(fcg.ALLOCATION_MODEL,
			collaborationDataStore, pseudoSimulatorSupplier, existingCoalitions,
			resolver, executor, parallelism);
	}

	/**
	 * Build sub-coalitions for proportional allocation model,
	 * it should be the full coalition and empty coalition (to get the baseline score).
	 * @param players
	 * @return
	 */
	static Collection<Set<Id<?>>> buildSubCoalitionsForProportional(Set<Id<?>> players) {
		Objects.requireNonNull(players, "players");
		Set<Set<Id<?>>> subCoalitions = new LinkedHashSet<>();
		subCoalitions.add(Set.of());
		for (Id<?> player : players) {
			subCoalitions.add(Set.of(player));
		}
		subCoalitions.add(Set.copyOf(players));
		return List.copyOf(subCoalitions);
	}

	static Collection<Set<Id<?>>> buildSubCoalitionsForMarginal(Set<Id<?>> players) {
		Objects.requireNonNull(players, "players");
		Set<Set<Id<?>>> subCoalitions = new LinkedHashSet<>();
		subCoalitions.add(Set.of());
		Set<Id<?>> fullCoalition = Set.copyOf(players);
		subCoalitions.add(fullCoalition);
		for (Id<?> player : players) {
			Set<Id<?>> withoutPlayer = new HashSet<>(players);
			withoutPlayer.remove(player);
			subCoalitions.add(withoutPlayer);
		}
		return List.copyOf(subCoalitions);
	}


}
