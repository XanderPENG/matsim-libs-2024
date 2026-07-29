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

public class AllocationModelProportional implements AllocationModel {

	private static final Logger logger = LogManager.getLogger(AllocationModelProportional.class);
	private final CollaborationDataStore collaborationDataStore;
	private final double allocationFactor;

	public AllocationModelProportional(CollaborationDataStore collaborationDataStore, double allocationFactor) {
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
			logger.warn("No simulated coalition scores available for proportional allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();

			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping proportional allocation.", coalition);
				continue;
				}

				Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
				Double baselineValue = coalitionScores.get(Set.of());
				if (baselineValue == null) {
					throw new IllegalArgumentException(
						"Missing empty-coalition baseline for " + coalition);
				}
				double baseline = baselineValue;
			Double collaborativeScore = coalitionScores.get(fullCoalition);
			if (collaborativeScore == null) {
				logger.warn("No collaborative score found for coalition {}, skipping.", coalition);
				continue;
			}
			double totalSavings = collaborativeScore - baseline;
			if (totalSavings < 0) {
				logger.warn("Collaborative cost savings should not be negative, resetting to zero. current={}", totalSavings);
				totalSavings = 0.0;
			}

			Map<Id<?>, Double> proportionalWeights = new HashMap<>();
			for (Id<?> player : allPlayers) {
				Set<Id<?>> singleton = Set.of(player);
				double standaloneScore = coalitionScores.getOrDefault(singleton, baseline);
				double weight = standaloneScore - baseline;
				if (weight < 0) {
					logger.warn("Standalone savings for {} is negative ({}), clipping to zero.", player, weight);
					weight = 0.0;
				}
				proportionalWeights.put(player, weight);
			}

			double weightSum = proportionalWeights.values().stream().mapToDouble(Double::doubleValue).sum();
			double playerBudget = totalSavings * allocationFactor;
			double fallbackShare = playerBudget / allPlayers.size();
			for (Map.Entry<Id<?>, Double> weightEntry : proportionalWeights.entrySet()) {
				Id<?> playerId = weightEntry.getKey();
				double weight = weightEntry.getValue();
				double allocation = weightSum > 0 ? playerBudget * weight / weightSum : fallbackShare;
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
			logger.warn("No simulated coalition scores available for proportional allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			CollaboratorKey distributorKey = AllocationUtils.extractSingleDistributor(coalition).getKey();

			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping proportional allocation.", coalition);
				continue;
			}

			Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
			Double collaborativeCost = coalitionScores.get(fullCoalition);
			if (collaborativeCost == null) {
				logger.warn("No collaborative cost found for coalition {}, skipping.", coalition);
				continue;
			}

			Map<Id<?>, Double> proportionalWeights = new HashMap<>();
			for (Id<?> player : allPlayers) {
				Set<Id<?>> singleton = Set.of(player);
				double standaloneCost = coalitionScores.getOrDefault(singleton, collaborativeCost);
				double weight = Math.max(0.0, standaloneCost);
				proportionalWeights.put(player, weight);
			}

			double weightSum = proportionalWeights.values().stream().mapToDouble(Double::doubleValue).sum();
			double playerBudget = collaborativeCost * allocationFactor;
			double fallbackShare = playerBudget / allPlayers.size();
			for (Map.Entry<Id<?>, Double> weightEntry : proportionalWeights.entrySet()) {
				Id<?> playerId = weightEntry.getKey();
				double weight = weightEntry.getValue();
				double allocation = weightSum > 0 ? playerBudget * weight / weightSum : fallbackShare;
				finalAllocations.merge(AllocationUtils.playerKey(coalition, playerId), allocation, Double::sum);
			}

			double reservedShare = collaborativeCost * (1 - allocationFactor);
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
