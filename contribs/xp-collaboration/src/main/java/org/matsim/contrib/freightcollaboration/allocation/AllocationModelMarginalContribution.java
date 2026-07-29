package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AllocationModelMarginalContribution implements AllocationModel {

	private static final Logger logger = LogManager.getLogger(AllocationModelMarginalContribution.class);

	private final CollaborationDataStore collaborationDataStore;
	private final double allocationFactor;

	public AllocationModelMarginalContribution(CollaborationDataStore collaborationDataStore, double allocationFactor) {
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

	private void allocateCostSavings() {
		Map<CollaboratorKey, Double> finalAllocations = new HashMap<>();
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores = collaborationDataStore.getSimulatedCoalitionScores();
		if (simulatedScores == null || simulatedScores.isEmpty()) {
			logger.warn("No simulated coalition scores available for marginal contribution allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();
			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping.", coalition);
				continue;
				}

				Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
				Double baselineValue = coalitionScores.get(Set.of());
				if (baselineValue == null) {
					throw new IllegalArgumentException(
						"Missing empty-coalition baseline for " + coalition);
				}
				double baseline = baselineValue;
			Double fullScore = coalitionScores.get(fullCoalition);
			if (fullScore == null) {
				logger.warn("No full coalition score available for coalition {}, skipping marginal allocation.", coalition);
				continue;
			}
			double totalSavings = fullScore - baseline;
			if (totalSavings < 0) {
				logger.warn("Collaborative cost savings should not be negative, resetting to zero. current={}", totalSavings);
				totalSavings = 0.0;
			}

			Map<Id<?>, Double> marginalContributions = new HashMap<>();
			for (Id<?> player : allPlayers) {
				Set<Id<?>> withoutPlayer = new HashSet<>(fullCoalition);
				withoutPlayer.remove(player);
				double scoreWithout = coalitionScores.getOrDefault(withoutPlayer, fullScore);
				double marginal = fullScore - scoreWithout;
				if (marginal < 0) {
					logger.warn("Marginal contribution for {} is negative ({}), clipping to zero.", player, marginal);
					marginal = 0.0;
				}
				marginalContributions.put(player, marginal);
			}

			double marginalSum = marginalContributions.values().stream().mapToDouble(Double::doubleValue).sum();
			double playerBudget = totalSavings * allocationFactor;
			double fallbackShare = playerBudget / allPlayers.size();

			for (Map.Entry<Id<?>, Double> marginalEntry : marginalContributions.entrySet()) {
				Id<?> playerId = marginalEntry.getKey();
				double marginal = marginalEntry.getValue();
				double allocation = marginalSum > 0 ? playerBudget * marginal / marginalSum : fallbackShare;
				finalAllocations.merge(AllocationUtils.playerKey(coalition, playerId), allocation, Double::sum);
			}

			double reservedSavings = totalSavings * (1 - allocationFactor);
			finalAllocations.merge(distributorKey, reservedSavings, Double::sum);
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private void allocateCost() {
		Map<CollaboratorKey, Double> finalAllocations = new HashMap<>();
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores = collaborationDataStore.getSimulatedCoalitionScores();
		if (simulatedScores == null || simulatedScores.isEmpty()) {
			logger.warn("No simulated coalition scores available for marginal contribution allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();
			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping.", coalition);
				continue;
			}

			Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
			Double fullCost = coalitionScores.get(fullCoalition);
			if (fullCost == null) {
				logger.warn("No full coalition cost available for coalition {}, skipping marginal allocation.", coalition);
				continue;
			}

			Map<Id<?>, Double> marginalContributions = new HashMap<>();
			for (Id<?> player : allPlayers) {
				Set<Id<?>> withoutPlayer = new HashSet<>(fullCoalition);
				withoutPlayer.remove(player);
				double costWithout = coalitionScores.getOrDefault(withoutPlayer, fullCost);
				double marginal = fullCost - costWithout;
				marginal = Math.max(0.0, marginal);
				marginalContributions.put(player, marginal);
			}

			double marginalSum = marginalContributions.values().stream().mapToDouble(Double::doubleValue).sum();
			double playerBudget = fullCost * allocationFactor;
			double fallbackShare = playerBudget / allPlayers.size();

			for (Map.Entry<Id<?>, Double> marginalEntry : marginalContributions.entrySet()) {
				Id<?> playerId = marginalEntry.getKey();
				double marginal = marginalEntry.getValue();
				double allocation = marginalSum > 0 ? playerBudget * marginal / marginalSum : fallbackShare;
				finalAllocations.merge(AllocationUtils.playerKey(coalition, playerId), allocation, Double::sum);
			}

			double reservedShare = fullCost * (1 - allocationFactor);
			finalAllocations.merge(distributorKey, reservedShare, Double::sum);
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private Set<Id<?>> extractAllPlayers(Map<Set<Id<?>>, Double> coalitionScores) {
		Set<Id<?>> allCollaborators = new HashSet<>();
		for (Set<Id<?>> coalition : coalitionScores.keySet()) {
			allCollaborators.addAll(coalition);
		}
		return allCollaborators;
	}

}
