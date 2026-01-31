package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class CollaborationDataStore {

	// This map stores the original plans of each collaborator before any modifications due to collaboration.
	// Note: this map should not be changed after initialization.
	private final Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans;
	private Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedCoalitionScores;
	private Map<Id<?>, Double> allocatedValues;
	private Scenario scenario;
	private Map<Id<Carrier>, Carrier> LspReceiverCopiedNonDistrCarriers;
	private Map<Id<Carrier>, Double> iter0CarrierBaselineFeeFree;
	private Map<Id<Carrier>, Double> iter0CarrierBaselineFeeIncluded;

	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = originalPlans;
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
		simulatedCoalitionScores.put(coalition, subCoalitionScores);
	}

	private void resetSimulatedCoalitionScores() {
		simulatedCoalitionScores = null;
	}

	public Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> getOriginalPlans() {
		return originalPlans;
	}

	public Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> getSimulatedCoalitionScores() {
		return simulatedCoalitionScores;
	}

	public Map<Id<?>, Double> getAllocatedValues() {
		return allocatedValues;
	}

	public void setAllocatedValues(Map<Id<?>, Double> allocatedValues) {
		this.allocatedValues = allocatedValues;
	}

	// Reset the entire data store (only used at the beginning of a new simulation)
	public void reset(){
		resetSimulatedCoalitionScores();
		allocatedValues = null;
	}

	public Scenario getScenario() {
		return scenario;
	}

	public void setScenario(Scenario scenario) {
		this.scenario = scenario;
	}

	public void setLspReceiverCopiedNonDistrCarriers(Map<Id<Carrier>, Carrier> carrierMap) {
		this.LspReceiverCopiedNonDistrCarriers = carrierMap;
	}

	public Map<Id<Carrier>, Carrier> getLspReceiverCopiedNonDistrCarriers() {
		return LspReceiverCopiedNonDistrCarriers;
	}

	public void setIter0CarrierBaselineFeeFree(Map<Id<Carrier>, Double> baselineScores) {
		this.iter0CarrierBaselineFeeFree = baselineScores;
	}

	public Map<Id<Carrier>, Double> getIter0CarrierBaselineFeeFree() {
		return iter0CarrierBaselineFeeFree;
	}

	public void setIter0CarrierBaselineFeeIncluded(Map<Id<Carrier>, Double> baselineScores) {
		this.iter0CarrierBaselineFeeIncluded = baselineScores;
	}

	public Map<Id<Carrier>, Double> getIter0CarrierBaselineFeeIncluded() {
		return iter0CarrierBaselineFeeIncluded;
	}
}
