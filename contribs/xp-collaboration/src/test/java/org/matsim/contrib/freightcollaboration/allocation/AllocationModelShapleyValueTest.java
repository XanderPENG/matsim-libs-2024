package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
class AllocationModelShapleyValueTest {

    @Test
    void calculateShapleyValues() {
		CollaborationDataStore dataStore = null;  // Placeholder, not used in this test
        // Test implementation for calculateShapleyValues method
        AllocationModelShapleyValue model = new AllocationModelShapleyValue(dataStore, 0.9);

        Map<Set<Id<?>>, Double> coalitionAndScores = generateCoalitionsAndPositiveScores();
        Map<Id<?>, Double> shapley = model.calculateShapleyValues(coalitionAndScores);

		// Check for the positive scores case
        assertNotNull(shapley);
        // We expect three unique collaborators: a, b, c
        assertEquals(3, shapley.size());
		// Print the Shapley values for visual inspection
		for (Map.Entry<Id<?>, Double> entry : shapley.entrySet()) {
			System.out.println("Collaborator " + entry.getKey() + ": Shapley Value = " + entry.getValue());
		}

		// Test with negative scores
		Map<Set<Id<?>>, Double> negativeCoalitionAndScores = generateCoalitionsAndNegativeScores();
		Map<Id<?>, Double> negativeShapley = model.calculateShapleyValues(negativeCoalitionAndScores);
		assertNotNull(negativeShapley);
		assertEquals(3, negativeShapley.size());
		// Print the Shapley values for visual inspection
		for (Map.Entry<Id<?>, Double> entry : negativeShapley.entrySet()) {
			System.out.println("Collaborator " + entry.getKey() + ": Negative Shapley Value = " + entry.getValue());
		}
    }

    Map<Set<Id<?>>, Double> generateCoalitionsAndPositiveScores(){
        // Build a map from coalitions (sets of Ids) to positive double scores.
        Map<Set<Id<?>>, Double> coalitionAndScores = new HashMap<>();

        // Create Id instances. Use Object.class as the generic type so they are Id<?> compatible.
        Id<Object> a = Id.create("a", Object.class);
        Id<Object> b = Id.create("b", Object.class);
        Id<Object> c = Id.create("c", Object.class);

        coalitionAndScores.put(Collections.singleton(a), 50.0);
        coalitionAndScores.put(Collections.singleton(b), 50.0);
        coalitionAndScores.put(Collections.singleton(c), 50.0);

        coalitionAndScores.put(new HashSet<>(Arrays.asList(a, b)), 45.0);
        coalitionAndScores.put(new HashSet<>(Arrays.asList(a, c)), 35.0);
        coalitionAndScores.put(new HashSet<>(Arrays.asList(b, c)), 25.0);
        coalitionAndScores.put(new HashSet<>(Arrays.asList(a, b, c)), 20.0);

        return coalitionAndScores;
    }

	Map<Set<Id<?>>, Double> generateCoalitionsAndNegativeScores(){
		// Build a map from coalitions (sets of Ids) to positive double scores.
		Map<Set<Id<?>>, Double> coalitionAndScores = new HashMap<>();

		// Create Id instances. Use Object.class as the generic type so they are Id<?> compatible.
		Id<Object> a = Id.create("a", Object.class);
		Id<Object> b = Id.create("b", Object.class);
		Id<Object> c = Id.create("c", Object.class);

		coalitionAndScores.put(Collections.singleton(a), -50.0);
		coalitionAndScores.put(Collections.singleton(b), -50.0);
		coalitionAndScores.put(Collections.singleton(c), -50.0);

		coalitionAndScores.put(new HashSet<>(Arrays.asList(a, b)), -45.0);
		coalitionAndScores.put(new HashSet<>(Arrays.asList(a, c)), -35.0);
		coalitionAndScores.put(new HashSet<>(Arrays.asList(b, c)), -25.0);
		coalitionAndScores.put(new HashSet<>(Arrays.asList(a, b, c)), -20.0);

		return coalitionAndScores;
	}
}
