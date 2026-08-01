package org.matsim.contrib.freightcollaboration.strategy;

import com.google.inject.Guice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.controller.CarrierStrategyManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CarrierAllocationFactorStrategyTest {

	@AfterEach
	void resetRandom() {
		MatsimRandom.reset();
	}

	@Test
	void accessorValidatesAttributesAndConfiguredGrid() {
		CarrierPlan plan = carrierWithPlan("carrier").getSelectedPlan();
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();

		assertFalse(CarrierAllocationFactor.find(plan).isPresent());
		CarrierAllocationFactor.set(plan, 0.8, config);
		assertEquals(0.8, CarrierAllocationFactor.require(plan));
		assertEquals(0.8, CarrierAllocationFactor.require(plan, config));
		assertThrows(IllegalArgumentException.class, () -> CarrierAllocationFactor.set(plan, -0.1));
		assertThrows(IllegalArgumentException.class, () -> CarrierAllocationFactor.set(plan, Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> CarrierAllocationFactor.set(plan, 0.85, config));
		plan.getAttributes().putAttribute(CarrierAllocationFactor.ATTRIBUTE_NAME, "0.8");
		assertThrows(IllegalStateException.class, () -> CarrierAllocationFactor.require(plan));
	}

	@Test
	void mutationCopiesAllAttributesAndMovesOnlyOneGridStep() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		Carrier carrier = carrierWithPlan("carrier");
		CarrierPlan parent = carrier.getSelectedPlan();
		parent.setScore(12.0);
		parent.setJspritScore(34.0);
		parent.getAttributes().putAttribute("marker", "preserved");
		CarrierAllocationFactor.set(parent, 0.5, config);

		MatsimRandom.reset(12345L);
		CarrierAllocationFactorPlanStrategy strategy = new CarrierAllocationFactorPlanStrategy(
			owner -> owner.getSelectedPlan(), config);
		strategy.run(carrier);

		CarrierPlan mutated = carrier.getSelectedPlan();
		assertAll(
			() -> assertNotSame(parent, mutated),
			() -> assertEquals(2, carrier.getPlans().size()),
			() -> assertNull(mutated.getScore()),
			() -> assertEquals("preserved", mutated.getAttributes().getAttribute("marker")),
			() -> assertEquals(0.1,
				Math.abs(CarrierAllocationFactor.require(mutated) - CarrierAllocationFactor.require(parent)), 1e-12),
			() -> assertEquals(0.5, CarrierAllocationFactor.require(parent)),
			() -> assertEquals(12.0, parent.getScore())
		);
	}

	@Test
	void mutationReflectsAtGridBoundsAndIsSeedReproducible() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		Carrier lower = carrierWithPlan("lower");
		lower.getSelectedPlan().setScore(0.0);
		CarrierAllocationFactor.set(lower.getSelectedPlan(), 0.0, config);
		new CarrierAllocationFactorPlanStrategy(owner -> owner.getSelectedPlan(), config).run(lower);
		assertEquals(0.1, CarrierAllocationFactor.require(lower.getSelectedPlan()));

		Carrier upper = carrierWithPlan("upper");
		upper.getSelectedPlan().setScore(0.0);
		CarrierAllocationFactor.set(upper.getSelectedPlan(), 1.0, config);
		new CarrierAllocationFactorPlanStrategy(owner -> owner.getSelectedPlan(), config).run(upper);
		assertEquals(0.9, CarrierAllocationFactor.require(upper.getSelectedPlan()));

		assertEquals(interiorMutation(77L), interiorMutation(77L));
	}

	@Test
	void removalPrefersInferiorDuplicateFactorAndNeverSelectedPlan() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		Carrier carrier = carrierWithPlan("carrier");
		CarrierPlan selected = carrier.getSelectedPlan();
		selected.setScore(100.0);
		CarrierAllocationFactor.set(selected, 0.2, config);
		CarrierPlan duplicateBetter = addPlan(carrier, 0.4, 5.0, config);
		CarrierPlan duplicateWorse = addPlan(carrier, 0.4, 1.0, config);
		CarrierPlan globalWorst = addPlan(carrier, 0.6, -100.0, config);
		carrier.setSelectedPlan(selected);

		CarrierAllocationFactorPlanRemovalSelector selector =
			new CarrierAllocationFactorPlanRemovalSelector();
		assertSame(duplicateWorse, selector.selectPlan(carrier));

		carrier.removePlan(duplicateWorse);
		CarrierPlan selectedFactorDuplicate = addPlan(carrier, 0.2, 50.0, config);
		assertSame(selectedFactorDuplicate, selector.selectPlan(carrier));
		carrier.removePlan(selectedFactorDuplicate);
		assertSame(globalWorst, selector.selectPlan(carrier));
		assertSame(selected, carrier.getSelectedPlan());
		assertEquals(5.0, duplicateBetter.getScore());
	}

	@Test
	void removalRefusesToDiscardUnscoredOrSelectedPlans() {
		Carrier carrier = carrierWithPlan("carrier");
		CarrierPlan selected = carrier.getSelectedPlan();
		selected.setScore(1.0);
		carrier.addPlan(new CarrierPlan(carrier, List.of()));
		carrier.setSelectedPlan(selected);

		assertThrows(IllegalStateException.class,
			() -> new CarrierAllocationFactorPlanRemovalSelector().selectPlan(carrier));
		assertSame(selected, carrier.getSelectedPlan());
	}

	@Test
	void providerStartsAfterBaselineAndFreezesToBestSelectionAtNinetyPercent() {
		Config config = ConfigUtils.createConfig();
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(10);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = carrierWithPlan("carrier");
		carrier.getSelectedPlan().setScore(1.0);
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		MutableAfCarrierStrategyManagerProvider provider =
			new MutableAfCarrierStrategyManagerProvider(mutableConfig);
		Guice.createInjector(new com.google.inject.AbstractModule() {
			@Override
			protected void configure() {
				bind(Scenario.class).toInstance(scenario);
			}
		}).injectMembers(provider);

		CarrierStrategyManager manager = provider.get();
		assertEquals(List.of(1.0, 0.0, 0.0), manager.getWeights(null));
		assertEquals(0.8, CarrierAllocationFactor.require(carrier.getSelectedPlan()));

		manager.run(List.<Carrier>of(), 1, null);
		assertEquals(List.of(1.0, 1.0, 0.0), manager.getWeights(null));
		manager.run(List.<Carrier>of(), 9, null);
		assertEquals(List.of(0.0, 0.0, 1.0), manager.getWeights(null));
	}

	@Test
	void providerAllowsIterationZeroVrpListenerToCreateTheInitialPlan() {
		Config config = ConfigUtils.createConfig();
		config.controller().setLastIteration(10);
		Scenario scenario = ScenarioUtils.createScenario(config);
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		CarriersUtils.addOrGetCarriers(scenario).addCarrier(carrier);
		MutableAfCarrierStrategyManagerProvider provider =
			new MutableAfCarrierStrategyManagerProvider(new MutableAllocationFactorConfigGroup());
		Guice.createInjector(new com.google.inject.AbstractModule() {
			@Override
			protected void configure() {
				bind(Scenario.class).toInstance(scenario);
			}
		}).injectMembers(provider);

		CarrierStrategyManager manager = provider.get();
		assertEquals(List.of(1.0, 0.0, 0.0), manager.getWeights(null));
		assertEquals(0, carrier.getPlans().size());
	}

	private static double interiorMutation(long seed) {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		Carrier carrier = carrierWithPlan("seed-" + seed);
		carrier.getSelectedPlan().setScore(0.0);
		CarrierAllocationFactor.set(carrier.getSelectedPlan(), 0.5, config);
		MatsimRandom.reset(seed);
		new CarrierAllocationFactorPlanStrategy(owner -> owner.getSelectedPlan(), config).run(carrier);
		return CarrierAllocationFactor.require(carrier.getSelectedPlan());
	}

	private static Carrier carrierWithPlan(String id) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
		CarrierPlan plan = new CarrierPlan(carrier, List.of());
		carrier.addPlan(plan);
		carrier.setSelectedPlan(plan);
		return carrier;
	}

	private static CarrierPlan addPlan(Carrier carrier, double factor, double score,
			MutableAllocationFactorConfigGroup config) {
		CarrierPlan plan = new CarrierPlan(carrier, List.of());
		CarrierAllocationFactor.set(plan, factor, config);
		plan.setScore(score);
		carrier.addPlan(plan);
		return plan;
	}
}
