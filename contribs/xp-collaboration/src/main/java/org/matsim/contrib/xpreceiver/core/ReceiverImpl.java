package org.matsim.contrib.xpreceiver.core;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ReceiverImpl implements Receiver {
    private final Id<Receiver> id;
    private Id<Link> linkId;
    private final List<ReceiverPlan> plans = new ArrayList<>();
    private ReceiverPlan selectedPlan;
    private final Attributes attributes = new AttributesImpl();
    private double initialCost = 0.0;

    public ReceiverImpl(Id<Receiver> id) {
        this.id = id;
    }

    @Override
    public Id<Receiver> getId() {
        return id;
    }

    @Override
    public List<ReceiverPlan> getPlans() {
        return Collections.unmodifiableList(plans);
    }

    @Override
    public boolean addPlan(ReceiverPlan plan) {
        Objects.requireNonNull(plan, "plan");
        boolean added = plans.add(plan);
        if (added && selectedPlan == null) {
            selectedPlan = plan;
        }
        return added;
    }

    @Override
    public boolean removePlan(ReceiverPlan plan) {
        if (plan == null) {
            return false;
        }
        boolean removed = plans.remove(plan);
        if (removed && plan == selectedPlan) {
            selectedPlan = plans.isEmpty() ? null : plans.get(0);
        }
        return removed;
    }

    @Override
    public ReceiverPlan getSelectedPlan() {
        return selectedPlan;
    }

    @Override
    public void setSelectedPlan(ReceiverPlan selectedPlan) {
        if (!plans.contains(selectedPlan)) {
            throw new IllegalArgumentException("Plan must belong to receiver before it can be selected.");
        }
        this.selectedPlan = selectedPlan;
    }

    @Override
    public ReceiverPlan createCopyOfSelectedPlanAndMakeSelected() {
        if (selectedPlan == null) {
            throw new IllegalStateException("Cannot copy plan when none is selected.");
        }
        ReceiverPlan copy = selectedPlan.copy();
        addPlan(copy);
        setSelectedPlan(copy);
        return copy;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public Id<Link> getLinkId() {
        return linkId;
    }

    @Override
    public Receiver setLinkId(Id<Link> linkId) {
        this.linkId = linkId;
        return this;
    }

    @Override
    public void setInitialCost(double cost) {
        this.initialCost = cost;
    }

    @Override
    public double getInitialCost() {
        return initialCost;
    }
}
