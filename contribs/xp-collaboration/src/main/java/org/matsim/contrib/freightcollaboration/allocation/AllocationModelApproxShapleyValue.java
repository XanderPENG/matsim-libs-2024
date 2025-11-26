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
	private final double allocationFactor;
	private boolean useCostSavings = true;
	private ApproximationMethod approximationMethod = ApproximationMethod.MONTE_CARLO;
	private int monteCarloSamples = 20;
	private double SamplesRatio = 0.4;
	private int stratifiedSamplesPerLevel = 20;

	public AllocationModelApproxShapleyValue(CollaborationDataStore collaborationDataStore,
											 FreightPseudoSimulator freightPseudoSimulator,
											 List<MutableFreightCoalition> coalitions,
											 double allocationFactor) {
		this.collaborationDataStore = collaborationDataStore;
		this.freightPseudoSimulator = freightPseudoSimulator;
		this.coalitions = coalitions;
		this.allocationFactor = allocationFactor;
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
		this.useCostSavings = (type == AllocationValueTypes.COST_SAVINGS);
		allocateValues();
	}

	private void allocateValues() {
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

			Map<Set<Id<?>>, Double> valueCache = new HashMap<>();       // savings cache (or raw when cost mode)
			Map<Set<Id<?>>, Double> rawValueCache = new HashMap<>();    // raw psim values cache

			// Compute baseline once per coalition
			double baselineRaw = freightPseudoSimulator.runSingleSubCoalition(distributors, players, Set.of());
			rawValueCache.put(Set.of(), baselineRaw);
			valueCache.put(Set.of(), useCostSavings ? 0.0 : baselineRaw);

			Map<Id<?>, Double> shapleyValues;
			if (approximationMethod == ApproximationMethod.STRATIFIED) {
				shapleyValues = approximateShapleyStratified(players, distributors, valueCache, rawValueCache, baselineRaw);
			} else {
				shapleyValues = approximateShapleyMonteCarlo(players, distributors, valueCache, rawValueCache, baselineRaw);
			}

			double totalShapleyValue = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
			double reservedShare = totalShapleyValue * (1 - allocationFactor);

			for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
				Id<?> collaboratorId = shapleyEntry.getKey();
				double allocation = shapleyEntry.getValue();
				finalAllocations.put(collaboratorId, finalAllocations.getOrDefault(collaboratorId, 0.0) + allocationFactor * allocation);
			}
			Id<?> distributorId = extractDistributorId(coalition);
			finalAllocations.put(distributorId, finalAllocations.getOrDefault(distributorId, 0.0) + reservedShare);

			// Store the sampled scores for transparency
			collaborationDataStore.addSimulatedCoalitionScores(coalition, valueCache);
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private Map<Id<?>, Double> approximateShapleyMonteCarlo(Map<Id<?>, FreightCollaborator<?>> players,
											 Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Set<Id<?>>, Double> valueCache,
											 Map<Set<Id<?>>, Double> rawValueCache,
											 double baselineRaw) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));

		valueCache.computeIfAbsent(Set.of(), k -> useCostSavings ? 0.0 : baselineRaw);
		rawValueCache.computeIfAbsent(Set.of(), k -> baselineRaw);

		for (int sample = 0; sample < monteCarloSamples; sample++) {
			Collections.shuffle(playerList, random);
			Set<Id<?>> currentCoalition = new HashSet<>();
			double currentValue = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, currentCoalition);
			for (Id<?> playerId : playerList) {
				currentCoalition.add(playerId);
				double valueWith = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, currentCoalition);
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
											 Map<Set<Id<?>>, Double> valueCache,
											 Map<Set<Id<?>>, Double> rawValueCache,
											 double baselineRaw) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));
		int n = playerList.size();

		valueCache.computeIfAbsent(Set.of(), k -> useCostSavings ? 0.0 : baselineRaw);
		rawValueCache.computeIfAbsent(Set.of(), k -> baselineRaw);

		for (Id<?> playerId : playerList) {
			double shapleyEstimate = 0.0;
			for (int k = 0; k <= n - 1; k++) {
				double weight = 1.0 / n; // each subset size contributes equally in expectation
				long combCount = combination(n - 1, k);
				int samples = (int) Math.min(combCount, Math.max(1, stratifiedSamplesPerLevel));
				List<Set<Id<?>>> sampledSubsets = sampleSubsetsWithoutReplacement(playerList, playerId, k, samples, random);
				double marginalSum = 0.0;
				for (Set<Id<?>> subset : sampledSubsets) {
					double valueWithout = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, subset);
					Set<Id<?>> withPlayer = new HashSet<>(subset);
					withPlayer.add(playerId);
					double valueWith = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, withPlayer);
					marginalSum += valueWith - valueWithout;
				}
				double averageMarginal = sampledSubsets.isEmpty() ? 0.0 : marginalSum / sampledSubsets.size();
				shapleyEstimate += weight * averageMarginal;
			}
			shapleyValues.put(playerId, shapleyEstimate);
		}

		shapleyValues.replaceAll((id, value) -> Math.max(0.0, value));
		return shapleyValues;
	}

	private List<Set<Id<?>>> sampleSubsetsWithoutReplacement(List<Id<?>> playerList, Id<?> excludedPlayer, int subsetSize, int samples, Random rnd) {
		List<Id<?>> candidates = new ArrayList<>();
		for (Id<?> id : playerList) {
			if (!id.equals(excludedPlayer)) {
				candidates.add(id);
			}
		}
		if (subsetSize == 0) {
			return List.of(Set.of());
		}
		long totalComb = combination(candidates.size(), subsetSize);
		int target = (int) Math.min(samples, totalComb);
		if (totalComb <= samples && totalComb <= 10000) {
			return enumerateCombinations(candidates, subsetSize, target);
		}
		List<Set<Id<?>>> result = new ArrayList<>();
		for (int i = 0; i < target; i++) {
			Collections.shuffle(candidates, rnd);
			result.add(new HashSet<>(candidates.subList(0, subsetSize)));
		}
		return result;
	}

	private List<Set<Id<?>>> enumerateCombinations(List<Id<?>> elements, int k, int limit) {
		List<Set<Id<?>>> res = new ArrayList<>();
		int n = elements.size();
		int[] idx = new int[k];
		for (int i = 0; i < k; i++) idx[i] = i;
		while (res.size() < limit) {
			Set<Id<?>> comb = new HashSet<>();
			for (int j = 0; j < k; j++) comb.add(elements.get(idx[j]));
			res.add(comb);
			int p = k - 1;
			while (p >= 0 && idx[p] == p + n - k) p--;
			if (p < 0) break;
			idx[p]++;
			for (int j = p + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
		}
		return res;
	}

	private long combination(int n, int k) {
		if (k < 0 || k > n) return 0;
		if (k == 0 || k == n) return 1;
		k = Math.min(k, n - k);
		long res = 1;
		for (int i = 1; i <= k; i++) {
			res = res * (n - k + i) / i;
		}
		return res;
	}

	private double evaluateSubCoalition(Map<Id<?>, FreightCollaborator<?>> distributors,
									 Map<Id<?>, FreightCollaborator<?>> players,
									 Map<Set<Id<?>>, Double> valueCache,
									 Map<Set<Id<?>>, Double> rawValueCache,
									 double baselineRaw,
									 Set<Id<?>> subCoalition) {
		Set<Id<?>> key = Set.copyOf(subCoalition);
		return valueCache.computeIfAbsent(key,
				ignored -> {
					double rawValue = rawValueCache.computeIfAbsent(key,
						k -> freightPseudoSimulator.runSingleSubCoalition(distributors, players, k));
					if (!useCostSavings) {
						return rawValue;
					}
					if (key.isEmpty()) {
						return 0.0;
					}
					return rawValue - baselineRaw;
				});
	}

	private Id<?> extractDistributorId(MutableFreightCoalition coalition) {
		if (coalition.getCollaborationType() == CollaborationTypes.CARRIER_RECEIVER) {
			return coalition.getCollaboratorsSetByRole(CollaboratorRole.CARRIER).iterator().next().getId();
		}
		throw new IllegalStateException("Unsupported collaboration type for approximate Shapley allocation: " + coalition.getCollaborationType());
	}
}
