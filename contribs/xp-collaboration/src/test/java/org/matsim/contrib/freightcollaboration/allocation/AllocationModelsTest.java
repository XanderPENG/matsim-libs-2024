package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationModelsTest {

	@Test
	void proportionalUsesSingletonWeightsAndReservesDistributorShare() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b");
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		store.addSimulatedCoalitionScores(coalition, game(0, a, 2, b, 6, 12));

		new AllocationModelProportional(store, 0.75).allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(2.25, store.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(6.75, store.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);
		assertEquals(3.0, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
	}

	@Test
	void proportionalFallsBackToEqualSharesWhenAllWeightsAreZero() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b");
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		store.addSimulatedCoalitionScores(coalition, game(0, a, 0, b, 0, 10));

		new AllocationModelProportional(store, 0.8).allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(4.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(4.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);
		assertEquals(2.0, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
	}

	@Test
	void marginalContributionNormalizesPlayerBudget() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b");
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		store.addSimulatedCoalitionScores(coalition, game(0, a, 1, b, 4, 10));

		new AllocationModelMarginalContribution(store, 0.8).allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(3.2, store.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(4.8, store.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);
		assertEquals(2.0, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
	}

	@Test
	void allocationModelsValidateFactorAndHandleMissingScores() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		assertThrows(IllegalArgumentException.class, () -> new AllocationModelProportional(store, -0.1));
		assertThrows(IllegalArgumentException.class, () -> new AllocationModelMarginalContribution(store, 1.1));
		assertThrows(IllegalArgumentException.class, () -> new AllocationModelShapleyValue(store, 2));

		new AllocationModelProportional(store, 1).allocate(AllocationValueTypes.COST);
		assertNull(store.getAllocatedValues());
		new AllocationModelMarginalContribution(store, 1).allocate(AllocationValueTypes.COST);
		assertNull(store.getAllocatedValues());
	}

	@Test
	void proportionalAndMarginalAllocateCostsAndBalanceDistributorShare() {
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		MutableFreightCoalition proportionalCoalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("p-carrier", "a", "b");
		CollaborationDataStore proportionalStore =
			FreightCollaborationTestFixtures.emptyDataStore();
		proportionalStore.addSimulatedCoalitionScores(
			proportionalCoalition, game(0, a, 2, b, 6, 12));
		new AllocationModelProportional(proportionalStore, 0.5)
			.allocate(AllocationValueTypes.COST);
		assertEquals(1.5,
			proportionalStore.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(4.5,
			proportionalStore.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);
		assertEquals(6.0, proportionalStore.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("p-carrier", Object.class)), 1e-12);

		MutableFreightCoalition marginalCoalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("m-carrier", "a", "b");
		CollaborationDataStore marginalStore =
			FreightCollaborationTestFixtures.emptyDataStore();
		marginalStore.addSimulatedCoalitionScores(
			marginalCoalition, game(0, a, 1, b, 4, 10));
		new AllocationModelMarginalContribution(marginalStore, 0.8)
			.allocate(AllocationValueTypes.COST);
		assertEquals(3.2,
			marginalStore.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(4.8,
			marginalStore.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);
		assertEquals(2.0, marginalStore.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("m-carrier", Object.class)), 1e-12);
	}

	@Test
	void zeroNegativeAndIncompleteGamesHaveDeterministicBehavior() {
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b");

		CollaborationDataStore zeroCostStore =
			FreightCollaborationTestFixtures.emptyDataStore();
		zeroCostStore.addSimulatedCoalitionScores(
			coalition, game(0, a, -2, b, -4, 10));
		new AllocationModelProportional(zeroCostStore, 0.8)
			.allocate(AllocationValueTypes.COST);
		assertEquals(4.0,
			zeroCostStore.getAllocatedValue(CollaboratorRole.RECEIVER, a), 1e-12);
		assertEquals(4.0,
			zeroCostStore.getAllocatedValue(CollaboratorRole.RECEIVER, b), 1e-12);

		CollaborationDataStore negativeSavings =
			FreightCollaborationTestFixtures.emptyDataStore();
		negativeSavings.addSimulatedCoalitionScores(
			coalition, game(5, a, 4, b, 3, 1));
		new AllocationModelMarginalContribution(negativeSavings, 0.8)
			.allocate(AllocationValueTypes.COST_SAVINGS);
		assertTrue(negativeSavings.getAllocatedValues().values().stream()
			.allMatch(value -> value == 0.0));

		for (AllocationModel model : java.util.List.of(
			new AllocationModelProportional(missingBaselineStore(coalition, a, b), 1),
			new AllocationModelMarginalContribution(missingBaselineStore(coalition, a, b), 1))) {
			assertThrows(IllegalArgumentException.class,
				() -> model.allocate(AllocationValueTypes.COST_SAVINGS));
		}
	}

	@Test
	void modelsRejectNullStoresAndNonFiniteFactors() {
		assertThrows(NullPointerException.class,
			() -> new AllocationModelProportional(null, 1));
		assertThrows(NullPointerException.class,
			() -> new AllocationModelMarginalContribution(null, 1));
		assertThrows(IllegalArgumentException.class,
			() -> new AllocationModelProportional(
				FreightCollaborationTestFixtures.emptyDataStore(), Double.NaN));
		assertThrows(IllegalArgumentException.class,
			() -> new AllocationModelMarginalContribution(
				FreightCollaborationTestFixtures.emptyDataStore(),
				Double.POSITIVE_INFINITY));
	}

	private static CollaborationDataStore missingBaselineStore(
			MutableFreightCoalition coalition, Id<?> a, Id<?> b) {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(a), 1.0, Set.of(b), 2.0, Set.of(a, b), 4.0));
		return store;
	}

	private static Map<Set<Id<?>>, Double> game(
			double empty, Id<?> a, double aValue, Id<?> b, double bValue, double fullValue) {
		Map<Set<Id<?>>, Double> values = new LinkedHashMap<>();
		values.put(Set.of(), empty);
		values.put(Set.of(a), aValue);
		values.put(Set.of(b), bValue);
		values.put(Set.of(a, b), fullValue);
		return values;
	}
}
