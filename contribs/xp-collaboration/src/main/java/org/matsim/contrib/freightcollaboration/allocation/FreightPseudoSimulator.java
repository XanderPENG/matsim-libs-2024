package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.FreightActivity;
import org.matsim.freight.carriers.events.AbstractCarrierEvent;
import org.matsim.freight.carriers.events.CarrierEventCreatorUtils;
import org.matsim.freight.carriers.events.CarrierTourEndEvent;
import org.matsim.freight.carriers.events.CarrierTourStartEvent;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.vehicles.Vehicle;
import org.slf4j.LoggerFactory;

import java.util.*;

public class FreightPseudoSimulator {

//	@Inject
	CollaborationDataStore collaborationDataStore;

//	@Inject
	Network network;

//	@Inject
	FreightCollaborators freightCollaborators;

	// Travel time to be used in the pseudo-simulation, which is based on the events of the main-MATSim simulation
	TravelTime tt;

	CarrierScoringFunctionFactory carrierScoringFunctionFactory;

	private static Logger LOGGER = LogManager.getLogger(FreightPseudoSimulator.class);

//	FreightPseudoSimulator() {}

	FreightPseudoSimulator(CollaborationDataStore dataStore, Network network,
						   FreightCollaborators freightCollaborators, TravelTime tt,
						   CarrierScoringFunctionFactory carrierScoringFunctionFactory) {
		this.tt = tt;
		this.collaborationDataStore = dataStore;
		this.network = network;
		this.freightCollaborators = freightCollaborators;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
	}

	void run() {

	}

	/** Simulate all possible sub-coalitions of the given players and distributors
	 * For each sub-coalition, record its scores and return them as a map
	 */
	Map<Set<Id<?>>, Double> runAllSubCoalitions(Map<Id<?>, FreightCollaborator<?>> distributors, Map<Id<?>, FreightCollaborator<?>> players) {
		LOGGER.info("Starting Freight Pseudo Simulator");
		// Generate all possible sub-coalitions of the given players
		var subCoalitionScoreMap = AllocationUtils.generateSubsets(players);
		// For each sub-coalition, simulate it and record its score
		for (Set<Id<?>> subCoalition : subCoalitionScoreMap.keySet()) {
			// if it is the empty set
			if (subCoalition.isEmpty()) {
				// No collaboration, set score to 0 (ignore)
				// FIXME: is it appropriate to set it to 0?
				Map<Id<?>, FreightCollaborator<?>> copyDistributors = AllocationUtils.deepCopyCollaboratorsMap(distributors);
				Map<Id<?>, FreightCollaborator<?>> copyPlayers = AllocationUtils.deepCopyCollaboratorsMap(players);

				// identify non-collaborating members
				Set<Id<?>> nonCollaboratingMembers = players.keySet();
				// For these non-collaborating members/players, we need to use their original plans from the data store
				resetNonCollaboratingMembersPlans(nonCollaboratingMembers, copyPlayers);

				// run PSim for this sub-coalition
				double score = runPSim(copyDistributors, copyPlayers);

				// record the score
				subCoalitionScoreMap.put(subCoalition, score);
//			} else if (subCoalition.size() == players.size()) {  // also using psim
//				// Full coalition, use the existing main-MATSim events
//				continue;
			} else { // Partial coalition, simulate it separately
				// Deep copy the distributors and players maps for this sub-coalition, so that we can modify them safely
				Map<Id<?>, FreightCollaborator<?>> copyDistributors = AllocationUtils.deepCopyCollaboratorsMap(distributors);
				Map<Id<?>, FreightCollaborator<?>> copyPlayers = AllocationUtils.deepCopyCollaboratorsMap(players);

				// identify non-collaborating members
				Set<Id<?>> nonCollaboratingMembers = AllocationUtils.identifyNonCollaboratingMembers(players.keySet(), subCoalition);
				// For these non-collaborating members/players, we need to use their original plans from the data store
				resetNonCollaboratingMembersPlans(nonCollaboratingMembers, copyPlayers);

				// run PSim for this sub-coalition
				double score = runPSim(copyDistributors, copyPlayers);

				// record the score
				subCoalitionScoreMap.put(subCoalition, score);
			}
		}

		return subCoalitionScoreMap;
	}


	@SuppressWarnings("unchecked")
	private double runPSim(Map<Id<?>, FreightCollaborator<?>> copyDistributors, Map<Id<?>, FreightCollaborator<?>> copyPlayers) {
		var collaboratorRoleOfDistributors = copyDistributors.values().iterator().next().getRole();
		var collaboratorRoleOfPlayers = copyPlayers.values().iterator().next().getRole();
		// if it is carrier-receiver collaboration - runCarrierPSim
		if (CollaborationTypes.CARRIER_RECEIVER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)) {
			var carrierCollaborator = (FreightCollaborator<Carrier>) copyDistributors.values().iterator().next();
			var receiverCollaborators = new HashSet<>((Set<FreightCollaborator<Receiver>>) (Set<?>) Set.copyOf(copyPlayers.values()));
			Set<FreightCollaborator<Receiver>> nonCollaboratingReceivers = new HashSet<>();
			@SuppressWarnings("unchecked")
			Map<Id<?>, FreightCollaborator<Receiver>> allReceivers = (Map<Id<?>, FreightCollaborator<Receiver>>) (Map<?, ?>) freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
			allReceivers.values().forEach(receiverCollaborator -> {
				// if this receiver is not in the collaborating players for this carrier, check whether it is a non-collaborating receiver for this carrier
				if (!copyPlayers.containsKey(receiverCollaborator.getId())) {
					ReceiverPlan receiverPlan = receiverCollaborator.getTypedSelectedPlan();
					// check whether this receiver has orders for this carrier
					boolean hasOrdersForThisCarrier = receiverPlan.getReceiverOrders().stream()
							.anyMatch(order -> order.getCarrierId().equals(carrierCollaborator.getId()));
					if (hasOrdersForThisCarrier) {
						nonCollaboratingReceivers.add(receiverCollaborator);
					}
				}
			});
			// merge the non-collaborating receivers and the collaborating ones
			receiverCollaborators.addAll(nonCollaboratingReceivers);
			// need to re-generate the carrier plan based on the new receiver plans/requests
			LinkReceiverAndCarrier.receiversTriggerCarrierReplan(carrierCollaborator, receiverCollaborators,
					network, tt
				);
			// Then run the carrier PSim
			var driverLegsAndActivitiesMap = runActivityBasedCarrierSimulation(carrierCollaborator.getDelegate());
			// calculate the score based on the output legs and activities
			CarrierPSimScorer carrierPSimScorer = new CarrierPSimScorer(driverLegsAndActivitiesMap,
				carrierCollaborator.getDelegate(), carrierScoringFunctionFactory);
			return carrierPSimScorer.getScore();

		} else if (CollaborationTypes.LSP_RECEIVER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)) {
			// to be implemented
			throw new IllegalStateException("Unable to simulate LSP-Receiver collaboration yet.");
		} else if (CollaborationTypes.CARRIER_CARRIER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)){
			// to be implemented
			throw new IllegalStateException("Unable to simulate Carrier-Carrier collaboration yet.");
		} else {
			throw new IllegalStateException("Unsupported collaboration type for PSim: " +
					collaboratorRoleOfDistributors + " - " + collaboratorRoleOfPlayers);
		}
	}

	/**
	 * Run the pseudo-simulation for the given carrier, which has already updated its plan.
	 * This psim will generate the carrier-related events based on the carrier plan and the given travel time.
	 */
	@Deprecated(since = "Use the runActivityBasedCarrierSimulation method")
	void runCarrierPSim(Carrier carrier, TravelTime tt) {
		CarrierPlan selectedPlan = carrier.getSelectedPlan();
		// create a map to store the driver (fake) and their corresponding events
		Map<Integer, List<AbstractCarrierEvent>> driverAndTourMap = new HashMap<>();
		// for-loop to process each tour in the carrier plan
		int driverIdCounter = 1;
		List<AbstractCarrierEvent> driverEvents = new ArrayList<>();
		for (ScheduledTour tour: selectedPlan.getScheduledTours()) {
			double timeCursor = tour.getDeparture(); // start time of the tour

			// First, generate the CarrierTourStartEvent
			CarrierTourStartEvent tourStartEvent = new CarrierTourStartEvent(
					tour.getDeparture(),  // start time
					carrier.getId(),  // carrier id
					//FIXME: not sure which link id is appropriate here, theoretically they should be the same?
					tour.getTour().getStartLinkId() != null ? tour.getTour().getStartLinkId() : tour.getTour().getEndLinkId(),
					tour.getVehicle().getId(), // vehicle id
					tour.getTour().getId() // tour id
			);
			// add the event to the driver's event list
			driverEvents.add(tourStartEvent);

			// Get the TourElements list and for-loop through them to generate other events
			for (var el : tour.getTour().getTourElements()) {
				switch (el) {
					case Tour.Leg leg -> {
						// handle leg: leg.getRoute(), leg.getExpectedDepartureTime(), leg.getExpectedTransportTime()
						// use tt to estimate times if needed
					}
					case Tour.Pickup pickup -> {
						// handle pickup activity: pickup.getShipment(), pickup.getLocation(), pickup.getTimeWindow()
					}
					case Tour.Delivery delivery -> {
						// handle delivery activity
					}
					case Tour.ServiceActivity service -> {
						// handle service activity
					}
					case Tour.TourActivity act -> {
						// generic activity path if needed
					}
					default ->
						// unknown element type safeguard
						throw new IllegalStateException("Unknown tour element: " + el.getClass());
				}
			}
			// After processing all tour elements, add the end event

			driverIdCounter++;

		}
	}

	/**
	 * This method implements the activity-based carrier pseudo-simulation.
	 * It will not generate events anymore, instead, it will process the carrierplan activities (i.e., activity is now the main/minimal entity),
	 * and then it will output @FreightActivity and MATSim @Leg directly, for the sake of scoring.
	 */
	private Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> runActivityBasedCarrierSimulation(Carrier carrier) {
		CarrierPlan selectedPlan = carrier.getSelectedPlan();
		// create a map to store the driver (fake) and their corresponding Legs and Activities
		Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> driverLegsAndActivities = new HashMap<>();
		// for-loop to process each tour in the carrier plan
		int driverIdCounter = 1;
		// for-loop to process each tour in the carrier plan
		for (ScheduledTour tour: selectedPlan.getScheduledTours()) {
			double timeCursor = tour.getDeparture(); // start time of the tour, but it does not matter in this activity-based psim
			Id<Vehicle> vehicleId = tour.getVehicle().getId(); // route must have vehicle id set
			List<FreightActivity> freightActivities = new ArrayList<>();
			List<Leg> matsimLegs = new ArrayList<>();

			// First activity is the tour start activity
			Tour.TourElement prevElement = tour.getTour().getStart();
			// Get the TourElements list and for-loop through them to generate other events
			for (var el : tour.getTour().getTourElements()) {
				// if prevElement is TourActivity, this must be followed by a Leg
				if (prevElement instanceof Tour.TourActivity lastAct){
					assert el instanceof Tour.Leg thisLeg;
					// process the activity and leg
					// Create a MATSim Leg with travel time = expected travel time (which is calculated based on tt during routing stage of the carrier plan generation)
					Leg matsimLeg = PopulationUtils.createLeg(tour.getVehicle().getType().getNetworkMode());
					matsimLeg.setDepartureTime(((Tour.Leg) el).getExpectedDepartureTime());
					matsimLeg.setTravelTime(((Tour.Leg) el).getExpectedTransportTime());
					NetworkRoute nRoute = (NetworkRoute) ((Tour.Leg) el).getRoute();
					nRoute.setVehicleId(vehicleId);
					matsimLeg.setRoute(nRoute);

					// add to matsimLegs
					matsimLegs.add(matsimLeg);

					// create a FreightActivity based on lastAct and this leg time
					Activity basicActivity = PopulationUtils.createActivityFromLinkId(lastAct.getActivityType(),
							lastAct.getLocation());
					basicActivity.setStartTime(((Tour.Leg) el).getExpectedDepartureTime());
					basicActivity.setEndTime(lastAct.getDuration() + ((Tour.Leg) el).getExpectedDepartureTime());
					FreightActivity freightActivity = new FreightActivity(basicActivity, lastAct.getTimeWindow());
					// add to freightActivities
					freightActivities.add(freightActivity);
					// update the lastElement
					prevElement = el;
				} else if (prevElement instanceof Tour.Leg lastLeg) {
					assert el instanceof Tour.TourActivity act;
					prevElement = el;
				} else {
					throw new IllegalStateException("Unexpected tour element sequence: " + prevElement.getClass() + " followed by " + el.getClass());
				}
			}
			driverIdCounter++;
			driverLegsAndActivities.put(driverIdCounter, new Tuple<>(freightActivities, matsimLegs));
		}
		return  driverLegsAndActivities;
	}


	/**
	 * For the non-collaborating members, reset their plans to the original ones from the data store
	 */
	private void resetNonCollaboratingMembersPlans(Set<Id<?>> nonCollaboratingMembers, Map<Id<?>, FreightCollaborator<?>> copyPlayers){
		for (Id<?> nonCollaboratingMember : nonCollaboratingMembers) {
			FreightCollaborator<?> collaborator = copyPlayers.get(nonCollaboratingMember);
			if (collaborator == null) {
				// raise warning
				throw new IllegalStateException("No collaborator found for " + nonCollaboratingMember);
			}

			// Get the original plan from the data store
			var originalPlan = collaborationDataStore.getOriginalPlans().get(collaborator.getRole()).get(nonCollaboratingMember);
			if (originalPlan == null) {
				throw new IllegalStateException("No original plan found for " + nonCollaboratingMember);
			}

			// Reset the plan of the collaborator based on the role
			var delegate = collaborator.getDelegate();
			switch (collaborator.getRole()) {
				case CARRIER -> {
					if (delegate instanceof Carrier carrier &&
						originalPlan instanceof CarrierPlan carrierPlan) {
						carrier.setSelectedPlan(carrierPlan);
					}
				}
				case LSP -> {
					if (delegate instanceof LSP lsp &&
						originalPlan instanceof LSPPlan lspPlan) {
						lsp.setSelectedPlan(lspPlan);
					}
				}
				case RECEIVER -> {
					if (delegate instanceof Receiver receiver &&
						originalPlan instanceof ReceiverPlan receiverPlan) {
						receiver.setSelectedPlan(receiverPlan);
					}
				}
			}
		}
	}

	void setTravelTime(TravelTime travelTime) {
		this.tt = travelTime;
	}


}
