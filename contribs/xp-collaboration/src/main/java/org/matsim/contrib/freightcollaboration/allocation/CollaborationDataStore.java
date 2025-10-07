package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;

import java.util.HashMap;
import java.util.Map;

@Singleton
public class CollaborationDataStore {

	// This map stores the original plans of each collaborator before any modifications due to collaboration.
	// Note: this map should not be changed after initialization.
	private final Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans;
	private Map<CollaboratorRole, Map<Id<?>, Double>> contributions;


	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = originalPlans;
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

}
