package org.matsim.contrib.xpreceiver.core;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.freight.carriers.Carrier;

import java.util.Objects;

/**
 * Minimal receiver order that links a receiver to a carrier and expected delivery window.
 */
public final class ReceiverOrder {
    private final Id<Carrier> carrierId;
    private final Id<Link> deliveryLinkId;
    private final double quantity;
    private final double serviceDuration;
    private final TimeWindow timeWindow;
    private final double carrierCost;

    private ReceiverOrder(Builder builder) {
        this.carrierId = Objects.requireNonNull(builder.carrierId, "carrierId");
        this.deliveryLinkId = Objects.requireNonNull(builder.deliveryLinkId, "deliveryLinkId");
        this.quantity = builder.quantity;
        this.serviceDuration = builder.serviceDuration;
        this.timeWindow = Objects.requireNonNull(builder.timeWindow, "timeWindow");
        this.carrierCost = builder.carrierCost;
    }

    public Id<Carrier> getCarrierId() {
        return carrierId;
    }

    public Id<Link> getDeliveryLinkId() {
        return deliveryLinkId;
    }

    public double getQuantity() {
        return quantity;
    }

    public double getServiceDuration() {
        return serviceDuration;
    }

    public TimeWindow getTimeWindow() {
        return timeWindow;
    }

    public double getCarrierCost() {
        return carrierCost;
    }

    public Builder toBuilder() {
        return new Builder(carrierId)
                .setDeliveryLinkId(deliveryLinkId)
                .setQuantity(quantity)
                .setServiceDuration(serviceDuration)
                .setTimeWindow(timeWindow)
                .setCarrierCost(carrierCost);
    }

    public static Builder builder(Id<Carrier> carrierId) {
        return new Builder(carrierId);
    }

    public static final class Builder {
        private final Id<Carrier> carrierId;
        private Id<Link> deliveryLinkId;
        private double quantity = 0.0;
        private double serviceDuration = 0.0;
        private TimeWindow timeWindow = TimeWindow.of(0.0, 0.0);
        private double carrierCost = 0.0;

        private Builder(Id<Carrier> carrierId) {
            this.carrierId = carrierId;
        }

        public Builder setDeliveryLinkId(Id<Link> deliveryLinkId) {
            this.deliveryLinkId = deliveryLinkId;
            return this;
        }

        public Builder setQuantity(double quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder setServiceDuration(double serviceDuration) {
            this.serviceDuration = serviceDuration;
            return this;
        }

        public Builder setTimeWindow(TimeWindow timeWindow) {
            this.timeWindow = timeWindow;
            return this;
        }

        public Builder setCarrierCost(double carrierCost) {
            this.carrierCost = carrierCost;
            return this;
        }

        public ReceiverOrder build() {
            return new ReceiverOrder(this);
        }
    }
}
