package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.learning.MutableAfSelectionPolicy;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Injector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
		assertEquals(0.1, config.getMinAllocationFactor());
		assertEquals(0.05, config.getAllocationFactorStep());
		assertEquals(5, config.getMaxFactorPlans());
		assertEquals(6, config.getNewFactorMinDwell());
		assertEquals(5, config.getStabilityWindow());
		assertEquals(0.05, config.getStabilityRelativeTolerance());
		assertEquals(0.70, config.getCoalitionStabilityThreshold());
		assertEquals(MutableAfSelectionPolicy.CARRIER_BEST, config.getSolutionSelectionPolicy());
		assertEquals(3, config.getEvaluationWindow());
		assertEquals(100, options.lastIteration());
	}

	@Test
	void matsimInjectorAutomaticallyBindsTheMutableConfigModule() {
		MutableAllocationFactorConfigGroup mutableConfig =
			RunMutableAfCollabReceiverDistantCarrier.defaultOptions().createConfigGroup();
		Config config = ConfigUtils.createConfig(mutableConfig);

		com.google.inject.Injector injector = Injector.createInjector(config);

		assertSame(mutableConfig, injector.getInstance(MutableAllocationFactorConfigGroup.class));
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
				"--allocation-factor-freeze-fraction=0.75",
				"--allocation-factor-max-plans=4",
				"--allocation-factor-new-dwell=8",
				"--allocation-factor-revisit-dwell=4",
				"--allocation-factor-stability-window=2",
				"--allocation-factor-max-dwell=20",
				"--allocation-factor-evaluation-window=4",
				"--allocation-factor-stability-relative-tolerance=0.002",
				"--allocation-factor-coalition-stability-threshold=0.75",
				"--allocation-factor-selection-policy=receiver-best",
				"--allocation-factor-min-exploration-probability=0.2",
				"--allocation-factor-max-exploration-probability=0.7",
				"--allocation-factor-exploitation-beta=5.0",
				"--receiver-plans-per-factor=4",
				"--last-iteration=80"
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
		assertEquals(8, config.getNewFactorMinDwell());
		assertEquals(4, config.getRevisitFactorMinDwell());
		assertEquals(2, config.getStabilityWindow());
		assertEquals(20, config.getMaxAdaptDwell());
		assertEquals(4, config.getEvaluationWindow());
		assertEquals(0.002, config.getStabilityRelativeTolerance());
		assertEquals(0.75, config.getCoalitionStabilityThreshold());
		assertEquals(MutableAfSelectionPolicy.RECEIVER_BEST,
			config.getSolutionSelectionPolicy());
		assertEquals(0.2, config.getMinExplorationProbability());
		assertEquals(0.7, config.getMaxExplorationProbability());
		assertEquals(5.0, config.getExploitationBeta());
		assertEquals(4, config.getMaxReceiverPlansPerFactor());
		assertEquals(80, parsed.lastIteration());
	}

	@Test
	void parserRejectsOffGridAndUnknownOptions() {
		RunMutableAfCollabReceiverDistantCarrier.MutableOptions defaults =
			RunMutableAfCollabReceiverDistantCarrier.defaultOptions();
		assertThrows(IllegalArgumentException.class,
			() -> RunMutableAfCollabReceiverDistantCarrier.parseOptions(
				new String[]{"--initial-allocation-factor=0.83"}, defaults));
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
