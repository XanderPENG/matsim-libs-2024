package org.matsim.contrib.freightcollaboration.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FreightCollaborationConfigGroupTest {

	@TempDir
	Path tempDirectory;

	@Test
	void defaultsAndValidatedSettersHaveStableContract() {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();

		assertAll(
			() -> assertEquals(AllocationModels.APPROX_SHAPLEY, group.ALLOCATION_MODEL),
			() -> assertEquals(AllocationValueTypes.COST_SAVINGS, group.getAllocationStrategy()),
			() -> assertEquals(12, group.getMaxExactShapleyPlayers()),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setVrpMaxIterations(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setParallelism(-1)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setAllocationFactor(-0.01)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setAllocationFactor(1.01)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setAllocationFactor(Double.NaN)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setMonteCarloSamples(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setSamplesRatio(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setSamplesRatio(1.01)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setSamplesRatio(Double.POSITIVE_INFINITY)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setStratifiedSamplesPerLevel(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setMaxStratifiedEvaluations(1)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setMaxStratifiedEvaluations(0)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setMaxExactShapleyPlayers(-1)),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setMaxExactShapleyPlayers(31))
		);

		group.setVrpMaxIterations(11);
		group.setParallelism(3);
		group.setMonteCarloSamples(12);
		group.setSamplesRatio(0.5);
		group.setStratifiedSamplesPerLevel(4);
		group.setMaxStratifiedEvaluations(20);
		group.setMaxExactShapleyPlayers(0);
		group.setParallelism(0);
		assertAll(
			() -> assertEquals(11, group.getVrpMaxIterations()),
			() -> assertTrue(group.getParallelism() > 0),
			() -> assertEquals(12, group.getMonteCarloSamples()),
			() -> assertEquals(0.5, group.getSamplesRatio()),
			() -> assertEquals(4, group.getStratifiedSamplesPerLevel()),
			() -> assertEquals(20, group.getMaxStratifiedEvaluations()),
			() -> assertEquals(0, group.getMaxExactShapleyPlayers())
		);
		group.setAllocationFactor(0.0);
		assertEquals(0.0, group.getAllocationFactor());
		group.setAllocationFactor(1.0);
		assertEquals(1.0, group.getAllocationFactor());
	}

	@Test
	void enumParsersRejectTyposAndAcceptEverySupportedValue() {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		for (AllocationModels model : AllocationModels.values()) {
			group.setAllocationModelString(model.name());
			assertEquals(model.name(), group.getAllocationModelString());
		}
		for (AllocationValueTypes strategy : AllocationValueTypes.values()) {
			group.setAllocationStrategyString(strategy.name());
			assertEquals(strategy.name(), group.getAllocationStrategyString());
		}
		for (FreightCollaborationConfigGroup.PsimScoringMode mode
				: FreightCollaborationConfigGroup.PsimScoringMode.values()) {
			group.setPsimScoringModeString(mode.name());
			assertEquals(mode.name(), group.getPsimScoringModeString());
		}
		for (FreightCollaborationConfigGroup.Iter0BaselineMode mode
				: FreightCollaborationConfigGroup.Iter0BaselineMode.values()) {
			group.setIter0BaselineModeString(mode.name());
			assertEquals(mode.name(), group.getIter0BaselineModeString());
		}
		for (AllocationModelApproxShapleyValue.ApproximationMethod method
				: AllocationModelApproxShapleyValue.ApproximationMethod.values()) {
			group.setApproxShapleyMethod(method.name());
			assertEquals(method.name(), group.getApproxShapleyMethod());
		}

		assertAll(
			() -> assertThrows(IllegalArgumentException.class, () -> group.setAllocationModelString("bad")),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setAllocationStrategyString("bad")),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setPsimScoringModeString("bad")),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setIter0BaselineModeString("bad")),
			() -> assertThrows(IllegalArgumentException.class, () -> group.setApproxShapleyMethod("bad"))
		);
		group.setApproxShapleyMethod(null);
		assertEquals(AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO.name(),
			group.getApproxShapleyMethod());
		group.setApproxShapleyMethod(" ");
		assertEquals(AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO.name(),
			group.getApproxShapleyMethod());
	}

	@Test
	void paramSetParsesDeterministicallyAndDefensively() {
		CollaborationParamSet params = new CollaborationParamSet();
		params.setCollaborationTypeString(" CARRIER_RECEIVER ");
		params.setCollaborationStrategiesString(
			"RECEIVER_TIME_WINDOW_MUTATION, COLLABORATION_STATUS_MUTATION");
		params.setAdditionalParamsString("alpha=a|b; beta=c");

		assertEquals(CollaborationTypes.CARRIER_RECEIVER, params.getCollaborationType());
		assertEquals(Set.of(
			CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION,
			CollaborationStrategies.COLLABORATION_STATUS_MUTATION),
			params.getCollaborationStrategies());
		assertEquals(Set.of("a", "b"), params.getAdditionalParams().get("alpha"));
		assertThrows(UnsupportedOperationException.class, () -> params.getAdditionalParams().clear());
		assertThrows(IllegalArgumentException.class, () -> params.setAdditionalParamsString("missingEquals"));
		assertThrows(IllegalArgumentException.class, () -> params.setAdditionalParamsString("=value"));
		assertThrows(IllegalArgumentException.class, () -> params.setAdditionalParamsString("a=1;a=2"));
		assertThrows(IllegalArgumentException.class, () -> params.setCollaborationTypeString("bad"));
		assertThrows(IllegalArgumentException.class, () -> params.setCollaborationStrategiesString("bad"));

		Map<String, Set<String>> mutable = new LinkedHashMap<>();
		Set<String> values = new LinkedHashSet<>(Set.of("x"));
		mutable.put("key", values);
		params.setAdditionalParams(mutable);
		values.add("later");
		assertEquals(Set.of("x"), params.getAdditionalParams().get("key"));

		params.setCollaborationTypeString(null);
		params.setCollaborationStrategiesString(" ");
		params.setAdditionalParamsString(" ");
		assertAll(
			() -> assertNull(params.getCollaborationType()),
			() -> assertEquals("", params.getCollaborationTypeString()),
			() -> assertEquals("", params.getCollaborationStrategiesString()),
			() -> assertEquals("", params.getAdditionalParamsString())
		);
		params.setCollaborationStrategies(null);
		params.setAdditionalParams(null);
		assertTrue(params.getCollaborationStrategies().isEmpty());
		assertTrue(params.getAdditionalParams().isEmpty());
		Map<String, Set<String>> withNullValue = new java.util.HashMap<>();
		withNullValue.put("nullable", null);
		params.setAdditionalParams(withNullValue);
		assertEquals(Set.of(), params.getAdditionalParams().get("nullable"));
	}

	@Test
	void parameterSetContainerRejectsUnknownTypesAndDoesNotLeakItsSet() {
		CollaborationParamSet params = new CollaborationParamSet(
			CollaborationTypes.CARRIER_RECEIVER, Set.of());
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup(
			Set.of(params), "network.xml");

		assertAll(
			() -> assertEquals("network.xml", group.INPUT_NETWORK_FILE),
			() -> assertThrows(IllegalArgumentException.class, () -> group.createParameterSet("unknown")),
			() -> assertThrows(IllegalArgumentException.class,
				() -> group.addParameterSet(new ReflectiveConfigGroup("wrong") { })),
			() -> assertThrows(UnsupportedOperationException.class,
				() -> group.getCollaborationParamSets().clear())
		);
		assertInstanceOf(CollaborationParamSet.class,
			group.createParameterSet(CollaborationParamSet.GROUP_NAME));
		assertFalse(group.getCollaborationParamSetsString().isBlank());
		group.setCollaborationParamSets("legacy-value");
		assertEquals(Set.of(params), group.getCollaborationParamSets());

		FreightCollaborationConfigGroup empty = new FreightCollaborationConfigGroup(null, null);
		empty.setCollaborationParamSets(null);
		assertEquals("", empty.getCollaborationParamSetsString());
	}

	@Test
	void configRoundTripsParameterSetsAndAllAllocationControls() {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		group.INPUT_NETWORK_FILE = "network.xml";
		group.ALLOCATION_MODEL = AllocationModels.SHAPLEY;
		group.setAllocationStrategyString(AllocationValueTypes.COST.name());
		group.setPsimScoringModeString(FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES.name());
		group.setIter0BaselineModeString(FreightCollaborationConfigGroup.Iter0BaselineMode.FEE_INCLUDED.name());
		group.setVrpMaxIterations(17);
		group.setParallelism(2);
		group.setAllocationFactor(0.4);
		group.setMaxExactShapleyPlayers(7);
		group.setApproxShapleyMethod(AllocationModelApproxShapleyValue.ApproximationMethod.STRATIFIED.name());
		group.addParameterSet(new CollaborationParamSet(
			CollaborationTypes.CARRIER_RECEIVER,
			Set.of(CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION)));
		Config config = ConfigUtils.createConfig(group);
		Path path = tempDirectory.resolve("config.xml");
		new ConfigWriter(config).write(path.toString());

		FreightCollaborationConfigGroup loadedGroup = new FreightCollaborationConfigGroup();
		ConfigUtils.loadConfig(path.toString(), loadedGroup);

		assertAll(
			() -> assertEquals("network.xml", loadedGroup.INPUT_NETWORK_FILE),
			() -> assertEquals(AllocationModels.SHAPLEY, loadedGroup.ALLOCATION_MODEL),
			() -> assertEquals(AllocationValueTypes.COST, loadedGroup.getAllocationStrategy()),
			() -> assertEquals(17, loadedGroup.getVrpMaxIterations()),
			() -> assertEquals(2, loadedGroup.getParallelism()),
			() -> assertEquals(0.4, loadedGroup.getAllocationFactor()),
			() -> assertEquals(7, loadedGroup.getMaxExactShapleyPlayers()),
			() -> assertEquals(1, loadedGroup.getCollaborationParamSets().size())
		);
	}

	@Test
	void consistencyRejectsDirectFieldBypassAndIncompatibleStrategy() {
		Config config = ConfigUtils.createConfig();
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		config.addModule(group);
		group.ALLOCATION_FACTOR = Double.NaN;
		assertThrows(IllegalArgumentException.class, () -> group.checkConsistency(config));

		group.ALLOCATION_FACTOR = 0.9;
		group.addParameterSet(new CollaborationParamSet(
			CollaborationTypes.LSP_RECEIVER,
			Set.of(CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION)));
		assertThrows(IllegalArgumentException.class, () -> group.checkConsistency(config));
	}

	@Test
	void consistencyValidatesEveryDirectlyWritableNumericAndRequiredType() {
		assertInvalid(group -> group.VRP_MAX_ITERATIONS = 0);
		assertInvalid(group -> group.PARALLELISM = -1);
		assertInvalid(group -> group.MONTE_CARLO_SAMPLES = 0);
		assertInvalid(group -> group.SAMPLES_RATIO = Double.NaN);
		assertInvalid(group -> group.STRATIFIED_SAMPLES_PER_LEVEL = 0);
		assertInvalid(group -> group.MAX_STRATIFIED_EVALUATIONS = 1);
		assertInvalid(group -> group.MAX_EXACT_SHAPLEY_PLAYERS = -1);
		assertInvalid(group -> group.RECEIVER_RELAXATION_PENALTY = Double.NaN);
		assertInvalid(group -> group.RECEIVER_RELAXATION_PENALTY = -1);
		assertInvalid(group -> group.RECEIVER_FIXED_FEE = Double.POSITIVE_INFINITY);
		assertInvalid(group -> group.RECEIVER_FIXED_FEE = -1);
		assertInvalid(group -> group.CARRIER_CHARGED_FEE = Double.NaN);
		assertInvalid(group -> group.CARRIER_CHARGED_FEE = -1);

		FreightCollaborationConfigGroup missingType = new FreightCollaborationConfigGroup();
		missingType.addParameterSet(new CollaborationParamSet());
		Config config = ConfigUtils.createConfig(missingType);
		assertThrows(IllegalArgumentException.class, () -> missingType.checkConsistency(config));
	}

	private static void assertInvalid(java.util.function.Consumer<FreightCollaborationConfigGroup> mutation) {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		Config config = ConfigUtils.createConfig(group);
		mutation.accept(group);
		assertThrows(IllegalArgumentException.class, () -> group.checkConsistency(config));
	}
}
