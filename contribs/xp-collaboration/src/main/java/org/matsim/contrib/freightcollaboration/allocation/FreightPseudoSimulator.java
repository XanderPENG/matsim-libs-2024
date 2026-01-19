package org.matsim.contrib.freightcollaboration.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.run.ScoringFunctionFactoryUsecase;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndLsp;
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
import org.matsim.freight.logistics.LSPCarrierResource;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPScorerFactory;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.vehicles.Vehicle;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
	/** Upper bound for jsprit iterations during sampling; keeps Shapley runs lightweight. */
	private int vrpMaxIterations = 100;
	/** Lighter bound used for sample/partial coalitions to speed approximate methods. */
	private int vrpSampleIterations = 40;

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

	/** Allow callers to trade accuracy for speed during sampling runs. */
	public void setVrpMaxIterations(int vrpMaxIterations) {
		if (vrpMaxIterations <= 0) {
			throw new IllegalArgumentException("vrpMaxIterations must be positive.");
		}
		this.vrpMaxIterations = vrpMaxIterations;
		// Keep sample iterations proportionally lower unless user overrides later.
		if (vrpSampleIterations >= vrpMaxIterations) {
			this.vrpSampleIterations = Math.max(10, vrpMaxIterations / 2);
		}
	}

	/** Optional override for sampling iterations (e.g., in Shapley Monte‑Carlo). */
	public void setVrpSampleIterations(int vrpSampleIterations) {
		if (vrpSampleIterations <= 0) {
			throw new IllegalArgumentException("vrpSampleIterations must be positive.");
		}
		this.vrpSampleIterations = vrpSampleIterations;
	}

	/** Simulate all possible sub-coalitions of the given players and distributors
	 * For each sub-coalition, record its scores and return them as a map
	 */
	Map<Set<Id<?>>, Double> runAllSubCoalitions(Map<Id<?>, FreightCollaborator<?>> distributors, Map<Id<?>, FreightCollaborator<?>> players) {
		LOGGER.info("Starting Freight Pseudo Simulator");
		// Generate all possible sub-coalitions of the given players
		var subCoalitionScoreMap = AllocationUtils.generateSubsets(players);
		ConcurrentHashMap<Set<Id<?>>, Double> result = new ConcurrentHashMap<>();
		subCoalitionScoreMap.keySet().parallelStream()
			.forEach(subCoalition -> result.put(Set.copyOf(subCoalition), simulateSubCoalition(distributors, players, subCoalition)));
		return result;
	}

	Map<Set<Id<?>>, Double> runSubCoalitions(Map<Id<?>, FreightCollaborator<?>> distributors,
											 Map<Id<?>, FreightCollaborator<?>> players,
											 Collection<Set<Id<?>>> subCoalitions) {
		ConcurrentHashMap<Set<Id<?>>, Double> subCoalitionScoreMap = new ConcurrentHashMap<>();
		subCoalitions.parallelStream()
			.forEach(subCoalition -> subCoalitionScoreMap.put(Set.copyOf(subCoalition),
				simulateSubCoalition(distributors, players, subCoalition)));
		return subCoalitionScoreMap;
	}

	double runSingleSubCoalition(Map<Id<?>, FreightCollaborator<?>> distributors,
								 Map<Id<?>, FreightCollaborator<?>> players,
								 Set<Id<?>> subCoalition) {
		return simulateSubCoalition(distributors, players, subCoalition);
	}

	private double simulateSubCoalition(Map<Id<?>, FreightCollaborator<?>> distributors,
									   Map<Id<?>, FreightCollaborator<?>> players,
									   Set<Id<?>> subCoalition) {
		// Deep copy the distributors and players maps for this sub-coalition, so that we can modify them safely
		Map<Id<?>, FreightCollaborator<?>> copyDistributors = AllocationUtils.deepCopyCollaboratorsMap(distributors);
		Map<Id<?>, FreightCollaborator<?>> copyPlayers = AllocationUtils.deepCopyCollaboratorsMap(players);

		Set<Id<?>> nonCollaboratingMembers;
		if (subCoalition.isEmpty()) {
			nonCollaboratingMembers = Set.copyOf(players.keySet());
		} else {
			nonCollaboratingMembers = AllocationUtils.identifyNonCollaboratingMembers(players.keySet(), subCoalition);
		}
		resetNonCollaboratingMembersPlans(nonCollaboratingMembers, copyPlayers);
		return runPSim(copyDistributors, copyPlayers, subCoalition);
	}


	@SuppressWarnings("unchecked")
	private double runPSim(Map<Id<?>, FreightCollaborator<?>> copyDistributors, Map<Id<?>, FreightCollaborator<?>> copyPlayers,
						   Set<Id<?>> collaboratingSubset) {
		var collaboratorRoleOfDistributors = copyDistributors.values().iterator().next().getRole();
		var collaboratorRoleOfPlayers = copyPlayers.values().iterator().next().getRole();
		// if it is carrier-receiver collaboration - runCarrierPSim
		if (CollaborationTypes.CARRIER_RECEIVER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)) {
			var carrierCollaborator = (FreightCollaborator<Carrier>) copyDistributors.values().iterator().next();
			var receiverCollaborators = new HashSet<>((Set<FreightCollaborator<Receiver>>) (Set<?>) Set.copyOf(copyPlayers.values()));
			Set<FreightCollaborator<Receiver>> nonCollaboratingReceivers = new HashSet<>();
			@SuppressWarnings("unchecked")
			Map<Id<?>, FreightCollaborator<Receiver>> globalReceivers = (Map<Id<?>, FreightCollaborator<Receiver>>) (Map<?, ?>) freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
			var allReceivers = AllocationUtils.deepCopyCollaboratorsMap(globalReceivers);
			allReceivers.values().forEach(receiverCollaborator -> {
				// if this receiver is not in the collaborating players for this carrier, check whether it is a non-collaborating receiver for this carrier
				if (!copyPlayers.containsKey(receiverCollaborator.getId())) {
					ReceiverPlan receiverPlan = receiverCollaborator.getTypedSelectedPlan();
					// check whether this receiver has orders for this carrier
					boolean hasOrdersForThisCarrier = receiverPlan.getReceiverOrders().stream()
							.anyMatch(order -> order.getCarrierId().equals(carrierCollaborator.getId()));
					if (hasOrdersForThisCarrier) {
						//FIXME: this is a non-collaborating receiver for this carrier, its plan should (or not?) be reset to the original one
						receiverCollaborator.getDelegate().setSelectedPlan((ReceiverPlan) collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER).get(receiverCollaborator.getId()));
						nonCollaboratingReceivers.add(receiverCollaborator);
					}
				}
			});
			// merge the non-collaborating receivers and the collaborating ones
			receiverCollaborators.addAll(nonCollaboratingReceivers);
			// need to re-generate the carrier plan based on the new receiver plans/requests
			boolean isFullCoalition = collaboratingSubset.size() == copyPlayers.size();
			int iterations = isFullCoalition ? vrpMaxIterations : vrpSampleIterations;
			LinkReceiverAndCarrier.receiversTriggerCarrierReplan(carrierCollaborator, receiverCollaborators,
					network, tt, iterations
			);
			// Then run the carrier PSim
			var driverLegsAndActivitiesMap = runActivityBasedCarrierSimulation(carrierCollaborator.getDelegate());
			// calculate the score based on the output legs and activities
			// TODO: inject the carrierScoringFunctionFactory properly
			CarrierPSimScorer carrierPSimScorer = new CarrierPSimScorer(driverLegsAndActivitiesMap,
				carrierCollaborator.getDelegate(), carrierScoringFunctionFactory);
			return carrierPSimScorer.getScore();

			}
		// TODO: The current implementation is rough and needs to be reimplemented properly
		else if (CollaborationTypes.LSP_RECEIVER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)) {
				var lspCollaborator = (FreightCollaborator<LSP>) copyDistributors.values().iterator().next();
				Set<Id<Carrier>> lspCarriersId = new HashSet<>();
				// Get all carriers of this lsp as the receivers are only connected to carriers
				lspCollaborator.getDelegate().getResources().forEach(resource -> {
					if (resource instanceof LSPCarrierResource carrierResource) {
						// Get the carrier
						Carrier carrier = carrierResource.getCarrier();
						lspCarriersId.add(carrier.getId());
					}
				});
				// DEEP Copy receivers
				var receiverCollaborators = new HashSet<>((Set<FreightCollaborator<Receiver>>) (Set<?>) Set.copyOf(copyPlayers.values()));
				Set<FreightCollaborator<Receiver>> nonCollaboratingReceivers = new HashSet<>();
				@SuppressWarnings("unchecked")
				Map<Id<?>, FreightCollaborator<Receiver>> globalReceivers = (Map<Id<?>, FreightCollaborator<Receiver>>) (Map<?, ?>) freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
				var allReceivers = AllocationUtils.deepCopyCollaboratorsMap(globalReceivers);
				allReceivers.values().forEach(receiverCollaborator -> {
					// if this receiver is not in the collaborating players for this lsp, check whether it is a non-collaborating receiver for this lsp
					if (!copyPlayers.containsKey(receiverCollaborator.getId())) {
						ReceiverPlan receiverPlan = receiverCollaborator.getTypedSelectedPlan();
						// check whether this receiver has orders for this carrier
						boolean hasOrdersForThisLsp = receiverPlan.getReceiverOrders().stream()
							.anyMatch(order -> lspCarriersId.contains(order.getCarrierId()));
						if (hasOrdersForThisLsp) {
							//FIXME: this is a non-collaborating receiver for this carrier, its plan should (or not?) be reset to the original one
							receiverCollaborator.getDelegate().setSelectedPlan((ReceiverPlan) collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER).get(receiverCollaborator.getId()));
							nonCollaboratingReceivers.add(receiverCollaborator);
						}
					}
				});
				// merge the non-collaborating receivers and the collaborating ones
				receiverCollaborators.addAll(nonCollaboratingReceivers);
				//Re-generate the LSP/carrier plan based on the new receiver plans/requests
				LSP newlyPlannedLsp = LinkReceiverAndLsp.receiversTriggerLspReplan(lspCollaborator,
					receiverCollaborators, network, tt, vrpMaxIterations, collaborationDataStore.getScenario());
				// Then run the LSP-receiver activity-based pseudo-simulation and scoring
				return runLspPseudoSimAndScoring(newlyPlannedLsp);
			} else if (CollaborationTypes.CARRIER_CARRIER.isCompatible(collaboratorRoleOfDistributors, collaboratorRoleOfPlayers)){
			// to be implemented
			throw new IllegalStateException("Unable to simulate Carrier-Carrier collaboration yet.");
		} else {
			throw new IllegalStateException("Unsupported collaboration type for PSim: " +
					collaboratorRoleOfDistributors + " - " + collaboratorRoleOfPlayers);
		}
	}

	/**
	 * This method implements the LSP pseudo-simulation and scoring.
	 * Generally, LSP does not need to handle activities, legs, events...
	 * The only thing need to do "psim" is the affiliated carriers' tours,
	 * hence, we can just use the existing carrier's logic to process it.
	 *
	 * TODO: Currently, it seems that we do not need the LspScoringFunctionFact as only the carrier scores will change,
	 * However, it should be kept here for future extension since the hub cost could change due to collaboration.
	 */
	private double runLspPseudoSimAndScoring(LSP lsp){
		Set<Carrier> affiliatedCarriers = new HashSet<>();
		lsp.getResources().forEach(r ->
			{
			if (r instanceof LSPCarrierResource lspCarrierResource) {
				affiliatedCarriers.add(lspCarrierResource.getCarrier());
				// Run the @RunActivityBasedCarrierSimulation for each affiliated carrier
				var driverLegsAndActivitiesMap = runActivityBasedCarrierSimulation(lspCarrierResource.getCarrier());
				// Score each affiliated carrier
				CarrierPSimScorer carrierPSimScorer = new CarrierPSimScorer(driverLegsAndActivitiesMap,
					lspCarrierResource.getCarrier(), carrierScoringFunctionFactory);
				// Set the score back to the carrier plan
				lspCarrierResource.getCarrier().getSelectedPlan().setScore(carrierPSimScorer.getScore());
			}
		});
		double totalLspScore = 0.0;
		// Sum up all affiliated carriers' scores as the LSP score
		for (Carrier carrier : affiliatedCarriers) {
			Double carrierPlanScore = carrier.getSelectedPlan().getScore();
			if (carrierPlanScore != null) {
				totalLspScore += carrierPlanScore;
			} else {
				throw new IllegalStateException("Carrier " + carrier.getId() + " has no score for the selected plan.");
			}
		}
		/*
		 * Add penalty for non-delivered shipments
		 */
		totalLspScore += ScoringFunctionFactoryUsecase.LSPScoringFunctionFactory.scoreNonDeliveredShipments(lsp);

		return totalLspScore;
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
