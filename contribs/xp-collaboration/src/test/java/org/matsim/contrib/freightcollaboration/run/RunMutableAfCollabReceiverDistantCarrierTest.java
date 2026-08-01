package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunMutableAfCollabReceiverDistantCarrierTest {

	@Test
	void defaultsUseAnIndependentOutputTreeAndExpectedFactorGrid() {
		RunMutableAfCollabReceiverDistantCarrier.MutableOptions options =
			RunMutableAfCollabReceiverDistantCarrier.defaultOptions();
		MutableAllocationFactorConfigGroup config = options.createConfigGroup();

		assertEquals(Path.of("output", "mutableAfCollabReceiverDistantCarrier"),
			options.experimentOptions().outputBaseDir());
		assertEquals(0.8, config.getInitialAllocationFactor());
		assertEquals(11, config.getMaxFactorPlans());
	}

	@Test
	void parserCombinesSharedAndMutableOptions(@TempDir Path directory) {
		RunMutableAfCollabReceiverDistantCarrier.MutableOptions parsed =
			RunMutableAfCollabReceiverDistantCarrier.parseOptions(new String[]{
				"--network-size=25",
				"--instances=2",
				"--output-base=" + directory,
				"--initial-allocation-factor=0.6",
				"--allocation-factor-min=0.2",
				"--allocation-factor-max=0.8",
				"--allocation-factor-step=0.2",
				"--allocation-factor-mutation-weight=2.5",
				"--allocation-factor-freeze-fraction=0.75"
			}, RunMutableAfCollabReceiverDistantCarrier.defaultOptions());

		assertEquals(25, parsed.experimentOptions().networkSize());
		assertEquals(2, parsed.experimentOptions().instanceCount());
		assertEquals(directory, parsed.experimentOptions().outputBaseDir());
		MutableAllocationFactorConfigGroup config = parsed.createConfigGroup();
		assertEquals(0.6, config.getInitialAllocationFactor());
		assertEquals(0.2, config.getMinAllocationFactor());
		assertEquals(0.8, config.getMaxAllocationFactor());
		assertEquals(0.2, config.getAllocationFactorStep());
		assertEquals(2.5, config.getMutationWeight());
		assertEquals(0.75, config.getDisableInnovationFraction());
		assertEquals(4, config.getMaxFactorPlans());
	}

	@Test
	void parserRejectsOffGridAndUnknownOptions() {
		RunMutableAfCollabReceiverDistantCarrier.MutableOptions defaults =
			RunMutableAfCollabReceiverDistantCarrier.defaultOptions();
		assertThrows(IllegalArgumentException.class,
			() -> RunMutableAfCollabReceiverDistantCarrier.parseOptions(
				new String[]{"--initial-allocation-factor=0.85"}, defaults));
		assertThrows(IllegalArgumentException.class,
			() -> RunMutableAfCollabReceiverDistantCarrier.parseOptions(
				new String[]{"--allocation-factor-step=NaN"}, defaults));
		assertThrows(IllegalArgumentException.class,
			() -> RunMutableAfCollabReceiverDistantCarrier.parseOptions(
				new String[]{"--mystery=1"}, defaults));
	}

	@Test
	void experimentRequiresAnExplicitCompletionMarker(@TempDir Path directory) throws IOException {
		assertFalse(RunMutableAfCollabReceiverDistantCarrier.isExperimentComplete(directory));
		Files.createFile(directory.resolve("output_config.xml"));
		assertFalse(RunMutableAfCollabReceiverDistantCarrier.isExperimentComplete(directory));
		Files.createFile(directory.resolve(RunMutableAfCollabReceiverDistantCarrier.EXPERIMENT_COMPLETE_MARKER));
		assertTrue(RunMutableAfCollabReceiverDistantCarrier.isExperimentComplete(directory));
	}
}
