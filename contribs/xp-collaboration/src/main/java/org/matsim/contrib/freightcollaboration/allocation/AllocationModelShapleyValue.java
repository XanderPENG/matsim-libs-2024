package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;

import java.util.*;

public class AllocationModelShapleyValue implements AllocationModel {

//	@Inject
	CollaborationDataStore collaborationDataStore;

	private static final Logger logger = LogManager.getLogger(AllocationModelShapleyValue.class);
	private final double allocationFactor;

	public AllocationModelShapleyValue(CollaborationDataStore collaborationDataStore, double allocationFactor) {
		this.collaborationDataStore = Objects.requireNonNull(collaborationDataStore, "collaborationDataStore");
		if (!Double.isFinite(allocationFactor) || allocationFactor < 0.0 || allocationFactor > 1.0) {
			throw new IllegalArgumentException("allocationFactor must be in [0, 1].");
		}
		this.allocationFactor = allocationFactor;
	}

	@Override
	public void allocate(AllocationValueTypes type) {
		switch (type) {
			case COST_SAVINGS -> allocateCostSavings();
			case COST -> allocateCost();
			default -> throw new UnsupportedOperationException("Unsupported allocation type: " + type);
		}
	}

	public void allocateCostSavings() {
		// Maintain a map to store final allocation values for each collaborator
		Map<CollaboratorKey, Double> finalAllocations = new HashMap<>();
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores =
			requireSimulatedScores();

		// For-loop over all mutable coalitions
		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			// in this map key: subcoalition, but without the distributor ID (carrier) but only the players
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();

			// Get the distributor (carrier) ID by collaboration types
			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();

			// Calculate the cost savings for each subcoalition in  this coalition
			double nonCollaborativeCost = requireScore(coalitionScores, Set.of());
			Map<Set<Id<?>>, Double> costSavings = new HashMap<>();
			for (Map.Entry<Set<Id<?>>, Double> scoreEntry : coalitionScores.entrySet()) {
				Set<Id<?>> subCoalition = scoreEntry.getKey();
				double collaborativeCost = scoreEntry.getValue();
				double savings = collaborativeCost - nonCollaborativeCost;
				costSavings.put(subCoalition, savings);
			}

			// Calculate Shapley values of cost savings for this coalition
			Map<Id<?>, Double> shapleyValues = calculateShapleyValues(costSavings);
			double totalShapleyValue = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
			// reserve certain savings by the distributor based on the allocation factor and total cost savings
			double reservedCostSavings = totalShapleyValue * (1 - allocationFactor);

			// record the allocations
			for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
				CollaboratorKey collaboratorKey = AllocationUtils.playerKey(coalition, shapleyEntry.getKey());
				double allocation = shapleyEntry.getValue();
				finalAllocations.merge(collaboratorKey, allocationFactor * allocation, Double::sum);
			}
			// Add the reserved savings to the distributor
			finalAllocations.merge(distributorKey, reservedCostSavings, Double::sum);
		}

		// Update the allocated values in the data store
		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private void allocateCost() {
		Map<CollaboratorKey, Double> finalAllocations = new HashMap<>();

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : requireSimulatedScores().entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();

			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();

			Map<Id<?>, Double> shapleyValues = calculateShapleyValues(coalitionScores);
			double totalCostShare = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
			double reservedShare = totalCostShare * (1 - allocationFactor);

			for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
				CollaboratorKey collaboratorKey = AllocationUtils.playerKey(coalition, shapleyEntry.getKey());
				double allocation = shapleyEntry.getValue();
				finalAllocations.merge(collaboratorKey, allocationFactor * allocation, Double::sum);
			}

			finalAllocations.merge(distributorKey, reservedShare, Double::sum);
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	Map<Id<?>, Double> calculateShapleyValues(Map<Set<Id<?>>, Double> coalitionScores) {
		Objects.requireNonNull(coalitionScores, "coalitionScores");

		// Extract all unique collaborator IDs from coalition scores
		Set<Id<?>> allCollaborators = new LinkedHashSet<>();
		for (Set<Id<?>> coalition : coalitionScores.keySet()) {
			Objects.requireNonNull(coalition, "coalition key");
			allCollaborators.addAll(coalition);
		}

		int n = allCollaborators.size();
		if (n >= Integer.SIZE - 1) {
			throw new IllegalArgumentException("Too many players for exact Shapley allocation: " + n);
		}
		int expectedCoalitions = 1 << n;
		if (coalitionScores.size() != expectedCoalitions) {
			throw new IllegalArgumentException("Incomplete characteristic function: expected "
				+ expectedCoalitions + " sub-coalitions for " + n + " players, found "
				+ coalitionScores.size());
		}
		requireScore(coalitionScores, Set.of());
		Map<Id<?>, Double> shapleyValues = new LinkedHashMap<>();

		// Calculate Shapley value for each collaborator
		for (Id<?> collaborator : allCollaborators) {
			double shapleyValue = 0.0;

			for (Set<Id<?>> coalitionWithout : coalitionScores.keySet()) {
				if (coalitionWithout.contains(collaborator)) {
					continue;
				}
				Set<Id<?>> coalitionWith = new HashSet<>(coalitionWithout);
				coalitionWith.add(collaborator);
				double valueWith = requireScore(coalitionScores, coalitionWith);
				double valueWithout = requireScore(coalitionScores, coalitionWithout);
				double weight = 1.0 / (n * binomial(n - 1, coalitionWithout.size()));
				shapleyValue += weight * (valueWith - valueWithout);
			}

			shapleyValues.put(collaborator, shapleyValue);
		}

		return shapleyValues;
	}

	private double binomial(int n, int k) {
		if (k < 0 || k > n) {
			return 0.0;
		}
		k = Math.min(k, n - k);
		double result = 1.0;
		for (int i = 1; i <= k; i++) {
			result *= (double) (n - k + i) / i;
		}
		return result;
	}

	private Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> requireSimulatedScores() {
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> scores =
			collaborationDataStore.getSimulatedCoalitionScores();
		if (scores == null || scores.isEmpty()) {
			logger.warn("No simulated coalition scores available for Shapley allocation.");
			return Map.of();
		}
		return scores;
	}

	private static double requireScore(Map<Set<Id<?>>, Double> scores, Set<Id<?>> coalition) {
		Double value = scores.get(coalition);
		if (value == null) {
			throw new IllegalArgumentException("Missing score for sub-coalition " + coalition);
		}
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("Non-finite score for sub-coalition " + coalition + ": " + value);
		}
		return value;
	}
}
