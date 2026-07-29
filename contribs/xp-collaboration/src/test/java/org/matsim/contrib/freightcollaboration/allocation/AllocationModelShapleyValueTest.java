package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AllocationModelShapleyValueTest {
	private static final Id<Object> A = Id.create("a", Object.class);
	private static final Id<Object> B = Id.create("b", Object.class);

	@Test
	void exactShapleySatisfiesAdditivitySymmetryDummyAndEfficiency() {
		AllocationModelShapleyValue model =
			new AllocationModelShapleyValue(FreightCollaborationTestFixtures.emptyDataStore(), 1.0);

		Map<Id<?>, Double> additive = model.calculateShapleyValues(game(0, 2, 3, 5));
		assertEquals(2.0, additive.get(A), 1e-12);
		assertEquals(3.0, additive.get(B), 1e-12);
		assertEquals(5.0, additive.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);

		Map<Id<?>, Double> synergy = model.calculateShapleyValues(game(0, 0, 0, 10));
		assertEquals(5.0, synergy.get(A), 1e-12);
		assertEquals(5.0, synergy.get(B), 1e-12);

		Map<Id<?>, Double> negativeDummy = model.calculateShapleyValues(game(0, -2, 3, 1));
		assertEquals(-2.0, negativeDummy.get(A), 1e-12);
		assertEquals(3.0, negativeDummy.get(B), 1e-12);
	}

	@Test
	void exactShapleyRejectsIncompleteOrNonFiniteGames() {
		AllocationModelShapleyValue model =
			new AllocationModelShapleyValue(FreightCollaborationTestFixtures.emptyDataStore(), 1.0);
		Map<Set<Id<?>>, Double> missing = new LinkedHashMap<>(game(0, 1, 2, 3));
		missing.remove(Set.of(A));
		assertThrows(IllegalArgumentException.class, () -> model.calculateShapleyValues(missing));

		Map<Set<Id<?>>, Double> nonFinite = new LinkedHashMap<>(game(0, 1, 2, 3));
		nonFinite.put(Set.of(A), Double.NaN);
		assertThrows(IllegalArgumentException.class, () -> model.calculateShapleyValues(nonFinite));
	}

	@Test
	void costSavingsAllocationUsesRoleAwareKeysAndBalancesBudget() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("same", "same");
		Id<?> receiverId = coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)
			.iterator().next().getId();
		store.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(), 10.0,
			Set.of(receiverId), 14.0));

		new AllocationModelShapleyValue(store, 0.75).allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(3.0,
			store.getAllocatedValue(CollaboratorRole.RECEIVER, receiverId), 1e-12);
		assertEquals(1.0,
			store.getAllocatedValue(CollaboratorRole.CARRIER, Id.create("same", Object.class)), 1e-12);
		assertEquals(2, store.getAllocatedValues().size());
		assertEquals(4.0, store.getAllocatedValues().values().stream()
			.mapToDouble(Double::doubleValue).sum(), 1e-12);
	}

	@Test
	void costAllocationUsesCharacteristicFunctionWithoutIdCollision() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		Id<?> receiverId = coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)
			.iterator().next().getId();
		store.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(), 0.0,
			Set.of(receiverId), 8.0));

		new AllocationModelShapleyValue(store, 0.75).allocate(AllocationValueTypes.COST);

		assertEquals(6.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, receiverId), 1e-12);
		assertEquals(2.0, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
	}

	private static Map<Set<Id<?>>, Double> game(double empty, double a, double b, double both) {
		Map<Set<Id<?>>, Double> values = new LinkedHashMap<>();
		values.put(Set.of(), empty);
		values.put(Set.of(A), a);
		values.put(Set.of(B), b);
		values.put(Set.of(A, B), both);
		return values;
	}
}
