package org.matsim.contrib.freightcollaboration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAllocationFactorConfigGroupTest {

	@Test
	void defaultsDescribeTheCompleteUnitGrid() {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();

		assertAll(
			() -> assertEquals(0.8, config.getInitialAllocationFactor()),
			() -> assertEquals(0.0, config.getMinAllocationFactor()),
			() -> assertEquals(1.0, config.getMaxAllocationFactor()),
			() -> assertEquals(0.1, config.getAllocationFactorStep()),
			() -> assertEquals(1.0, config.getMutationWeight()),
			() -> assertEquals(0.9, config.getDisableInnovationFraction()),
			() -> assertEquals(11, config.getMaxFactorPlans()),
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
		assertInvalid(config -> config.MAX_FACTOR_PLANS = 10);
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
				() -> config.setMaxFactorPlans(0)),
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
			() -> assertEquals(5, loaded.getMaxFactorPlans())
		);
	}

	private static void assertInvalid(java.util.function.Consumer<MutableAllocationFactorConfigGroup> mutation) {
		MutableAllocationFactorConfigGroup config = new MutableAllocationFactorConfigGroup();
		mutation.accept(config);
		assertThrows(IllegalArgumentException.class, config::validateGrid);
	}
}
