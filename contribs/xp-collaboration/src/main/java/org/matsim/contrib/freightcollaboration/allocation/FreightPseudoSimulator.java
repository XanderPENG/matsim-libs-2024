package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaborationType;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;

import java.util.Map;
import java.util.Set;

public class FreightPseudoSimulator {

	@Inject
	CollaborationDataStore collaborationDataStore;

	void run() {

	}

	/** Simulate all possible sub-coalitions of the given players and distributors
	 * For each sub-coalition, record its scores and return them as a map
	 */
	Map<Set<Id<?>>, Double> runAllSubCoalitions(Map<Id<?>, FreightCollaborator<?>> distributors, Map<Id<?>, FreightCollaborator<?>> players) {
		// Generate all possible sub-coalitions of the given players
		var subCoalitionScoreMap = AllocationUtils.generateSubsets(players);
		// For each sub-coalition, simulate it and record its score
		for (Set<Id<?>> subCoalition : subCoalitionScoreMap.keySet()) {
			// if it is the empty set

			if (subCoalition.isEmpty()) {
				// No collaboration, set score to 0 (ignore)
				continue;
			} else if (subCoalition.size() == players.size()) {
				// Full coalition, use the existing main-MATSim events
				continue;
			} else {
				// Partial coalition, simulate it separately
				continue;
			}
		}

		return subCoalitionScoreMap;
	}


}

