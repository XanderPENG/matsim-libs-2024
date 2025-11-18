package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ShapleyValueAllocationModel implements AllocationModel {

//	@Inject
	CollaborationDataStore collaborationDataStore;

	private static final Logger logger = LogManager.getLogger(ShapleyValueAllocationModel.class);

	public ShapleyValueAllocationModel(CollaborationDataStore collaborationDataStore) {
		this.collaborationDataStore = collaborationDataStore;
	}

	@Override
	public void allocate(AllocationValueTypes type) {
		// Implementation of Shapley Value allocation logic goes here
		if (type == AllocationValueTypes.COST_SAVINGS) {
			allocateCostSavings();
		} else {
			throw new UnsupportedOperationException("ShapleyValueAllocationModel currently supports only COST_SAVINGS allocation type.");
		}
	}

	// TODO: We may incorporate the allocation factor (i.e., how much cost savings could be allocated by the carrier) later if needed
	public void allocateCostSavings() {
		// Maintain a map to store final allocation values for each collaborator
		Map<Id<?>, Double> finalAllocations = new HashMap<>();

		// For-loop over all mutable coalitions
		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : collaborationDataStore.getSimulatedCoalitionScores().entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> coalitionScores = entry.getValue();

			// Calculate the cost savings for each subcoalition in  this coalition
			double nonCollaborativeCost = coalitionScores.get(Set.of());
			Map<Set<Id<?>>, Double> costSavings = new HashMap<>();
			for (Map.Entry<Set<Id<?>>, Double> scoreEntry : coalitionScores.entrySet()) {
				Set<Id<?>> subCoalition = scoreEntry.getKey();
				double collaborativeCost = scoreEntry.getValue();
				// it should not be negative, so we use absolute value here to avoid any issue
				double savings = Math.abs(nonCollaborativeCost - collaborativeCost);
				if (savings < 0) {
					logger.warn("Pls check that the collaborative cost savings should not be negative. now the savings: {}", savings);
				}
				costSavings.put(subCoalition, savings);
			}

			// Calculate Shapley values of cost savings for this coalition
			Map<Id<?>, Double> shapleyValues = calculateShapleyValues(costSavings);

			// record the allocations
			for (Map.Entry<Id<?>, Double> shapleyEntry : shapleyValues.entrySet()) {
				Id<?> collaboratorId = shapleyEntry.getKey();
				double allocation = shapleyEntry.getValue();
				finalAllocations.put(collaboratorId, finalAllocations.getOrDefault(collaboratorId, 0.0) + allocation);
			}
		}

		// Update the allocated values in the data store
		collaborationDataStore.setAllocatedValues(finalAllocations);
	}

	Map<Id<?>, Double> calculateShapleyValues(Map<Set<Id<?>>, Double> coalitionScores) {
		Map<Id<?>, Double> shapleyValues = new HashMap<>();

		// Extract all unique collaborator IDs from coalition scores
		Set<Id<?>> allCollaborators = new HashSet<>();
		for (Set<Id<?>> coalition : coalitionScores.keySet()) {
			allCollaborators.addAll(coalition);
		}

		int n = allCollaborators.size();

		// Calculate Shapley value for each collaborator
		for (Id<?> collaborator : allCollaborators) {
			double shapleyValue = 0.0;

			// Iterate through all coalitions
			for (Set<Id<?>> coalition : coalitionScores.keySet()) {
				// Check coalitions both with and without this collaborator
				if (coalition.contains(collaborator)) {
					// Create coalition without this collaborator
					Set<Id<?>> coalitionWithout = new HashSet<>(coalition);
					coalitionWithout.remove(collaborator);

					// Calculate marginal contribution
					double valueWith = coalitionScores.get(coalition);
					double valueWithout = coalitionScores.getOrDefault(coalitionWithout, 0.0);
					double marginalContribution = valueWith - valueWithout;

					// Weight by coalition size factor: (|S|! * (n - |S| - 1)!) / n!
					int coalitionSize = coalitionWithout.size();
					double weight = factorial(coalitionSize) * factorial(n - coalitionSize - 1) / (double) factorial(n);

					shapleyValue += weight * marginalContribution;
				}
			}

			shapleyValues.put(collaborator, shapleyValue);
		}

		return shapleyValues;
	}

	private long factorial(int n) {
		if (n <= 1) return 1;
		long result = 1;
		for (int i = 2; i <= n; i++) {
			result *= i;
		}
		return result;
	}

}
