package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FreightCollaborationEngineTest {

	@Test
	void allAllocationModelsRunThroughTheEngineAndCloseTheirExecutor() {
		for (AllocationModels model : AllocationModels.values()) {
			Fixture fixture = fixture(model);
			List<Set<Id<?>>> evaluated = new ArrayList<>();
			ExecutorService executor = Executors.newFixedThreadPool(2);
			FreightPseudoSimulator simulator = new FreightPseudoSimulator((distributors, players, subset) -> {
				evaluated.add(Set.copyOf(subset));
				return subset.isEmpty() ? 10.0 : 10.0 - subset.size();
			});
			FreightCollaborationEngine engine = new FreightCollaborationEngine(
				fixture.config, fixture.scenario, fixture.collaborators, fixture.store,
				List.of(fixture.coalition), null, null, () -> simulator, ignored -> executor);

			engine.runCollaboration();

			assertTrue(executor.isShutdown(), model.name());
			assertFalse(evaluated.isEmpty(), model.name());
			assertFalse(fixture.store.getAllocatedValues().isEmpty(), model.name());
			Set<Set<Id<?>>> uniqueEvaluations = new LinkedHashSet<>(evaluated);
			if (model == AllocationModels.SHAPLEY) {
				assertEquals(4, uniqueEvaluations.size());
			} else if (model == AllocationModels.PROPORTIONAL) {
				assertEquals(Set.of(Set.of(), Set.of(id("r1")), Set.of(id("r2")),
					Set.of(id("r1"), id("r2"))), uniqueEvaluations);
			} else if (model == AllocationModels.MARGINAL) {
				assertEquals(Set.of(Set.of(), Set.of(id("r1")), Set.of(id("r2")),
					Set.of(id("r1"), id("r2"))), uniqueEvaluations);
			}
		}
	}

	@Test
	void noCoalitionsSkipExecutorAndFailuresStillShutItDown() {
		Fixture fixture = fixture(AllocationModels.SHAPLEY);
		AtomicInteger executorCalls = new AtomicInteger();
		FreightCollaborationEngine empty = new FreightCollaborationEngine(
			fixture.config, fixture.scenario, fixture.collaborators, fixture.store,
			List.of(), null, null,
			() -> new FreightPseudoSimulator((d, p, s) -> 0.0),
			ignored -> {
				executorCalls.incrementAndGet();
				return Executors.newSingleThreadExecutor();
			});
		empty.runCollaboration();
		assertEquals(0, executorCalls.get());

		ExecutorService executor = Executors.newSingleThreadExecutor();
		FreightCollaborationEngine failing = new FreightCollaborationEngine(
			fixture.config, fixture.scenario, fixture.collaborators, fixture.store,
			List.of(fixture.coalition), null, null,
			() -> {
				throw new IllegalStateException("factory failed");
			},
			ignored -> executor);
		RuntimeException failure = assertThrows(RuntimeException.class, failing::runCollaboration);
		assertTrue(failure.getMessage().contains("Error during freight collaboration"));
		assertTrue(executor.isShutdown());
	}

	@Test
	void engineRejectsNullExecutorAndCoalitionBuildersRejectNull() {
		Fixture fixture = fixture(AllocationModels.SHAPLEY);
		FreightCollaborationEngine engine = new FreightCollaborationEngine(
			fixture.config, fixture.scenario, fixture.collaborators, fixture.store,
			List.of(fixture.coalition), null, null,
			() -> new FreightPseudoSimulator((d, p, s) -> 0.0),
			ignored -> null);
		assertThrows(NullPointerException.class, engine::runCollaboration);
		assertAll(
			() -> assertThrows(NullPointerException.class,
				() -> FreightCollaborationEngine.buildSubCoalitionsForProportional(null)),
			() -> assertThrows(NullPointerException.class,
				() -> FreightCollaborationEngine.buildSubCoalitionsForMarginal(null))
		);
	}

	private static Fixture fixture(AllocationModels model) {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		group.ALLOCATION_MODEL = model;
		group.setParallelism(2);
		group.setMonteCarloSamples(4);
		group.setSamplesRatio(1.0);
		Config config = ConfigUtils.createConfig(group);
		Scenario scenario = ScenarioUtils.createScenario(config);
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "r1", "r2");
		FreightCollaborators collaborators =
			new FreightCollaborators(coalition.getCollaboratorsSet());
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.setScenario(scenario);
		return new Fixture(config, scenario, coalition, collaborators, store);
	}

	private static Id<?> id(String value) {
		return Id.create(value, Object.class);
	}

	private record Fixture(Config config, Scenario scenario, MutableFreightCoalition coalition,
						   FreightCollaborators collaborators, CollaborationDataStore store) {
	}
}
