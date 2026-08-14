package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class CollaborationDataStore {

	// This map stores the original plans of each collaborator before any modifications due to collaboration.
	// Note: this map should not be changed after initialization.
	private final Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans;
	private volatile Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedCoalitionScores;
	private volatile Map<CollaboratorKey, Double> allocatedValues;
	private volatile Scenario scenario;
	private volatile Map<Id<Carrier>, Carrier> LspReceiverCopiedNonDistrCarriers;
	private volatile Map<Id<Carrier>, Double> iter0CarrierBaselineFeeFree;
	private volatile Map<Id<Carrier>, Double> iter0CarrierBaselineFeeIncluded;
	private final Map<MutableFreightCoalition, Double> appliedAllocationFactors = new ConcurrentHashMap<>();
	private final Map<CollaboratorKey, Double> distributorPlayerTransfers = new ConcurrentHashMap<>();
	private volatile int collaborationResultIteration = -1;

	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = copyOriginalPlans(originalPlans);
		resetSimulatedCoalitionScores();
	}


	/**
	 * Add the psim subcoalitions scores to the data store.
	 */
	public void addSimulatedCoalitionScores(MutableFreightCoalition coalition, Map<Set<Id<?>>, Double> subCoalitionScores) {
		if (simulatedCoalitionScores == null) {
			simulatedCoalitionScores = new ConcurrentHashMap<>();
		}
		// Add/overwrite the entry (parallel-safe)
		Map<Set<Id<?>>, Double> scoresCopy = new LinkedHashMap<>();
		subCoalitionScores.forEach((members, score) -> scoresCopy.put(Set.copyOf(members), score));
		simulatedCoalitionScores.put(coalition, Map.copyOf(scoresCopy));
	}

	private void resetSimulatedCoalitionScores() {
		simulatedCoalitionScores = null;
	}

	public Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> getOriginalPlans() {
		return originalPlans;
	}

	public Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> getSimulatedCoalitionScores() {
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> scores = simulatedCoalitionScores;
		return scores == null ? null : Map.copyOf(scores);
	}

	public Map<CollaboratorKey, Double> getAllocatedValues() {
		return allocatedValues;
	}

	public void setAllocatedValues(Map<CollaboratorKey, Double> allocatedValues) {
		this.allocatedValues = allocatedValues == null ? null : Map.copyOf(allocatedValues);
	}

	public double getAllocatedValue(CollaboratorRole role, Id<?> id) {
		Map<CollaboratorKey, Double> values = allocatedValues;
		return values == null ? 0.0 : values.getOrDefault(new CollaboratorKey(role, id), 0.0);
	}

	public void recordAppliedAllocationFactor(MutableFreightCoalition coalition, double factor) {
		if (!Double.isFinite(factor) || factor < 0.0 || factor > 1.0) {
			throw new IllegalArgumentException("Applied allocation factor must be in [0,1].");
		}
		appliedAllocationFactors.put(coalition, factor);
	}

	public Map<MutableFreightCoalition, Double> getAppliedAllocationFactors() {
		return Map.copyOf(appliedAllocationFactors);
	}

	public void recordDistributorPlayerTransfer(CollaboratorKey distributor, double signedTransfer) {
		if (!Double.isFinite(signedTransfer)) {
			throw new IllegalArgumentException("Distributor transfer must be finite.");
		}
		distributorPlayerTransfers.merge(distributor, signedTransfer, Double::sum);
	}

	public Map<CollaboratorKey, Double> getDistributorPlayerTransfers() {
		return Map.copyOf(distributorPlayerTransfers);
	}

	public double getDistributorPlayerTransfer(CollaboratorRole role, Id<?> id) {
		return distributorPlayerTransfers.getOrDefault(new CollaboratorKey(role, id), 0.0);
	}

	/** Iteration whose reset/PSim/allocation cycle produced the currently stored mutable results. */
	public int getCollaborationResultIteration() {
		return collaborationResultIteration;
	}

	public void markCollaborationResultIteration(int iteration) {
		if (iteration < 0) {
			throw new IllegalArgumentException("Collaboration result iteration must be non-negative.");
		}
		collaborationResultIteration = iteration;
	}

	// Reset the entire data store (only used at the beginning of a new simulation)
	public void reset(){
		resetSimulatedCoalitionScores();
		allocatedValues = null;
		appliedAllocationFactors.clear();
		distributorPlayerTransfers.clear();
		collaborationResultIteration = -1;
	}

	/** Starts a new iteration-scoped collaboration result buffer. */
	public void reset(int iteration) {
		reset();
		markCollaborationResultIteration(iteration);
	}

	public Scenario getScenario() {
		return scenario;
	}

	public void setScenario(Scenario scenario) {
		this.scenario = scenario;
	}

	public void setLspReceiverCopiedNonDistrCarriers(Map<Id<Carrier>, Carrier> carrierMap) {
		this.LspReceiverCopiedNonDistrCarriers = carrierMap == null ? null : Map.copyOf(carrierMap);
	}

	public Map<Id<Carrier>, Carrier> getLspReceiverCopiedNonDistrCarriers() {
		return LspReceiverCopiedNonDistrCarriers;
	}

	public void setIter0CarrierBaselineFeeFree(Map<Id<Carrier>, Double> baselineScores) {
		this.iter0CarrierBaselineFeeFree = baselineScores == null ? null : Map.copyOf(baselineScores);
	}

	public Map<Id<Carrier>, Double> getIter0CarrierBaselineFeeFree() {
		return iter0CarrierBaselineFeeFree;
	}

	public void setIter0CarrierBaselineFeeIncluded(Map<Id<Carrier>, Double> baselineScores) {
		this.iter0CarrierBaselineFeeIncluded = baselineScores == null ? null : Map.copyOf(baselineScores);
	}

	public Map<Id<Carrier>, Double> getIter0CarrierBaselineFeeIncluded() {
		return iter0CarrierBaselineFeeIncluded;
	}

	private static Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> copyOriginalPlans(
			Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}
		Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> copy =
			new EnumMap<>(CollaboratorRole.class);
		source.forEach((role, plans) ->
			copy.put(role, plans == null ? Map.of() : Map.copyOf(new HashMap<>(plans))));
		return Map.copyOf(copy);
	}
}
