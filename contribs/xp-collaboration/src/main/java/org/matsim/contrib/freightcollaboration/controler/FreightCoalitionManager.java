package org.matsim.contrib.freightcollaboration.controler;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.GrandFreightCoalition;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;

import java.util.HashSet;
import java.util.Set;

/**
 * This class is intended to manage freight coalitions within the MATSim freight collaboration framework.
 * It should handle the creation, modification, and dissolution of coalitions among different collaborators
 * such as carriers, receivers, and logistics service providers (LSPs).
 *
 * @author Xander Peng
 */
@Singleton
public class FreightCoalitionManager {
	@Inject
	private Scenario scenario;

	private Set<CollaboratorRole> allCollaboratorRoles = null;
	private GrandFreightCoalition grandFreightCoalition;

	public FreightCoalitionManager() {
	}

	Set<CollaboratorRole> provideAllowedRoles() {

		FreightCollaborationConfigGroup configGroup =
			(FreightCollaborationConfigGroup) scenario.getConfig().getModules()
				.get(FreightCollaborationConfigGroup.GROUP_NAME);

		Set<CollaboratorRole> allowedRoles = new HashSet<>();
		for (CollaborationParamSet paramSet : configGroup.getCollaborationParamSets()) {
			allowedRoles.addAll(paramSet.getCollaborationType().getAllowedRoles());
		}
		return allowedRoles;
	}

	public Set<CollaboratorRole> getAllCollaboratorRoles() {
		if (allCollaboratorRoles == null) {
			allCollaboratorRoles = provideAllowedRoles();
		}
		return allCollaboratorRoles;
	}

	public GrandFreightCoalition getGrandFreightCoalition() {
		return grandFreightCoalition;
	}

	public void setGrandFreightCoalition(GrandFreightCoalition grandFreightCoalition) {
		this.grandFreightCoalition = grandFreightCoalition;
	}

}
