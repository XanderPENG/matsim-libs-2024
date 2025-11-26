package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AllocationModelProportional implements AllocationModel {

	private static final Logger logger = LogManager.getLogger(AllocationModelProportional.class);
	private final CollaborationDataStore collaborationDataStore;
	private final double allocationFactor = 0.9;

	public AllocationModelProportional(CollaborationDataStore collaborationDataStore) {
		this.collaborationDataStore = collaborationDataStore;
	}

	@Override
	public void allocate(AllocationValueTypes type) {
		if (type != AllocationValueTypes.COST_SAVINGS) {
			throw new UnsupportedOperationException("Proportional allocation currently supports only COST_SAVINGS.");
		}
		allocateCostSavings();
	}

	private void allocateCostSavings() {
		Map<Id<?>, Double> finalAllocations = new HashMap<>();
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores = collaborationDataStore.getSimulatedCoalitionScores();
		if (simulatedScores == null || simulatedScores.isEmpty()) {
			logger.warn("No simulated coalition scores available for proportional allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			Id<?> distributorId = extractDistributorId(coalition);

			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping proportional allocation.", coalition);
				continue;
			}

			Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
			double baseline = coalitionScores.get(Set.of());
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
				finalAllocations.put(playerId, finalAllocations.getOrDefault(playerId, 0.0) + allocation);
			}

			double reservedSavings = totalSavings * (1 - allocationFactor);
			finalAllocations.put(distributorId, finalAllocations.getOrDefault(distributorId, 0.0) + reservedSavings);
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

	private Id<?> extractDistributorId(MutableFreightCoalition coalition) {
		if (coalition.getCollaborationType() == CollaborationTypes.CARRIER_RECEIVER) {
			return coalition.getCollaboratorsSetByRole(CollaboratorRole.CARRIER).iterator().next().getId();
		}
		throw new IllegalStateException("Unsupported collaboration type for proportional allocation: " + coalition.getCollaborationType());
	}
}
