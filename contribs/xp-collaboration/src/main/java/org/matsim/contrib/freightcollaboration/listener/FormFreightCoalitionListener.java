package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.GrandFreightCoalition;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;

import java.util.HashSet;
import java.util.Set;

/**
 * Functions that need to be executed at the start of each iteration.
 * The functions are listed below:
 * 	1. Form coalitions
 */
public class FormFreightCoalitionListener implements IterationStartsListener {

	@Inject
	private Scenario scenario;

	@Inject
	private Set<CollaboratorRole> allowedRoles;

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {

	}

	private void formFreightGrandCoalition(){
		GrandFreightCoalition grandCoalition = new GrandFreightCoalition(scenario);
	}


}
