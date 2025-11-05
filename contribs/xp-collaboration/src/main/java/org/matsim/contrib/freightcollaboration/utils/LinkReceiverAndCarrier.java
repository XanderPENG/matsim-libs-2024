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
import java.util.Set;

public class LinkReceiverAndCarrier {

	/**
	 * This method aims to re-generate carrierplan based on these changed receiver plans.
	 * It should be called after receivers have changed their plans (e.g., reset the receivers' plans to original ones during the PSim)
	 */
	public static void receiversTriggerCarrierReplan(FreightCollaborator<Carrier> carrierCollaborator,
													 Set<FreightCollaborator<Receiver>> receiverCollaborators,
													 Network network, TravelTime tt) {
		// Clean the carrier's current plan and services/shipments
		Carrier carrier = carrierCollaborator.getDelegate();
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
		VehicleRoutingProblem.Builder vrpBuilder = MatsimJspritFactory.createRoutingProblemBuilder(carrier, network);
		NetworkBasedTransportCosts netBasedCosts = NetworkBasedTransportCosts.Builder.newInstance(network,
			carrier.getCarrierCapabilities().getVehicleTypes())
			.setTravelTime(tt). // Set travel time from the MATSim simulation
			build();
		VehicleRoutingProblem vrp = vrpBuilder.setRoutingCost(netBasedCosts).build();
		// New a VRP algorithm and search for solutions
		VehicleRoutingAlgorithm vra = new SchrimpfFactory().createAlgorithm(vrp);
		Collection<VehicleRoutingProblemSolution> solutions = vra.searchSolutions();
		// Create a new carrierPlan from the best solution
		CarrierPlan newPlan = MatsimJspritFactory.createPlan(carrier, Solutions.bestOf(solutions));
		// Route plan so as to add routes to the plan
		NetworkRouter.routePlan(newPlan, netBasedCosts);
		// Assign this plan now to the carrier and make it the selected carrier plan
		carrier.addPlan(newPlan);
		carrier.setSelectedPlan(newPlan);
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
