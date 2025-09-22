package org.matsim.contrib.freightcollaboration.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

	public Set<CollaborationParamSet> getCollaborationParamSets() {
		return collaborationParamSets;
	}

}
