package org.matsim.contrib.freightcollaboration.config;

import org.matsim.contrib.freightcollaboration.CollaborationType;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.core.api.internal.MatsimParameters;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup.StringGetter;
import org.matsim.core.config.ReflectiveConfigGroup.StringSetter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class CollaborationParamSet extends ReflectiveConfigGroup implements MatsimParameters {

	public static final String GROUP_NAME = "collaborationParamSet";

	// Changed from String to CollaborationType
	private CollaborationType COLLABORATION_TYPE;

	// Changed from Set<String> to Set<CollaborationStrategies>
	private Set<CollaborationStrategies> COLLABORATION_STRATEGIES;

	public CollaborationParamSet() {
		super(GROUP_NAME);
	}

	public CollaborationParamSet(CollaborationType collaborationType, Set<CollaborationStrategies> collaborationStrategies) {
		super(GROUP_NAME);
		this.COLLABORATION_TYPE = collaborationType;
		this.COLLABORATION_STRATEGIES = collaborationStrategies;
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
		return COLLABORATION_STRATEGIES;
	}

	/**
	 * Set the set of CollaborationStrategies.
	 */
	public void setCollaborationStrategies(Set<CollaborationStrategies> collaborationStrategies) {
		this.COLLABORATION_STRATEGIES = collaborationStrategies;
	}
}
