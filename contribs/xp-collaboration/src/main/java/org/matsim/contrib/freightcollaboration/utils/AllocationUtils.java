package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModel;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;

import java.util.*;

public class AllocationUtils {

	public static AllocationModel createAllocationModel(AllocationModels allocationModelType) {
		// Placeholder for actual implementation
		switch (allocationModelType){
			case AllocationModels.PROPORTIONAL:
				// return new ProportionalAllocation();
			case AllocationModels.SHAPLEY:
				// return new ShapleyValueAllocationModel();
			default:
				throw new IllegalArgumentException("Unknown allocation model type: " + allocationModelType);
		}
	}

	public static Map<Set<Id<?>>, Double> generateSubsets(Map<Id<?>, ?> coalitionMembers) {
		List<Id<?>> ids = new ArrayList<>(coalitionMembers.keySet());
		Map<Set<Id<?>>, Double> result = new HashMap<>();

		int n = ids.size();
		int total = 1 << n; // 2^n subsets

		for (int mask = 0; mask < total; mask++) {
			Set<Id<?>> subset = new HashSet<>();
			for (int i = 0; i < n; i++) {
				if ((mask & (1 << i)) != 0) {
					subset.add(ids.get(i));
				}
			}
			result.put(subset, 0.0); // default value at the initialization
		}

		return result;
	}
}
