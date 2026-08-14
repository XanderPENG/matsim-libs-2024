package org.matsim.contrib.freightcollaboration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.contrib.freightcollaboration.learning.MutableAfSelectionPolicy;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAllocationFactorConfigGroupTest {

	@Test
	void defaultsDescribeTheBoundedLearningPolicy() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();

		assertAll(
			() -> assertEquals(0.8, config.getInitialAllocationFactor()),
			() -> assertEquals(0.0, config.getMinAllocationFactor()),
			() -> assertEquals(1.0, config.getMaxAllocationFactor()),
			() -> assertEquals(0.1, config.getAllocationFactorStep()),
			() -> assertEquals(1.0, config.getMutationWeight()),
			() -> assertEquals(0.9, config.getDisableInnovationFraction()),
			() -> assertEquals(5, config.getMaxFactorPlans()),
			() -> assertEquals(6, config.getNewFactorMinDwell()),
			() -> assertEquals(3, config.getRevisitFactorMinDwell()),
			() -> assertEquals(5, config.getStabilityWindow()),
			() -> assertEquals(15, config.getMaxAdaptDwell()),
			() -> assertEquals(3, config.getEvaluationWindow()),
			() -> assertEquals(0.05, config.getStabilityRelativeTolerance()),
			() -> assertEquals(0.70, config.getCoalitionStabilityThreshold()),
			() -> assertEquals(MutableAfSelectionPolicy.CARRIER_BEST,
				config.getSolutionSelectionPolicy()),
			() -> assertEquals(1e-6, config.getParticipationRelativeTolerance()),
			() -> assertEquals(0.10, config.getMinExplorationProbability()),
			() -> assertEquals(0.80, config.getMaxExplorationProbability()),
			() -> assertEquals(4.0, config.getExploitationBeta()),
			() -> assertEquals(5, config.getMaxReceiverPlansPerFactor()),
			() -> assertEquals(11, config.gridPointCount()),
			() -> assertEquals(0.8, config.valueAt(8)),
			() -> assertEquals(8, config.indexOf(0.8)),
			() -> assertTrue(config.isOnGrid(0.3)),
			() -> assertFalse(config.isOnGrid(0.31))
		);
	}

	@Test
	void validationRejectsInvalidBoundsGridAndControls() {
		assertInvalid(config -> config.MIN_ALLOCATION_FACTOR = -0.1);
		assertInvalid(config -> config.MAX_ALLOCATION_FACTOR = 1.1);
		assertInvalid(config -> config.MIN_ALLOCATION_FACTOR = config.MAX_ALLOCATION_FACTOR);
		assertInvalid(config -> config.ALLOCATION_FACTOR_STEP = 0.0);
		assertInvalid(config -> config.ALLOCATION_FACTOR_STEP = 0.3);
		assertInvalid(config -> config.INITIAL_ALLOCATION_FACTOR = 0.85);
		assertInvalid(config -> config.MUTATION_WEIGHT = 0.0);
		assertInvalid(config -> config.DISABLE_INNOVATION_FRACTION = 0.0);
		assertInvalid(config -> config.DISABLE_INNOVATION_FRACTION = 1.1);
		assertInvalid(config -> config.MAX_FACTOR_PLANS = 1);
		assertInvalid(config -> config.MAX_FACTOR_PLANS = 12);
		assertInvalid(config -> config.NEW_FACTOR_MIN_DWELL = 16);
		assertInvalid(config -> config.REVISIT_FACTOR_MIN_DWELL = 16);
		assertInvalid(config -> config.MAX_ADAPT_DWELL = 5);
		assertInvalid(config -> config.STABILITY_WINDOW = 0);
		assertInvalid(config -> config.EVALUATION_WINDOW = 0);
		assertInvalid(config -> config.STABILITY_RELATIVE_TOLERANCE = -1.0);
		assertInvalid(config -> config.COALITION_STABILITY_THRESHOLD = -0.1);
		assertInvalid(config -> config.COALITION_STABILITY_THRESHOLD = 1.1);
		assertInvalid(config -> config.MIN_EXPLORATION_PROBABILITY = 0.9);
		assertInvalid(config -> config.MAX_EXPLORATION_PROBABILITY = 0.05);
		assertInvalid(config -> config.EXPLOITATION_BETA = 0.0);
		assertInvalid(config -> config.MAX_RECEIVER_PLANS_PER_FACTOR = 0);
		assertInvalid(config -> config.SOLUTION_SELECTION_POLICY = "unknown");
		assertInvalid(config -> config.INITIAL_ALLOCATION_FACTOR = Double.NaN);

		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		assertThrows(IllegalArgumentException.class, () -> config.valueAt(-1));
		assertThrows(IllegalArgumentException.class, () -> config.valueAt(11));
		assertThrows(IllegalArgumentException.class, () -> config.indexOf(0.35));
	}

	@Test
	void settersAndGridPredicatesRejectEveryInvalidBoundary() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();

		assertAll(
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMinAllocationFactor(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMinAllocationFactor(-0.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMinAllocationFactor(1.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMaxAllocationFactor(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMaxAllocationFactor(1.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMaxAllocationFactor(0.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setAllocationFactorStep(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setAllocationFactorStep(0.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMutationWeight(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMutationWeight(0.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setDisableInnovationFraction(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setDisableInnovationFraction(0.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setDisableInnovationFraction(1.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMaxFactorPlans(1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setNewFactorMinDwell(0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setRevisitFactorMinDwell(0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setStabilityRelativeTolerance(-1.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setCoalitionStabilityThreshold(-0.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setCoalitionStabilityThreshold(1.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setMinExplorationProbability(1.1)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setExploitationBeta(0.0)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> config.setInitialAllocationFactor(1.1)),
			() -> assertFalse(config.isOnGrid(Double.NaN)),
			() -> assertFalse(config.isOnGrid(-0.1)),
			() -> assertFalse(config.isOnGrid(1.1))
		);
	}

	@Test
	void xmlRoundTripPreservesOptInSettings(@TempDir Path directory) {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		config.MIN_ALLOCATION_FACTOR = 0.2;
		config.MAX_ALLOCATION_FACTOR = 0.8;
		config.ALLOCATION_FACTOR_STEP = 0.15;
		config.INITIAL_ALLOCATION_FACTOR = 0.65;
		config.MUTATION_WEIGHT = 2.5;
		config.DISABLE_INNOVATION_FRACTION = 0.75;
		config.MAX_FACTOR_PLANS = 5;
		config.NEW_FACTOR_MIN_DWELL = 8;
		config.REVISIT_FACTOR_MIN_DWELL = 4;
		config.STABILITY_WINDOW = 2;
		config.MAX_ADAPT_DWELL = 20;
		config.EVALUATION_WINDOW = 4;
		config.STABILITY_RELATIVE_TOLERANCE = 2e-3;
		config.COALITION_STABILITY_THRESHOLD = 0.75;
		config.SOLUTION_SELECTION_POLICY = "best-surplus";
		config.PARTICIPATION_RELATIVE_TOLERANCE = 2e-6;
		config.MIN_EXPLORATION_PROBABILITY = 0.2;
		config.MAX_EXPLORATION_PROBABILITY = 0.7;
		config.EXPLOITATION_BETA = 5.0;
		config.MAX_RECEIVER_PLANS_PER_FACTOR = 4;
		config.validateGrid();

		Config matsimConfig = ConfigUtils.createConfig(config);
		Path file = directory.resolve("config.xml");
		new ConfigWriter(matsimConfig).write(file.toString());

		MutableAllocationFactorConfigGroup loaded = new MutableAllocationFactorConfigGroup();
		ConfigUtils.loadConfig(file.toString(), loaded);
		loaded.validateGrid();
		assertAll(
			() -> assertEquals(0.2, loaded.getMinAllocationFactor()),
			() -> assertEquals(0.8, loaded.getMaxAllocationFactor()),
			() -> assertEquals(0.15, loaded.getAllocationFactorStep()),
			() -> assertEquals(0.65, loaded.getInitialAllocationFactor()),
			() -> assertEquals(2.5, loaded.getMutationWeight()),
			() -> assertEquals(0.75, loaded.getDisableInnovationFraction()),
			() -> assertEquals(5, loaded.getMaxFactorPlans()),
			() -> assertEquals(8, loaded.getNewFactorMinDwell()),
			() -> assertEquals(4, loaded.getRevisitFactorMinDwell()),
			() -> assertEquals(2, loaded.getStabilityWindow()),
			() -> assertEquals(20, loaded.getMaxAdaptDwell()),
			() -> assertEquals(4, loaded.getEvaluationWindow()),
			() -> assertEquals(2e-3, loaded.getStabilityRelativeTolerance()),
			() -> assertEquals(0.75, loaded.getCoalitionStabilityThreshold()),
			() -> assertEquals(MutableAfSelectionPolicy.BEST_SURPLUS,
				loaded.getSolutionSelectionPolicy()),
			() -> assertEquals(2e-6, loaded.getParticipationRelativeTolerance()),
			() -> assertEquals(0.2, loaded.getMinExplorationProbability()),
			() -> assertEquals(0.7, loaded.getMaxExplorationProbability()),
			() -> assertEquals(5.0, loaded.getExploitationBeta()),
			() -> assertEquals(4, loaded.getMaxReceiverPlansPerFactor())
		);
	}

	@Test
	void finalizationLeavesEnoughTimeForTheFirstValidatedFactor() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();

		assertEquals(90, config.finalizationIteration(0, 100));
		assertEquals(9, config.finalizationIteration(0, 10));
		assertThrows(IllegalArgumentException.class, () -> config.finalizationIteration(0, 6));
		assertThrows(IllegalArgumentException.class, () -> config.finalizationIteration(10, 10));
	}

	private static void assertInvalid(java.util.function.Consumer<MutableAllocationFactorConfigGroup> mutation) {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		mutation.accept(config);
		assertThrows(IllegalArgumentException.class, config::validateGrid);
	}
}
