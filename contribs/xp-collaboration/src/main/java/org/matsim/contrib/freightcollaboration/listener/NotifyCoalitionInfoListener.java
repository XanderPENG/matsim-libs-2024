package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;

public class NotifyCoalitionInfoListener implements BeforeMobsimListener {
	// Logger
	private static final Logger LOGGER = LogManager.getLogger(NotifyCoalitionInfoListener.class);

	@Inject
	private Scenario scenario;

	@Inject
	private FreightCollaborationConfigGroup freightCollaborationConfigGroup;

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		// Implement coalition formation logic here
		LOGGER.info("Coalition Formation Info:");



	}

	private void formFreightMutableCoalitions(){

	}
}
