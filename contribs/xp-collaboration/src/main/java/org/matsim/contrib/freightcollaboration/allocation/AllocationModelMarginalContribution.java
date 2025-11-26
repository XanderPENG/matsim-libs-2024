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

public class AllocationModelMarginalContribution implements AllocationModel {

	private static final Logger logger = LogManager.getLogger(AllocationModelMarginalContribution.class);

	private final CollaborationDataStore collaborationDataStore;
	private final double allocationFactor = 0.9;

	public AllocationModelMarginalContribution(CollaborationDataStore collaborationDataStore) {
		this.collaborationDataStore = collaborationDataStore;
	}

	@Override
	public void allocate(AllocationValueTypes type) {
		if (type != AllocationValueTypes.COST_SAVINGS) {
			throw new UnsupportedOperationException("MarginalContribution allocation currently supports only COST_SAVINGS.");
		}
		allocateCostSavings();
	}

	private void allocateCostSavings() {
		Map<Id<?>, Double> finalAllocations = new HashMap<>();
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores = collaborationDataStore.getSimulatedCoalitionScores();
		if (simulatedScores == null || simulatedScores.isEmpty()) {
			logger.warn("No simulated coalition scores available for marginal contribution allocation.");
			return;
		}

		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : simulatedScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();
			Id<?> distributorId = extractDistributorId(coalition);
			Set<Id<?>> allPlayers = extractAllPlayers(coalitionScores);
			if (allPlayers.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping.", coalition);
				continue;
			}

			Set<Id<?>> fullCoalition = new HashSet<>(allPlayers);
			double baseline = coalitionScores.get(Set.of());
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
		throw new IllegalStateException("Unsupported collaboration type for marginal contribution allocation: " + coalition.getCollaborationType());
	}
}
