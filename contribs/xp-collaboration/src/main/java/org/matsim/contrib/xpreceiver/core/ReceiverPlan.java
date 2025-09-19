package org.matsim.contrib.xpreceiver.core;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.freight.carriers.Carrier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple receiver plan that groups per-carrier orders.
 */
public final class ReceiverPlan implements BasicPlan {
    private final Map<Id<Carrier>, ReceiverOrder> orders = new LinkedHashMap<>();
    private Double score;
    private String type = BasicPlan.UNDEFINED_PLAN_TYPE;

    public ReceiverPlan addOrder(ReceiverOrder order) {
        Objects.requireNonNull(order, "order");
        orders.put(order.getCarrierId(), order);
        return this;
    }

    public Collection<ReceiverOrder> getOrders() {
        return orders.values();
    }

    public ReceiverOrder getOrder(Id<Carrier> carrierId) {
        return orders.get(carrierId);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public ReceiverPlan copy() {
        ReceiverPlan copy = new ReceiverPlan();
        copy.score = this.score;
        copy.type = this.type;
        orders.values().forEach(order -> copy.addOrder(order.toBuilder().build()));
        return copy;
    }

    @Override
    public void setScore(Double score) {
        this.score = score;
    }

    @Override
    public Double getScore() {
        return score;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String getType() {
        return type;
    }
}
