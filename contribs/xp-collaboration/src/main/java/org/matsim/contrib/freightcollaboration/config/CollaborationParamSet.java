package org.matsim.contrib.freightcollaboration.config;

import org.matsim.contrib.freightcollaboration.CollaborationType;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.core.api.internal.MatsimParameters;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class CollaborationParamSet extends ReflectiveConfigGroup implements MatsimParameters {

	public static final String GROUP_NAME = "collaborationParamSet";

	// Changed from String to CollaborationType
	private CollaborationType COLLABORATION_TYPE;

	// Changed from Set<String> to Set<CollaborationStrategies>
	private Set<CollaborationStrategies> COLLABORATION_STRATEGIES;

	private Map<String, Set<String>> ADDITIONAL_PARAMS;

	@Parameter
	private double COST_SAVINGS_SHARING_THRESHOLD = 0.8;

	public CollaborationParamSet() {
		super(GROUP_NAME);
	}

	public CollaborationParamSet(CollaborationType collaborationType, Set<CollaborationStrategies> collaborationStrategies) {
		super(GROUP_NAME);
		this.COLLABORATION_TYPE = collaborationType;
		setCollaborationStrategies(collaborationStrategies);
	}

	@StringGetter("COLLABORATION_TYPE")
	public String getCollaborationTypeString() {
		return COLLABORATION_TYPE != null ? COLLABORATION_TYPE.toString() : "";
	}

	@StringSetter("COLLABORATION_TYPE")
	public void setCollaborationTypeString(String type) {
		if (type == null || type.trim().isEmpty()) {
			this.COLLABORATION_TYPE = null;
			return;
		}
		try {
			this.COLLABORATION_TYPE = CollaborationTypes.valueOf(type.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid collaboration type: " + type + ". Valid types are: " +
				Arrays.toString(CollaborationTypes.values()));
		}
	}

	@StringGetter("COLLABORATION_STRATEGIES")
	public String getCollaborationStrategiesString() {
		if (COLLABORATION_STRATEGIES == null || COLLABORATION_STRATEGIES.isEmpty()) {
			return "";
		}
		return COLLABORATION_STRATEGIES.stream()
				.map(CollaborationStrategies::name)
				.collect(Collectors.joining(", "));
	}

	@StringSetter("COLLABORATION_STRATEGIES")
	public void setCollaborationStrategiesString(String strategies) {
		if (strategies == null || strategies.trim().isEmpty()) {
			this.COLLABORATION_STRATEGIES = Set.of();
			return;
		}

		this.COLLABORATION_STRATEGIES = Arrays.stream(strategies.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(s -> {
					try {
						return CollaborationStrategies.valueOf(s);
					} catch (IllegalArgumentException e) {
						throw new IllegalArgumentException("Invalid collaboration strategy: " + s + ". Valid strategies are: " +
							Arrays.toString(CollaborationStrategies.values()));
					}
				})
				.collect(Collectors.toCollection(java.util.LinkedHashSet::new));
	}


	// StringGetter and Setter for ADDITIONAL_PARAMS
	@StringGetter("ADDITIONAL_PARAMS")
	public String getAdditionalParamsString() {
		if (ADDITIONAL_PARAMS == null || ADDITIONAL_PARAMS.isEmpty()) {
			return "";
		}
		return ADDITIONAL_PARAMS.entrySet().stream()
				.map(entry -> entry.getKey() + "=" + String.join("|", entry.getValue()))
				.collect(Collectors.joining("; "));
	}

	@StringSetter("ADDITIONAL_PARAMS")
	public void setAdditionalParamsString(String params) {
		if (params == null || params.trim().isEmpty()) {
			this.ADDITIONAL_PARAMS = Map.of();
			return;
		}
		Map<String, Set<String>> parsed = new LinkedHashMap<>();
		for (String item : params.split(";")) {
			String trimmed = item.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] pair = trimmed.split("=", 2);
			if (pair.length != 2 || pair[0].trim().isEmpty()) {
				throw new IllegalArgumentException("Invalid ADDITIONAL_PARAMS entry '" + trimmed
					+ "'. Expected key=value1|value2.");
			}
			String key = pair[0].trim();
			if (parsed.containsKey(key)) {
				throw new IllegalArgumentException("Duplicate ADDITIONAL_PARAMS key: " + key);
			}
			Set<String> values = Arrays.stream(pair[1].split("\\|"))
				.map(String::trim)
				.filter(v -> !v.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
			parsed.put(key, Set.copyOf(values));
		}
		this.ADDITIONAL_PARAMS = Map.copyOf(parsed);
	}


	/**
	 * Get the CollaborationType object.
	 */
	public CollaborationType getCollaborationType() {
		return COLLABORATION_TYPE;
	}

	/**
	 * Set the CollaborationType object.
	 */
	public void setCollaborationType(CollaborationType collaborationType) {
		this.COLLABORATION_TYPE = collaborationType;
	}

	/**
	 * Get the set of CollaborationStrategies.
	 */
	public Set<CollaborationStrategies> getCollaborationStrategies() {
		return COLLABORATION_STRATEGIES == null ? Set.of() : COLLABORATION_STRATEGIES;
	}

	/**
	 * Set the set of CollaborationStrategies.
	 */
	public void setCollaborationStrategies(Set<CollaborationStrategies> collaborationStrategies) {
		this.COLLABORATION_STRATEGIES = collaborationStrategies == null
			? Set.of()
			: Set.copyOf(collaborationStrategies);
	}

	public Map<String, Set<String>> getAdditionalParams() {
		return ADDITIONAL_PARAMS == null ? Map.of() : ADDITIONAL_PARAMS;
	}

	public void setAdditionalParams(Map<String, Set<String>> additionalParams) {
		if (additionalParams == null || additionalParams.isEmpty()) {
			this.ADDITIONAL_PARAMS = Map.of();
			return;
		}
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		additionalParams.forEach((key, values) -> copy.put(
			Objects.requireNonNull(key, "additional parameter key"),
			values == null ? Set.of() : Set.copyOf(values)));
		this.ADDITIONAL_PARAMS = Map.copyOf(copy);
	}
}
