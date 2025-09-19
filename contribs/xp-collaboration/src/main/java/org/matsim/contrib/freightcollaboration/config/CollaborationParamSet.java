package org.matsim.contrib.freightcollaboration.config;

import org.matsim.contrib.freightcollaboration.CollaborationType;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.core.api.internal.MatsimParameters;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Set;

public class CollaborationParamSet extends ReflectiveConfigGroup implements MatsimParameters {


	public static final String GROUP_NAME = "collaborationParamSet";

	public String COLLABORATION_TYPE;

	public Set<String> COLLABORATION_STRATEGIES;

	public CollaborationParamSet() {
		super(GROUP_NAME);
	}


	public CollaborationParamSet(CollaborationType collaborationType, Set<String> collaborationStrategies) {
		super(GROUP_NAME);
		this.COLLABORATION_TYPE = ((CollaborationTypes) collaborationType).name();
		this.COLLABORATION_STRATEGIES = collaborationStrategies;
	}


	@StringGetter("COLLABORATION_TYPE")
	public String getCollaborationTypeString() {
		return COLLABORATION_TYPE;
	}

	@StringSetter("COLLABORATION_TYPE")
	public void setCollaborationTypeString(String type) {
		this.COLLABORATION_TYPE = type;
	}

	@StringGetter("COLLABORATION_STRATEGIES")
	public String getCollaborationStrategiesString() {
		return String.join(", ", COLLABORATION_STRATEGIES);
	}

	@StringSetter("COLLABORATION_STRATEGIES")
	public void setCollaborationStrategiesString(String strategies) {
		String[] strategyArray = strategies.split(",");
		this.COLLABORATION_STRATEGIES = Set.of(strategyArray);
	}

	/**
	 * Get the CollaborationType object from the stored string.
	 */
	public CollaborationType getCollaborationType() {
		return CollaborationTypes.valueOf(COLLABORATION_TYPE);
	}

}

