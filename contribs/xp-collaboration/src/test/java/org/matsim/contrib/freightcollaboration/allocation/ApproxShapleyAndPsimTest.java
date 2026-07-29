package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApproxShapleyAndPsimTest {

	@Test
	void monteCarloIsDeterministicPreservesNegativeMarginalsAndBalancesBudget() {
		CollaborationDataStore first = FreightCollaborationTestFixtures.emptyDataStore();
		CollaborationDataStore second = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b");

		runApprox(first, coalition, AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO);
		runApprox(second, coalition, AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO);

		assertEquals(first.getAllocatedValues(), second.getAllocatedValues());
		assertEquals(-2.0, first.getAllocatedValue(CollaboratorRole.RECEIVER,
			Id.create("a", Object.class)), 1e-12);
		assertEquals(3.0, first.getAllocatedValue(CollaboratorRole.RECEIVER,
			Id.create("b", Object.class)), 1e-12);
		assertEquals(0.0, first.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-12);
		assertEquals(1.0, first.getAllocatedValues().values().stream()
			.mapToDouble(Double::doubleValue).sum(), 1e-12);
	}

	@Test
	void stratifiedCorrectsEfficiencyToGrandCoalitionValue() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "a", "b", "c");
		AtomicInteger evaluations = new AtomicInteger();

		AllocationModelApproxShapleyValue model = new AllocationModelApproxShapleyValue(
			store,
			() -> new FreightPseudoSimulator((distributors, players, subset) -> {
				evaluations.incrementAndGet();
				return subset.size() * subset.size();
			}),
			List.of(coalition), 0.8, null, 1);
		model.setApproximationMethod(AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED);
		model.setStratifiedSamplesPerLevel(2);
		model.setMaxStratifiedEvaluations(5);
		model.setSamplesRatio(1.0);
		model.setRandomSeed(9);
		model.allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(9.0, store.getAllocatedValues().values().stream()
			.mapToDouble(Double::doubleValue).sum(), 1e-9);
		assertEquals(1.8, store.getAllocatedValue(CollaboratorRole.CARRIER,
			Id.create("carrier", Object.class)), 1e-9);
		assertTrue(evaluations.get() <= 5, "The stratified evaluation cap must include baseline and grand coalition");
		assertTrue(store.getSimulatedCoalitionScores().get(coalition).size() <= 5);
	}

	@Test
	void approximateConfigurationRejectsInvalidValues() {
		AllocationModelApproxShapleyValue model = new AllocationModelApproxShapleyValue(
			FreightCollaborationTestFixtures.emptyDataStore(),
			() -> new FreightPseudoSimulator((d, p, s) -> 0),
			List.of(), 1, null, 1);
		assertAll(
			() -> assertThrows(NullPointerException.class, () -> model.setApproximationMethod(null)),
			() -> assertThrows(IllegalArgumentException.class, () -> model.setMonteCarloSamples(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> model.setSamplesRatio(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> model.setSamplesRatio(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class, () -> model.setStratifiedSamplesPerLevel(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> model.setMaxStratifiedEvaluations(1))
		);
	}

	@Test
	void parallelTaskFailureAndInterruptionArePropagated() {
		MutableFreightCoalition first =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("c1", "r1");
		MutableFreightCoalition second =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("c2", "r2");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			AllocationModelApproxShapleyValue failing = new AllocationModelApproxShapleyValue(
				FreightCollaborationTestFixtures.emptyDataStore(),
				() -> {
					throw new IllegalStateException("simulator factory failed");
				},
				List.of(first, second), 1.0, executor, 2);
			RuntimeException failure = assertThrows(RuntimeException.class,
				() -> failing.allocate(AllocationValueTypes.COST_SAVINGS));
			assertTrue(failure.getMessage().contains("Failed to compute coalition allocation"));

			AllocationModelApproxShapleyValue interrupted = new AllocationModelApproxShapleyValue(
				FreightCollaborationTestFixtures.emptyDataStore(),
				() -> new FreightPseudoSimulator((d, p, s) -> 0.0),
				List.of(first, second), 1.0, executor, 2);
			Thread.currentThread().interrupt();
			RuntimeException interruptedFailure = assertThrows(RuntimeException.class,
				() -> interrupted.allocate(AllocationValueTypes.COST_SAVINGS));
			assertTrue(interruptedFailure.getMessage().contains("Interrupted"));
			assertTrue(Thread.currentThread().isInterrupted());
		} finally {
			Thread.interrupted();
			executor.shutdownNow();
		}
	}

	@Test
	void pseudoSimulatorEvaluatesEveryRequestedSubsetOnceInStableOrder() {
		List<Set<Id<?>>> calls = new ArrayList<>();
		FreightPseudoSimulator simulator = new FreightPseudoSimulator((distributors, players, subset) -> {
			calls.add(subset);
			return subset.size();
		});
		Map<Id<?>, FreightCollaborator<?>> players = new LinkedHashMap<>();
		for (String id : List.of("a", "b", "c")) {
			players.put(Id.create(id, Object.class),
				FreightCollaborationTestFixtures.receiverCollaborator(id));
		}

		Map<Set<Id<?>>, Double> result = simulator.runAllSubCoalitions(Map.of(), players);

		assertEquals(8, result.size());
		assertEquals(8, calls.size());
		assertEquals(0.0, result.get(Set.of()));
		assertThrows(IllegalArgumentException.class, () -> {
			Map<Id<?>, FreightCollaborator<?>> tooMany = new LinkedHashMap<>();
			for (int i = 0; i < 13; i++) {
				tooMany.put(Id.create("p" + i, Object.class),
					FreightCollaborationTestFixtures.receiverCollaborator("p" + i));
			}
			simulator.runAllSubCoalitions(Map.of(), tooMany);
		});
	}

	@Test
	void engineBuildsOnlyCoalitionsRequiredByEachModel() {
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		Collection<Set<Id<?>>> proportional =
			FreightCollaborationEngine.buildSubCoalitionsForProportional(Set.of(a, b));
		assertEquals(Set.of(Set.of(), Set.of(a), Set.of(b), Set.of(a, b)), Set.copyOf(proportional));

		Collection<Set<Id<?>>> marginal =
			FreightCollaborationEngine.buildSubCoalitionsForMarginal(Set.of(a, b));
		assertEquals(Set.of(Set.of(), Set.of(a), Set.of(b), Set.of(a, b)), Set.copyOf(marginal));
	}

	private static void runApprox(CollaborationDataStore store, MutableFreightCoalition coalition,
			AllocationModelApproxShapleyValue.ApproximationMethod method) {
		AllocationModelApproxShapleyValue model = new AllocationModelApproxShapleyValue(
			store,
			() -> new FreightPseudoSimulator((distributors, players, subset) -> {
				double value = 0;
				for (Id<?> id : subset) {
					value += id.toString().equals("a") ? -2 : 3;
				}
				return value;
			}),
			List.of(coalition), 1.0, null, 1);
		model.setApproximationMethod(method);
		model.setMonteCarloSamples(20);
		model.setSamplesRatio(1.0);
		model.setRandomSeed(42);
		model.allocate(AllocationValueTypes.COST_SAVINGS);
	}
}
