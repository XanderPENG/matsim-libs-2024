package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
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
	private Map<CollaboratorRole, Map<Id<?>, Double>> contributions;
	private Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedCoalitionScores;


	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = originalPlans;
		resetSimulatedCoalitionScores();
		resetContributions();
	}

	private void resetContributions() {

		// Reset the contributions map to zero for all collaborators for the new iteration.
		// This make sure that contributions has the same structure as originalPlans
		contributions = new HashMap<>();
		for (Map.Entry <CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> entry : originalPlans.entrySet()) {
			var role = entry.getKey();
			var plansMap = entry.getValue();
			Map<Id<?>, Double> roleContributions = new HashMap<>();
			for (Id<?> id : plansMap.keySet()) {
				roleContributions.put(id, 0.0);
			}
			contributions.put(role, roleContributions);
		}

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

}
