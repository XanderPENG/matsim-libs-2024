package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;

import java.util.Map;

@Singleton
public class CollaborationDataStore {

	// This map stores the original plans of each collaborator before any modifications due to collaboration.
	// Note: this map should not be changed after initialization.
	private final Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans;

	public CollaborationDataStore(Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans) {
		this.originalPlans = originalPlans;
	}

}
