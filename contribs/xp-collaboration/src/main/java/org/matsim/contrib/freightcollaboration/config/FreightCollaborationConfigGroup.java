package org.matsim.contrib.freightcollaboration.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.ReflectiveConfigGroup;

import java.util.Set;

public class FreightCollaborationConfigGroup extends ReflectiveConfigGroup {
	private static final Logger LOG = LogManager.getLogger(FreightCollaborationConfigGroup.class);
	private Set<CollaborationParamSet> collaborationParamSets;

	public static final String GROUP_NAME = "freightCollaboration";

	public FreightCollaborationConfigGroup() {
		super(GROUP_NAME);
	}

	public FreightCollaborationConfigGroup(Set<CollaborationParamSet> collaborationParamSets, String inputNetworkFile) {
		super(GROUP_NAME);
		this.collaborationParamSets = collaborationParamSets;
		this.INPUT_NETWORK_FILE = inputNetworkFile;
	}

	// Global parameters
	@Parameter
	public String INPUT_NETWORK_FILE;

	public void readCollaborationParamSets(){
		this.getParameterSets(CollaborationParamSet.GROUP_NAME).forEach(group -> {
			CollaborationParamSet collaborationParamSet = (CollaborationParamSet) group;
			collaborationParamSets.add(collaborationParamSet);
			LOG.info("Loaded CollaborationParamSet: " + collaborationParamSet.getCollaborationTypeString() + " with strategies " + collaborationParamSet.getCollaborationStrategiesString());
		});
	}

	public Set<CollaborationParamSet> getCollaborationParamSets() {
		return collaborationParamSets;
	}

}
