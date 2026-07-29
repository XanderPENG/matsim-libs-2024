package org.matsim.contrib.freightcollaboration.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;

import java.util.Map;
import java.util.Set;

/**
 * Test seam for deterministic characteristic-function evaluation.
 */
@FunctionalInterface
interface CoalitionValueEvaluator {
	double evaluate(Map<Id<?>, FreightCollaborator<?>> distributors,
					Map<Id<?>, FreightCollaborator<?>> players,
					Set<Id<?>> collaboratingPlayers);
}
