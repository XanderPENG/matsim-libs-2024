package org.matsim.contrib.freightcollaboration.utils;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.SchrimpfFactory;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.receiver.*;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class LinkReceiverAndCarrier {

	/** Cache transport costs per (carrier, travelTime instance) because those are immutable once built. */
	private static final Map<String, NetworkBasedTransportCosts> COSTS_CACHE = new ConcurrentHashMap<>();
	/** Cache best VRP solutions per (carrier, receiver subset, travelTime instance) to warm‑start jsprit. */
	private static final Map<String, VehicleRoutingProblemSolution> VRP_SOLUTION_CACHE = new ConcurrentHashMap<>();
	private static volatile int CURRENT_TT_TOKEN = Integer.MIN_VALUE;
	private static final Object CACHE_LOCK = new Object();

	/**
	 * Re-generate a carrier plan based on the receivers that collaborate in the sampled coalition.
	 * Uses a lightweight jsprit solve bounded by {@code maxIterations} to keep Shapley sampling fast.
	 */
	public static void receiversTriggerCarrierReplan(FreightCollaborator<Carrier> carrierCollaborator,
													 Set<FreightCollaborator<Receiver>> receiverCollaborators,
													 Network network, TravelTime tt, int maxIterations) {
		if (maxIterations <= 0) {
			throw new IllegalArgumentException("maxIterations must be positive.");
		}
		java.util.Objects.requireNonNull(carrierCollaborator, "carrierCollaborator");
		java.util.Objects.requireNonNull(receiverCollaborators, "receiverCollaborators");
		java.util.Objects.requireNonNull(network, "network");
		java.util.Objects.requireNonNull(tt, "tt");
		final int ttToken = System.identityHashCode(tt);
		// If travel time object changed (new mobsim iteration), drop caches to avoid unbounded growth and stale warm-starts.
		if (ttToken != CURRENT_TT_TOKEN) {
			synchronized (CACHE_LOCK) {
				if (ttToken != CURRENT_TT_TOKEN) {
					COSTS_CACHE.clear();
					VRP_SOLUTION_CACHE.clear();
					CURRENT_TT_TOKEN = ttToken;
				}
			}
		}
		final Carrier carrier = carrierCollaborator.getDelegate();
		final Set<Id<Receiver>> receiverIds = receiverCollaborators.stream()
			.map(fc->fc.getDelegate().getId())
			.collect(Collectors.toUnmodifiableSet());
		final String cacheKey = subsetKey(carrier.getId(), receiverIds, ttToken);

		// Clean the carrier's current plan and services/shipments
		carrier.clearPlans();
		carrier.getShipments().clear();
		carrier.getServices().clear();

		// For each receiver collaborator, get its current plan
		for (FreightCollaborator<Receiver> receiverCollaborator : receiverCollaborators) {
			Receiver receiver = receiverCollaborator.getDelegate();
			ReceiverPlan receiverPlan = receiver.getSelectedPlan();
			// From the receiver's current plan, get the orders and for-loop to process them
			for (ReceiverOrder order: receiverPlan.getReceiverOrders()){
				// If the order is for this carrier, add the corresponding services/shipments to the carrier
				if (order.getCarrierId().equals(carrier.getId())) {
					// Give a unique ID for each shipment
					int order_num = 0;
					for (Order productOrder : order.getReceiverProductOrders()) {
						// Create shipment for each order
						CarrierShipment.Builder builder = CarrierShipment.Builder.newInstance(
								Id.create("Order_" + receiver.getId().toString() + "_" + order_num, CarrierShipment.class),
								productOrder.getProduct().getProductType().getOriginLinkId(),
								productOrder.getReceiver().getLinkId(),
								(int) (Math.round(productOrder.getDailyOrderQuantity() * productOrder.getProduct().getProductType().getRequiredCapacity()))
						);
						CarrierShipment newShipment = builder.setDeliveryDuration(productOrder.getServiceDuration())
								// TODO: here we just use the first time window, need to be improved if multiple time windows exist
								.setDeliveryStartingTimeWindow( receiverPlan.getTimeWindows().getFirst())
								.build();

						carrier.getShipments().put(newShipment.getId(), newShipment);
						order_num++;
					}
				}

			}
		}

		// After processing all orders, we may want to create a new plan for the carrier
		NetworkBasedTransportCosts netBasedCosts = COSTS_CACHE.computeIfAbsent(
			costCacheKey(carrier.getId(), ttToken),
			ignored -> NetworkBasedTransportCosts.Builder.newInstance(network,
					carrier.getCarrierCapabilities().getVehicleTypes())
				.setTravelTime(tt)
				.build());

		VehicleRoutingProblem.Builder vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network)
			.setRoutingCost(netBasedCosts);
		VehicleRoutingProblem vrp = vrpBuilder.setRoutingCost(netBasedCosts).build();
		// New a VRP algorithm and search for solutions
		VehicleRoutingAlgorithm vra = new SchrimpfFactory().createAlgorithm(vrp);
		VehicleRoutingProblemSolution warmStart = VRP_SOLUTION_CACHE.get(cacheKey);
		if (warmStart != null) {
			try {
				var addInitial = vra.getClass().getMethod("addInitialSolution", VehicleRoutingProblemSolution.class);
				addInitial.invoke(vra, warmStart);
			} catch (Exception reflectionFailure) {
				// Best-effort warm-start; fall back silently if jsprit version differs.
			}
		}
		vra.setMaxIterations(maxIterations);
		Collection<VehicleRoutingProblemSolution> solutions = vra.searchSolutions();
		if (solutions == null || solutions.isEmpty()) {
			throw new IllegalStateException("No feasible carrier plan found for " + carrier.getId());
		}
		// Create a new carrierPlan from the best solution
		VehicleRoutingProblemSolution bestSolution = Solutions.bestOf(solutions);
		VRP_SOLUTION_CACHE.put(cacheKey, bestSolution);
		CarrierPlan newPlan = MatsimJspritFactory.createPlan(bestSolution);
		// Route plan so as to add routes to the plan
		NetworkRouter.routePlan(newPlan, netBasedCosts);
		// Assign this plan now to the carrier and make it the selected carrier plan
		carrier.addPlan(newPlan);
		carrier.setSelectedPlan(newPlan);
	}

	private static String subsetKey(Id<Carrier> carrierId, Set<Id<Receiver>> receiverIds, int ttToken) {
		// Deterministic key: carrier + sorted receiver ids + token of travel time (captures iteration TT changes).
		String receivers = receiverIds.stream()
			.map(Id::toString)
			.sorted()
			.collect(Collectors.joining(","));
		return carrierId + "|" + ttToken + "|" + receivers;
	}

	private static String costCacheKey(Id<Carrier> carrierId, int ttToken) {
		return carrierId + "|" + ttToken;
	}

	@SuppressWarnings("unchecked")
	public static Set<FreightCollaborator<Receiver>> findLinkedReceivers(Carrier carrierFreightCollaborator, FreightCollaborators freightCollaborators){
		Set<FreightCollaborator<Receiver>> linkedReceivers = new java.util.HashSet<>();
		for (FreightCollaborator<?> receiverCollaborator : freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER).values()) {
			if (!(receiverCollaborator.getDelegate() instanceof Receiver)) continue;
			Receiver receiver = (Receiver) receiverCollaborator.getDelegate();
			if (receiver == null) continue;
			ReceiverPlan receiverPlan = receiver.getSelectedPlan();
			if (receiverPlan == null) continue;
			// Check if this receiver has any orders linked to the given carrier
			for (ReceiverOrder order : receiverPlan.getReceiverOrders()){
				if (order == null || order.getCarrierId() == null) continue;
				if (order.getCarrierId().equals(carrierFreightCollaborator.getId())) {
					linkedReceivers.add((FreightCollaborator<Receiver>) receiverCollaborator);
					break; // No need to check further orders for this receiver
				}
			}
		}
		return linkedReceivers;
	}
}
