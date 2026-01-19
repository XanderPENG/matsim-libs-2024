package org.matsim.contrib.freightcollaboration.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.contrib.freightcollaboration.allocation.AllocationValueTypes;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;
import org.matsim.core.config.ConfigGroup;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class FreightCollaborationConfigGroup extends ReflectiveConfigGroup {
	private static final Logger LOG = LogManager.getLogger(FreightCollaborationConfigGroup.class);
	private Set<CollaborationParamSet> collaborationParamSets = new LinkedHashSet<>();

	public static final String GROUP_NAME = "freightCollaboration";
	public static final String ELEMENT_NAME = "freightCollaborators";

	public FreightCollaborationConfigGroup() {
		super(GROUP_NAME);
	}

	public FreightCollaborationConfigGroup(Set<CollaborationParamSet> collaborationParamSets, String inputNetworkFile) {
		super(GROUP_NAME);
		this.collaborationParamSets = new LinkedHashSet<>();
		if (collaborationParamSets != null) {
			collaborationParamSets.forEach(this::addParameterSet);
		}
		this.INPUT_NETWORK_FILE = inputNetworkFile;
	}

	// Global parameters
	@Parameter
	public String INPUT_NETWORK_FILE;

	/**
	 * Maximum iterations for VRP solver inside FreightPseudoSimulator.
	 * Kept configurable so sampling-heavy runs can trade accuracy for speed.
	 */
	@Parameter
	public int VRP_MAX_ITERATIONS = 200;

	/**
	 * Thread pool size used for coalition evaluation/allocation.
	 * 0 or negative values will be interpreted as "use available processors".
	 */
	@Parameter
	public int PARALLELISM = Runtime.getRuntime().availableProcessors();

	// Allocation model set, Proportional allocation by default
	public AllocationModels ALLOCATION_MODEL = AllocationModels.APPROX_SHAPLEY;

	/**
	 * Allocation strategy decides whether models distribute total cost or cost savings.
	 * Default keeps existing behaviour (cost savings).
	 */
	private AllocationValueTypes ALLOCATION_STRATEGY = AllocationValueTypes.COST_SAVINGS;

	/**
	 * Approximate Shapley method selection (MONTE_CARLO | STRATIFIED).
	 */
	public String APPROX_SHAPLEY_METHOD = AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO.name();

	/**
	 * Share of value given to collaborating players (rest goes to distributor).
	 */
	@Parameter
	public double ALLOCATION_FACTOR = 0.9;

	/**
	 * Penalty factor used by ReceiverRelaxationPenalty (per second of relaxation).
	 */
	@Parameter
	public double RECEIVER_RELAXATION_PENALTY = 0.01;

	/**
	 * Fixed fee charged to receivers (used when creating fixed receiver cost allocation).
	 */
	@Parameter
	public double RECEIVER_FIXED_FEE = 200.0;

	/**
	 * Fee charged by carriers to each linked receiver in SimpleChargingReceiverScoring.
	 */
	@Parameter
	public double CARRIER_CHARGED_FEE = 200.0;

	@Parameter
	public int MONTE_CARLO_SAMPLES = 10;

	@Parameter
	public double SAMPLES_RATIO = 0.2;

	@Parameter
	public int STRATIFIED_SAMPLES_PER_LEVEL = 5;

	@Parameter
	public int MAX_STRATIFIED_EVALUATIONS = 100;

//	@StringSetter("collaborationParamSets")
	public void setCollaborationParamSets(String value) {
		// Parameter sets are provided as <paramSet> elements; ignore any string and mirror parsed sets.
		if (collaborationParamSets == null) collaborationParamSets = new LinkedHashSet<>();
		collaborationParamSets.clear();
		this.getParameterSets(CollaborationParamSet.GROUP_NAME).forEach(group -> {
			CollaborationParamSet p = (CollaborationParamSet) group;
			collaborationParamSets.add(p);
		});
		if (value != null && !value.trim().isEmpty()) {
			LOG.warn("collaborationParamSets should be provided via <paramSet> entries; ignoring: {}", value);
		}
	}

//	@StringGetter("collaborationParamSets")
	public String getCollaborationParamSetsString() {
		if (collaborationParamSets == null || collaborationParamSets.isEmpty()) {
			return "";
		}
		return collaborationParamSets.stream()
				.map(paramSet -> paramSet.getCollaborationTypeString() + ":" + paramSet.getCollaborationStrategiesString())
				.collect(Collectors.joining("; "));
	}

	@Override
	public ConfigGroup createParameterSet(String type) {
		if (CollaborationParamSet.GROUP_NAME.equals(type)) {
			return new CollaborationParamSet();
		}
		throw new IllegalArgumentException("Unsupported parameter set type: " + type);
	}

	@Override
	public void addParameterSet(ConfigGroup set) {
		if (set instanceof CollaborationParamSet) {
			super.addParameterSet(set);
			this.collaborationParamSets.add((CollaborationParamSet) set);
			return;
		}
		throw new IllegalArgumentException("Unsupported parameter set class: " + set);
	}

	/**
	 * StringGetter and setter for allocation model
	 *
	 */
	@StringGetter("ALLOCATION_MODEL")
	public String getAllocationModelString() {
		return ALLOCATION_MODEL.toString();
	}

	@StringSetter("ALLOCATION_MODEL")
	public void setAllocationModelString(String allocationModel) {
		try {
			this.ALLOCATION_MODEL = AllocationModels.valueOf(allocationModel);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid allocation model: " + allocationModel + ". Valid options are: " + String.join(", ",
					java.util.Arrays.stream(AllocationModels.values()).map(Enum::name).toArray(String[]::new)));
		}
	}

	@StringGetter("ALLOCATION_STRATEGY")
	public String getAllocationStrategyString() {
		return ALLOCATION_STRATEGY.toString();
	}

	@StringSetter("ALLOCATION_STRATEGY")
	public void setAllocationStrategyString(String allocationStrategy) {
		try {
			this.ALLOCATION_STRATEGY = AllocationValueTypes.valueOf(allocationStrategy);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid allocation strategy: " + allocationStrategy + ". Valid options are: " + String.join(", ",
				java.util.Arrays.stream(AllocationValueTypes.values()).map(Enum::name).toArray(String[]::new)));
		}
	}

	public AllocationValueTypes getAllocationStrategy() {
		return ALLOCATION_STRATEGY;
	}

	public int getVrpMaxIterations() {
		return VRP_MAX_ITERATIONS;
	}

	public void setVrpMaxIterations(int vrpMaxIterations) {
		if (vrpMaxIterations <= 0) {
			throw new IllegalArgumentException("VRP_MAX_ITERATIONS must be positive.");
		}
		this.VRP_MAX_ITERATIONS = vrpMaxIterations;
	}

	public int getParallelism() {
		return PARALLELISM > 0 ? PARALLELISM : Runtime.getRuntime().availableProcessors();
	}

	public void setParallelism(int parallelism) {
		if (parallelism == 0) {
			this.PARALLELISM = Runtime.getRuntime().availableProcessors();
			return;
		}
		if (parallelism < 0) {
			throw new IllegalArgumentException("PARALLELISM must be positive or zero for auto.");
		}
		this.PARALLELISM = parallelism;
	}

	public double getAllocationFactor() {
		return ALLOCATION_FACTOR;
	}

	@StringGetter("APPROX_SHAPLEY_METHOD")
	public String getApproxShapleyMethod() {
		return APPROX_SHAPLEY_METHOD;
	}

	@StringSetter("APPROX_SHAPLEY_METHOD")
	public void setApproxShapleyMethod(String method) {
		if (method == null || method.isBlank()) {
			APPROX_SHAPLEY_METHOD = AllocationModelApproxShapleyValue.ApproximationMethod.MONTE_CARLO.name();
			return;
		}
		try {
			// Validate against enum to fail fast on typos
			AllocationModelApproxShapleyValue.ApproximationMethod.valueOf(method);
			APPROX_SHAPLEY_METHOD = method;
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid APPROX_SHAPLEY_METHOD: " + method + ". Valid options: " +
				java.util.Arrays.toString(AllocationModelApproxShapleyValue.ApproximationMethod.values()));
		}
	}

	public Set<CollaborationParamSet> getCollaborationParamSets() {
		return collaborationParamSets;
	}

	public int getMonteCarloSamples() {
		return MONTE_CARLO_SAMPLES;
	}

	public void setMonteCarloSamples(int samples) {
		if (samples <= 0) {
			throw new IllegalArgumentException("MONTE_CARLO_SAMPLES must be positive.");
		}
		this.MONTE_CARLO_SAMPLES = samples;
	}

	public double getSamplesRatio() {
		return SAMPLES_RATIO;
	}

	public void setSamplesRatio(double ratio) {
		if (ratio <= 0.0 || ratio > 1.0) {
			throw new IllegalArgumentException("SAMPLES_RATIO must be in (0,1].");
		}
		this.SAMPLES_RATIO = ratio;
	}

	public int getStratifiedSamplesPerLevel() {
		return STRATIFIED_SAMPLES_PER_LEVEL;
	}

	public void setStratifiedSamplesPerLevel(int perLevel) {
		if (perLevel <= 0) {
			throw new IllegalArgumentException("STRATIFIED_SAMPLES_PER_LEVEL must be positive.");
		}
		this.STRATIFIED_SAMPLES_PER_LEVEL = perLevel;
	}

	public int getMaxStratifiedEvaluations() {
		return MAX_STRATIFIED_EVALUATIONS;
	}

	public void setMaxStratifiedEvaluations(int maxEval) {
		if (maxEval <= 0) {
			throw new IllegalArgumentException("MAX_STRATIFIED_EVALUATIONS must be positive.");
		}
		this.MAX_STRATIFIED_EVALUATIONS = maxEval;
	}

}
