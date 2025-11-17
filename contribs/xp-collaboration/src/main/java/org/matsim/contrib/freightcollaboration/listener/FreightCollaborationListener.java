package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.allocation.FreightCollaborationEngine;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

public class FreightCollaborationListener implements IterationStartsListener, AfterMobsimListener {

	@Inject
	private EventsManager events;

	@Inject
	private Network network;

	@Inject
	private Config config;

	@Inject
	private Scenario scenario;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private CollaborationDataStore collaborationDataStore;

	@Inject
	FreightCoalitionManager freightCollaborationManager;

	private TravelTimeCalculator ttc;

	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborationListener.class);

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		// Initialize TravelTimeCalculator
		if (ttc != null) throw new IllegalStateException("TTC should be null at iteration start.");
		/**
		 * Create a TravelTimeCalculator to compute link travel times based on events.
		 * Note: Currently, only a default TravelTimeCalculator without custom settings is created,
		 * 	it could/should provide interface for custom settings?
		 */
		ttc = new TravelTimeCalculator.Builder(network)
			.build();
		// Register the TravelTimeCalculator as an event handler, to collect events and compute travel times later
		events.addHandler(ttc);
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {

		// if this is the first iteration, skip the collaboration process
		if (event.getIteration() == scenario.getConfig().controller().getFirstIteration()) {
			// Remove the TravelTimeCalculator as an event handler, to avoid interference with next iteration
			events.removeHandler(ttc);
			ttc = null;
			LOGGER.info("Skipping freight collaboration process at the first iteration.");
			return;
		}


		// Get the events-based TravelTime
		if (ttc == null) throw new IllegalStateException("TTC not initialized for this iteration.");
		TravelTime tt = ttc.getLinkTravelTimes();

		/**
		 * Use the TravelTime for the freight collaboration logic
		 */

		FreightCollaborationEngine collaborationEngine = new FreightCollaborationEngine(config, scenario, freightCollaborators,
			collaborationDataStore, freightCollaborationManager.getMutableFreightCoalitions(), tt);
		collaborationEngine.runCollaboration();

		// Remove the TravelTimeCalculator as an event handler, to avoid interference with next iteration
		events.removeHandler(ttc);
		ttc = null;
	}


}
