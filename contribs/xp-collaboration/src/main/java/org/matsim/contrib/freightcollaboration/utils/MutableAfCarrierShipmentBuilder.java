package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.receiver.Order;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;

/** Rebuilds physical shipments from the currently selected Receiver plans. */
public final class MutableAfCarrierShipmentBuilder {

	private MutableAfCarrierShipmentBuilder() {
	}

	public static void rebuild(Scenario scenario) {
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			carrier.getShipments().clear();
			carrier.getServices().clear();
		}

		int sequence = 0;
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			ReceiverPlan receiverPlan = receiver.getSelectedPlan();
			if (receiverPlan == null) {
				throw new IllegalStateException("Receiver has no selected plan: " + receiver.getId());
			}
			if (receiverPlan.getTimeWindows().isEmpty()) {
				throw new IllegalStateException("Receiver plan has no delivery time window: " + receiver.getId());
			}
			for (ReceiverOrder receiverOrder : receiverPlan.getReceiverOrders()) {
				Carrier carrier = receiverOrder.getCarrier();
				if (carrier == null) {
					throw new IllegalStateException("Receiver order is not linked to a carrier: " + receiver.getId());
				}
				for (Order order : receiverOrder.getReceiverProductOrders()) {
					sequence++;
					CarrierShipment shipment = CarrierShipment.Builder.newInstance(
							Id.create("Order" + receiver.getId() + sequence, CarrierShipment.class),
							order.getProduct().getProductType().getOriginLinkId(),
							order.getReceiver().getLinkId(),
							(int) Math.round(order.getDailyOrderQuantity()
								* order.getProduct().getProductType().getRequiredCapacity()))
						.setDeliveryDuration(order.getServiceDuration())
						.setDeliveryStartingTimeWindow(receiverPlan.getTimeWindows().getFirst())
						.build();
					if (shipment.getCapacityDemand() != 0) {
						CarriersUtils.addShipment(carrier, shipment);
					}
				}
			}
		}
	}
}
