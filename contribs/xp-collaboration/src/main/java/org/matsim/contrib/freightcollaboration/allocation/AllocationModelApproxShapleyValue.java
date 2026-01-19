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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class AllocationModelApproxShapleyValue implements AllocationModel {

	public enum ApproximationMethod {
		MONTE_CARLO,
		STRATIFIED
	}

	private static final Logger logger = LogManager.getLogger(AllocationModelApproxShapleyValue.class);
	private final CollaborationDataStore collaborationDataStore;
	private final Supplier<FreightPseudoSimulator> pseudoSimulatorSupplier;
	private final List<MutableFreightCoalition> coalitions;
	private final double allocationFactor;
	private final ExecutorService executor;
	private final int parallelism;
	private long randomSeed = 1L;
	private boolean useCostSavings = true;
	private ApproximationMethod approximationMethod = ApproximationMethod.MONTE_CARLO;
	private int monteCarloSamples = 10;
	private double samplesRatio = 0.2;
	private int stratifiedSamplesPerLevel = 5;
	/** Hard cap on unique sub-coalitions evaluated per coalition when using stratified sampling. */
	private int maxStratifiedEvaluations = 100;

	public AllocationModelApproxShapleyValue(CollaborationDataStore collaborationDataStore,
											 Supplier<FreightPseudoSimulator> pseudoSimulatorSupplier,
											 List<MutableFreightCoalition> coalitions,
											 double allocationFactor,
											 ExecutorService executor,
											 int parallelism) {
		this.collaborationDataStore = collaborationDataStore;
		this.pseudoSimulatorSupplier = pseudoSimulatorSupplier;
		this.coalitions = coalitions;
		this.allocationFactor = allocationFactor;
		this.executor = executor;
		this.parallelism = Math.max(1, parallelism);
	}

	public void setApproximationMethod(ApproximationMethod approximationMethod) {
		this.approximationMethod = approximationMethod;
	}

	public void setMonteCarloSamples(int monteCarloSamples) {
		this.monteCarloSamples = monteCarloSamples;
	}

	/**
	 * Scale factor (0–1] applied to the configured sample counts so users can
	 * quickly dial runtime up/down without changing absolute defaults.
	 */
	public void setSamplesRatio(double samplesRatio) {
		if (samplesRatio <= 0.0 || samplesRatio > 1.0) {
			throw new IllegalArgumentException("samplesRatio must be in (0, 1].");
		}
		this.samplesRatio = samplesRatio;
	}

	public void setStratifiedSamplesPerLevel(int stratifiedSamplesPerLevel) {
		this.stratifiedSamplesPerLevel = stratifiedSamplesPerLevel;
	}

	public void setRandomSeed(long seed) {
		this.randomSeed = seed;
	}

	/** Limit the total pseudo-sim evaluations performed by the stratified sampler (per coalition). */
	public void setMaxStratifiedEvaluations(int maxStratifiedEvaluations) {
		if (maxStratifiedEvaluations <= 0) {
			throw new IllegalArgumentException("maxStratifiedEvaluations must be positive.");
		}
		this.maxStratifiedEvaluations = maxStratifiedEvaluations;
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

		List<Callable<CoalitionResult>> tasks = coalitions.stream()
			.<Callable<CoalitionResult>>map(coalition -> () -> computeCoalitionAllocation(coalition))
			.toList();

		List<CoalitionResult> results = executeTasks(tasks);

		Map<Id<?>, Double> finalAllocations = new HashMap<>();
		for (CoalitionResult result : results) {
			if (result == null) {
				continue;
			}
			result.allocations().forEach((id, value) -> finalAllocations.merge(id, value, Double::sum));
			if (result.valueCache() != null && !result.valueCache().isEmpty()) {
				collaborationDataStore.addSimulatedCoalitionScores(result.coalition(), result.valueCache());
			}
		}

		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	private CoalitionResult computeCoalitionAllocation(MutableFreightCoalition coalition) {
		Map<Id<?>, FreightCollaborator<?>> players = AllocationUtils.extractValidPlayers(coalition);
		Map<Id<?>, FreightCollaborator<?>> distributors = AllocationUtils.extractValidDistributors(coalition);
		if (players.isEmpty()) {
			logger.warn("Coalition {} has no collaborating players, skipping.", coalition);
			return null;
		}

		FreightPseudoSimulator pseudoSimulator = pseudoSimulatorSupplier.get();
		Random rnd = new Random(randomSeed + Math.abs((long) coalition.hashCode()));

		Map<Set<Id<?>>, Double> valueCache = new HashMap<>();       // savings cache (or raw when cost mode)
		Map<Set<Id<?>>, Double> rawValueCache = new HashMap<>();    // raw psim values cache

		// Compute baseline once per coalition
		double baselineRaw = pseudoSimulator.runSingleSubCoalition(distributors, players, Set.of());
		rawValueCache.put(Set.of(), baselineRaw);
		valueCache.put(Set.of(), useCostSavings ? 0.0 : baselineRaw);

		Map<Id<?>, Double> shapleyValues;
		if (approximationMethod == ApproximationMethod.STRATIFIED) {
			shapleyValues = approximateShapleyStratified(players, distributors, valueCache, rawValueCache, baselineRaw,
				pseudoSimulator, rnd);
		} else {
			shapleyValues = approximateShapleyMonteCarlo(players, distributors, valueCache, rawValueCache, baselineRaw,
				pseudoSimulator, rnd);
		}

		double totalShapleyValue = shapleyValues.values().stream().mapToDouble(Double::doubleValue).sum();
		double reservedShare = totalShapleyValue * (1 - allocationFactor);

		Map<Id<?>, Double> allocations = new HashMap<>();
		for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
			Id<?> collaboratorId = shapleyEntry.getKey();
			double allocation = shapleyEntry.getValue();
			allocations.put(collaboratorId, allocationFactor * allocation);
		}
		Id<?> distributorId = extractDistributorId(coalition);
		allocations.put(distributorId, reservedShare);

		return new CoalitionResult(coalition, allocations, valueCache);
	}

	private List<CoalitionResult> executeTasks(List<Callable<CoalitionResult>> tasks) {
		if (executor == null || parallelism <= 1 || tasks.size() == 1) {
			List<CoalitionResult> results = new ArrayList<>();
			for (Callable<CoalitionResult> task : tasks) {
				try {
					results.add(task.call());
				} catch (Exception e) {
					throw new RuntimeException("Error computing coalition allocation", e);
				}
			}
			return results;
		}
		try {
			List<Future<CoalitionResult>> futures = executor.invokeAll(tasks);
			List<CoalitionResult> results = new ArrayList<>(futures.size());
			for (Future<CoalitionResult> future : futures) {
				results.add(future.get());
			}
			return results;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted while computing coalition allocations", e);
		} catch (ExecutionException e) {
			throw new RuntimeException("Failed to compute coalition allocation", e.getCause());
		}
	}

	private Map<Id<?>, Double> approximateShapleyMonteCarlo(Map<Id<?>, FreightCollaborator<?>> players,
											 Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Set<Id<?>>, Double> valueCache,
											 Map<Set<Id<?>>, Double> rawValueCache,
											 double baselineRaw,
											 FreightPseudoSimulator pseudoSimulator,
											 Random rnd) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));

		valueCache.computeIfAbsent(Set.of(), k -> useCostSavings ? 0.0 : baselineRaw);
		rawValueCache.computeIfAbsent(Set.of(), k -> baselineRaw);

		int effectiveSamples = Math.max(1, (int) Math.round(monteCarloSamples * samplesRatio));
		for (int sample = 0; sample < effectiveSamples; sample++) {
			Collections.shuffle(playerList, rnd);
			Set<Id<?>> currentCoalition = new HashSet<>();
			double currentValue = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, currentCoalition,
				pseudoSimulator);
			for (Id<?> playerId : playerList) {
				currentCoalition.add(playerId);
				double valueWith = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, currentCoalition,
					pseudoSimulator);
				double marginalContribution = valueWith - currentValue;
				shapleyValues.put(playerId, shapleyValues.get(playerId) + marginalContribution);
				currentValue = valueWith;
			}
		}

		shapleyValues.replaceAll((id, value) -> Math.max(0.0, value / effectiveSamples));
		return shapleyValues;
	}

	private Map<Id<?>, Double> approximateShapleyStratified(Map<Id<?>, FreightCollaborator<?>> players,
											 Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Set<Id<?>>, Double> valueCache,
											 Map<Set<Id<?>>, Double> rawValueCache,
											 double baselineRaw,
											 FreightPseudoSimulator pseudoSimulator,
											 Random rnd) {
		List<Id<?>> playerList = new ArrayList<>(players.keySet());
		Map<Id<?>, Double> shapleyValues = new HashMap<>();
		playerList.forEach(id -> shapleyValues.put(id, 0.0));
		int n = playerList.size();

		valueCache.computeIfAbsent(Set.of(), k -> useCostSavings ? 0.0 : baselineRaw);
		rawValueCache.computeIfAbsent(Set.of(), k -> baselineRaw);

		// Adaptively compute samples per level based on n and maxStratifiedEvaluations
		int adaptiveSamplesPerLevel = computeAdaptiveSamplesPerLevel(n);
		long totalExactCoalitions = (long) Math.pow(2, n);

		logger.info("Stratified Shapley: n={}, adaptiveSamplesPerLevel={}, maxEvaluations={}, exactCoalitions={}",
					n, adaptiveSamplesPerLevel, maxStratifiedEvaluations, totalExactCoalitions);

		// Phase 1: sample once, remember the subsets per player/stratum, and collect the
		// unique coalitions that actually need simulation. This avoids repeated psim runs
		// during the Shapley accumulation.
		Map<Id<?>, Map<Integer, List<Set<Id<?>>>>> sampledByPlayer = new HashMap<>();
		Set<Set<Id<?>>> subsetsToEvaluate = new HashSet<>();

		for (Id<?> playerId : playerList) {
			Map<Integer, List<Set<Id<?>>>> perK = new HashMap<>();
			for (int k = 0; k <= n - 1; k++) {
				long combCount = combination(n - 1, k);
				int baseSamples = Math.max(1, adaptiveSamplesPerLevel);
				int samples = (int) Math.min(combCount, Math.max(1, Math.round(baseSamples * samplesRatio)));
				List<Set<Id<?>>> sampledSubsets = sampleSubsetsWithoutReplacement(playerList, playerId, k, samples, rnd);
				perK.put(k, sampledSubsets);
				subsetsToEvaluate.addAll(sampledSubsets);
				for (Set<Id<?>> subset : sampledSubsets) {
					Set<Id<?>> withPlayer = new HashSet<>(subset);
					withPlayer.add(playerId);
					subsetsToEvaluate.add(withPlayer);
				}
			}
			sampledByPlayer.put(playerId, perK);
		}

		// Optional pruning: if the union of sampled coalitions is still too large, shrink
		// every per-stratum sample list proportionally so total evaluations stay under the cap.
		int coalitionsBeforePruning = subsetsToEvaluate.size();
		if (subsetsToEvaluate.size() > maxStratifiedEvaluations) {
			logger.info("Pruning coalitions: before={}, cap={}", subsetsToEvaluate.size(), maxStratifiedEvaluations);
			double keepRatio = (double) maxStratifiedEvaluations / subsetsToEvaluate.size();
			sampledByPlayer.replaceAll((pid, perK) -> {
				Map<Integer, List<Set<Id<?>>>> pruned = new HashMap<>();
				perK.forEach((k, list) -> {
					int keep = Math.max(1, (int) Math.ceil(list.size() * keepRatio));
					Collections.shuffle(list, rnd);
					pruned.put(k, new ArrayList<>(list.subList(0, keep)));
				});
				return pruned;
			});
			// rebuild the evaluation set from pruned lists
			subsetsToEvaluate.clear();
			for (Map.Entry<Id<?>, Map<Integer, List<Set<Id<?>>>>> entry : sampledByPlayer.entrySet()) {
				Id<?> pid = entry.getKey();
				for (List<Set<Id<?>>> subsets : entry.getValue().values()) {
					for (Set<Id<?>> subset : subsets) {
						subsetsToEvaluate.add(subset);
						Set<Id<?>> withPlayer = new HashSet<>(subset);
						withPlayer.add(pid);
						subsetsToEvaluate.add(withPlayer);
					}
				}
			}
			logger.info("After pruning: {} coalitions (reduced by {}%)",
						subsetsToEvaluate.size(),
						String.format("%.1f", 100.0 * (1 - (double)subsetsToEvaluate.size() / coalitionsBeforePruning)));
		}

		logger.info("Total unique coalitions to evaluate: {} (exact would be: {})",
					subsetsToEvaluate.size(), totalExactCoalitions);

		// Phase 2: evaluate every unique coalition only once; cached results are reused
		// in the accumulation below.
		for (Set<Id<?>> subset : subsetsToEvaluate) {
			evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, subset, pseudoSimulator);
		}

		// Phase 3: accumulate Shapley estimates from the cached values.
		for (Id<?> playerId : playerList) {
			double shapleyEstimate = 0.0;
			Map<Integer, List<Set<Id<?>>>> perK = sampledByPlayer.get(playerId);
			for (int k = 0; k <= n - 1; k++) {
				double weight = 1.0 / n; // each subset size contributes equally in expectation
				List<Set<Id<?>>> sampledSubsets = perK.get(k);
				double marginalSum = 0.0;
				for (Set<Id<?>> subset : sampledSubsets) {
					double valueWithout = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, subset,
						pseudoSimulator);
					Set<Id<?>> withPlayer = new HashSet<>(subset);
					withPlayer.add(playerId);
					double valueWith = evaluateSubCoalition(distributors, players, valueCache, rawValueCache, baselineRaw, withPlayer,
						pseudoSimulator);
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

	/**
	 * Adaptively compute the number of samples per level based on n and maxStratifiedEvaluations.
	 * The goal is to avoid over-sampling for small n while maintaining good approximation for large n.
	 *
	 * Strategy:
	 * - For small n (≤12): Use aggressive reduction to avoid generating more coalitions than exact method
	 * - For medium n (13-20): Scale samples based on target evaluation budget
	 * - For large n (>20): Use user-configured stratifiedSamplesPerLevel
	 *
	 * @param n Number of players
	 * @return Adaptive samples per level
	 */
	private int computeAdaptiveSamplesPerLevel(int n) {
		// For very small n, exact computation is more efficient
		if (n <= 8) {
			return 2;  // Minimal sampling, exact would be better
		}

		// For small n (9-12), use conservative sampling
		if (n <= 12) {
			// Target: ~200-400 total coalitions for n=10-12
			// Rough formula: totalCoalitions ≈ n * n * 2 * samples / deduplicationFactor
			// With deduplicationFactor ≈ 3 for small n:
			// samples ≈ maxStratifiedEvaluations * 3 / (n * n * 2)
			double targetSamples = (maxStratifiedEvaluations * 2.5) / (n * n * 2.0);
			return Math.max(2, Math.min((int) Math.ceil(targetSamples), stratifiedSamplesPerLevel));
		}

		// For medium n (13-20), scale based on budget
		if (n <= 20) {
			// Gradually increase samples as n grows
			// For n=15: aim for ~60% of maxStratifiedEvaluations
			// For n=20: aim for ~80% of maxStratifiedEvaluations
			double targetRatio = 0.5 + (n - 13) * 0.04;  // 0.5 at n=13, 0.78 at n=20
			double targetSamples = (maxStratifiedEvaluations * targetRatio) / (n * n * 2.0);
			return Math.max(3, Math.min((int) Math.ceil(targetSamples), stratifiedSamplesPerLevel));
		}

		// For large n (>20), use configured value or scale based on extreme growth
		if (n <= 25) {
			return stratifiedSamplesPerLevel;
		}

		// For very large n, might need even more samples for accuracy
		return Math.min(stratifiedSamplesPerLevel * 2, 50);
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
		Set<Set<Id<?>>> result = new HashSet<>();
		int attempts = 0;
		int maxAttempts = target * 10; // safeguard against degenerate loops
		while (result.size() < target && attempts < maxAttempts) {
			Collections.shuffle(candidates, rnd);
			result.add(new HashSet<>(candidates.subList(0, subsetSize)));
			attempts++;
		}
		return new ArrayList<>(result);
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
									 Set<Id<?>> subCoalition,
									 FreightPseudoSimulator pseudoSimulator) {
		Set<Id<?>> key = Set.copyOf(subCoalition);
		return valueCache.computeIfAbsent(key,
				ignored -> {
					double rawValue = rawValueCache.computeIfAbsent(key,
						k -> pseudoSimulator.runSingleSubCoalition(distributors, players, k));
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
		} else if (coalition.getCollaborationType() == CollaborationTypes.LSP_RECEIVER) {
			return coalition.getCollaboratorsSetByRole(CollaboratorRole.LSP).iterator().next().getId();
		}
		throw new IllegalStateException("Unsupported collaboration type for approximate Shapley allocation: " + coalition.getCollaborationType());
	}

	private record CoalitionResult(MutableFreightCoalition coalition,
								   Map<Id<?>, Double> allocations,
								   Map<Set<Id<?>>, Double> valueCache) { }
}
