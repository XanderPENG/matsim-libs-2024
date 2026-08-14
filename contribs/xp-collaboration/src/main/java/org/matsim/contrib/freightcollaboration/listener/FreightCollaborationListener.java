package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
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
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

import java.util.List;
import java.util.Set;

public class FreightCollaborationListener implements IterationStartsListener, AfterMobsimListener {

	private final EventsManager events;
	private final Network network;
	private final Config config;
	private final Scenario scenario;
	private final FreightCollaborators freightCollaborators;
	private final CollaborationDataStore collaborationDataStore;
	private final FreightCoalitionManager freightCollaborationManager;
	private final CarrierScoringFunctionFactory carrierScoringFunctionFactory;
	private final CollaborationRunnerFactory collaborationRunnerFactory;

	private TravelTimeCalculator ttc;

	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborationListener.class);

	@Inject
	public FreightCollaborationListener(EventsManager events, Network network, Config config, Scenario scenario,
										FreightCollaborators freightCollaborators,
										CollaborationDataStore collaborationDataStore,
										FreightCoalitionManager freightCollaborationManager,
										CarrierScoringFunctionFactory carrierScoringFunctionFactory) {
		this(events, network, config, scenario, freightCollaborators, collaborationDataStore,
			freightCollaborationManager, carrierScoringFunctionFactory,
			(cfg, sc, collaborators, dataStore, coalitions, travelTime, scoringFactory) ->
				() -> new FreightCollaborationEngine(cfg, sc, collaborators, dataStore, coalitions, travelTime,
					scoringFactory).runCollaboration());
	}

	FreightCollaborationListener(EventsManager events, Network network, Config config, Scenario scenario,
								 FreightCollaborators freightCollaborators,
								 CollaborationDataStore collaborationDataStore,
								 FreightCoalitionManager freightCollaborationManager,
								 CarrierScoringFunctionFactory carrierScoringFunctionFactory,
								 CollaborationRunnerFactory collaborationRunnerFactory) {
		this.events = events;
		this.network = network;
		this.config = config;
		this.scenario = scenario;
		this.freightCollaborators = freightCollaborators;
		this.collaborationDataStore = collaborationDataStore;
		this.freightCollaborationManager = freightCollaborationManager;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
		this.collaborationRunnerFactory = collaborationRunnerFactory;
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		if (ttc != null) {
			throw new IllegalStateException("TTC should be null at iteration start.");
		}
		TravelTimeCalculator next = new TravelTimeCalculator.Builder(network).build();
		events.addHandler(next);
		ttc = next;
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		TravelTimeCalculator current = ttc;
		if (current == null) {
			throw new IllegalStateException("TTC not initialized for this iteration.");
		}
		try {
			if (event.getIteration() == scenario.getConfig().controller().getFirstIteration()) {
				LOGGER.info("Skipping freight collaboration process at the first iteration.");
				return;
			}

			collaborationDataStore.reset(event.getIteration());
			TravelTime travelTime = current.getLinkTravelTimes();
			List<MutableFreightCoalition> coalitions = freightCollaborationManager.getMutableFreightCoalitions();
			if (coalitions == null || coalitions.isEmpty()
				|| coalitions.stream().allMatch(c -> c.size() == 1)) {
				LOGGER.info("No existing freight coalitions found - skipping collaboration process for this iteration.");
				return;
			}

			collaborationRunnerFactory.create(config, scenario, freightCollaborators, collaborationDataStore,
				List.copyOf(coalitions), travelTime, carrierScoringFunctionFactory).run();
		} finally {
			events.removeHandler(current);
			if (ttc == current) {
				ttc = null;
			}
		}
	}

	@FunctionalInterface
	interface CollaborationRunnerFactory {
		Runnable create(Config config, Scenario scenario, FreightCollaborators collaborators,
						CollaborationDataStore dataStore, List<MutableFreightCoalition> coalitions,
						TravelTime travelTime, CarrierScoringFunctionFactory scoringFunctionFactory);
	}
}
