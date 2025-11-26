package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;

import java.util.*;

public class AllocationModelApproxShapleyValue implements AllocationModel {

	public enum ApproximationMethod {
		MONTE_CARLO,
		STRATIFIED
	}

	private static final Logger logger = LogManager.getLogger(AllocationModelApproxShapleyValue.class);
	private final CollaborationDataStore collaborationDataStore;
	private final FreightPseudoSimulator freightPseudoSimulator;
	private final List<MutableFreightCoalition> coalitions;
	private final Random random = new Random(1);
	private final double allocationFactor = 0.9;
	private ApproximationMethod approximationMethod = ApproximationMethod.MONTE_CARLO;
	private int monteCarloSamples = 20;
	private double SamplesRatio = 0.4;
	private int stratifiedSamplesPerLevel = 20;

	public AllocationModelApproxShapleyValue(CollaborationDataStore collaborationDataStore,
											 FreightPseudoSimulator freightPseudoSimulator,
											 List<MutableFreightCoalition> coalitions) {
		this.collaborationDataStore = collaborationDataStore;
		this.freightPseudoSimulator = freightPseudoSimulator;
		this.coalitions = coalitions;
	}

	public void setApproximationMethod(ApproximationMethod approximationMethod) {
		this.approximationMethod = approximationMethod;
	}

	public void setMonteCarloSamples(int monteCarloSamples) {
		this.monteCarloSamples = monteCarloSamples;
	}

	public void setStratifiedSamplesPerLevel(int stratifiedSamplesPerLevel) {
		this.stratifiedSamplesPerLevel = stratifiedSamplesPerLevel;
	}

	public void setRandomSeed(long seed) {
		random.setSeed(seed);
	}

	@Override
	public void allocate(AllocationValueTypes type) {
		if (type != AllocationValueTypes.COST_SAVINGS) {
			throw new UnsupportedOperationException("Approximate Shapley allocation currently supports only COST_SAVINGS.");
		}
		allocateCostSavings();
	}

	private void allocateCostSavings() {
		if (coalitions == null || coalitions.isEmpty()) {
			logger.info("No valid coalitions found, skipping approximate Shapley allocation.");
			return;
		}

		Map<Id<?>, Double> finalAllocations = new HashMap<>();

		for (MutableFreightCoalition coalition : coalitions) {
			Map<Id<?>, FreightCollaborator<?>> players = AllocationUtils.extractValidPlayers(coalition);
			Map<Id<?>, FreightCollaborator<?>> distributors = AllocationUtils.extractValidDistributors(coalition);
			if (players.isEmpty()) {
				logger.warn("Coalition {} has no collaborating players, skipping.", coalition);
				continue;
			}

			Map<Set<Id<?>>, Double> valueCache = new HashMap<>();
			Map<Id<?>, Double> shapleyValues;
			if (approximationMethod == ApproximationMethod.STRATIFIED) {
				shapleyValues = approximateShapleyStratified(players, distributors, valueCache);
			} else {
				shapleyValues = approximateShapleyMonteCarlo(players, distributors, valueCache);
			}

			double totalShapleyValue = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
			double reservedCostSavings = totalShapleyValue * (1 - allocationFactor);

			for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
				Id<?> collaboratorId = shapleyEntry.getKey();
				double allocation = shapleyEntry.getValue();
				finalAllocations.put(collaboratorId, finalAllocations.getOrDefault(collaboratorId, 0.0) + allocationFactor * allocation);
			}
			Id<?> distributorId = extractDistributorId(coalition);
			finalAllocations.put(distributorId, finalAllocations.getOrDefault(distributorId, 0.0) + reservedCostSavings);

			// Store the sampled scores for transparency
			collaborationDataStore.addSimulatedCoalitionScores(coalition, valueCache);
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private Map<Id<?>, Double> approximateShapleyMonteCarlo(Map<Id<?>, FreightCollaborator<?>> players,
											 Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Set<Id<?>>, Double> valueCache) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));

		// Ensure baseline is present in the cache
		evaluateSubCoalition(distributors, players, valueCache, Set.of());

//		int upperBoundSamples;
//		if (SamplesRatio > 0 && SamplesRatio < 1) {
//			upperBoundSamples = (int) (SamplesRatio * factorial(playerList.size()));
//			monteCarloSamples = Math.min(monteCarloSamples, upperBoundSamples);
//		}

		for (int sample = 0; sample < monteCarloSamples; sample++) {
			Collections.shuffle(playerList, random);
			Set<Id<?>> currentCoalition = new HashSet<>();
			double currentValue = evaluateSubCoalition(distributors, players, valueCache, currentCoalition);
			for (Id<?> playerId : playerList) {
				currentCoalition.add(playerId);
				double valueWith = evaluateSubCoalition(distributors, players, valueCache, currentCoalition);
				double marginalContribution = valueWith - currentValue;
				shapleyValues.put(playerId, shapleyValues.get(playerId) + marginalContribution);
				currentValue = valueWith;
			}
		}

		shapleyValues.replaceAll((id, value) -> Math.max(0.0, value / monteCarloSamples));
		return shapleyValues;
	}

	private Map<Id<?>, Double> approximateShapleyStratified(Map<Id<?>, FreightCollaborator<?>> players,
											 Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Set<Id<?>>, Double> valueCache) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));
		int n = playerList.size();

		for (Id<?> playerId : playerList) {
			double shapleyEstimate = 0.0;
			for (int k = 0; k <= n - 1; k++) {
				double weight = factorial(k) * factorial(n - k - 1) / (double) factorial(n);
				double marginalSum = 0.0;
				int samples = Math.max(1, stratifiedSamplesPerLevel);
				for (int s = 0; s < samples; s++) {
					Set<Id<?>> subset = drawRandomSubset(playerList, playerId, k);
					double valueWithout = evaluateSubCoalition(distributors, players, valueCache, subset);
					Set<Id<?>> withPlayer = new HashSet<>(subset);
					withPlayer.add(playerId);
					double valueWith = evaluateSubCoalition(distributors, players, valueCache, withPlayer);
					marginalSum += valueWith - valueWithout;
				}
				double averageMarginal = marginalSum / samples;
				shapleyEstimate += weight * averageMarginal;
			}
			shapleyValues.put(playerId, shapleyEstimate);
		}

		shapleyValues.replaceAll((id, value) -> Math.max(0.0, value));
		return shapleyValues;
	}

	private Set<Id<?>> drawRandomSubset(List<Id<?>> playerList, Id<?> excludedPlayer, int subsetSize) {
		List<Id<?>> candidates = new ArrayList<>();
		for (Id<?> id : playerList) {
			if (!id.equals(excludedPlayer)) {
				candidates.add(id);
			}
		}
		Collections.shuffle(candidates, random);
		int targetSize = Math.min(subsetSize, candidates.size());
		return new HashSet<>(candidates.subList(0, targetSize));
	}

	private double evaluateSubCoalition(Map<Id<?>, FreightCollaborator<?>> distributors,
									 Map<Id<?>, FreightCollaborator<?>> players,
									 Map<Set<Id<?>>, Double> valueCache,
									 Set<Id<?>> subCoalition) {
		Set<Id<?>> key = Set.copyOf(subCoalition);
		return valueCache.computeIfAbsent(key,
				ignored -> freightPseudoSimulator.runSingleSubCoalition(distributors, players, key));
	}

	private Id<?> extractDistributorId(MutableFreightCoalition coalition) {
		if (coalition.getCollaborationType() == CollaborationTypes.CARRIER_RECEIVER) {
			return coalition.getCollaboratorsSetByRole(CollaboratorRole.CARRIER).iterator().next().getId();
		}
		throw new IllegalStateException("Unsupported collaboration type for approximate Shapley allocation: " + coalition.getCollaborationType());
	}

	private long factorial(int n) {
		if (n <= 1) return 1;
		long result = 1;
		for (int i = 2; i <= n; i++) {
			result *= i;
		}
		return result;
	}
}
