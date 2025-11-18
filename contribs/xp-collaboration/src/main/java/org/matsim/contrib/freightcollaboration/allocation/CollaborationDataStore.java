package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.core.utils.collections.Tuple;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Singleton
public class CollaborationDataStore {

	// This map stores the original plans of each collaborator before any modifications due to collaboration.
	// Note: this map should not be changed after initialization.
	private final Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans;
	private Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedCoalitionScores;
	private Map<Id<?>, Double> allocatedValues;
	private Scenario scenario;

	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = originalPlans;
		resetSimulatedCoalitionScores();
	}

	/**
	 * Add the psim subcoalitions scores to the data store.
	 */
	public void addSimulatedCoalitionScores(MutableFreightCoalition coalition, Map<Set<Id<?>>, Double> subCoalitionScores) {
		if (simulatedCoalitionScores == null) {
			simulatedCoalitionScores = new HashMap<>();
		}
		// Add the new entry
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
}
