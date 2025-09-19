package org.matsim.contrib.xpreceiver.config;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Configuration for the xp-receiver module.
 */
public class ReceiverConfigGroup extends ReflectiveConfigGroup {
    public static final String NAME = "xpReceiver";

    private static final String REPLAN_INTERVAL = "replanInterval";
    private static final String TIME_WINDOW_STEP = "timeWindowStep";
    private static final String REPLANNING_TYPE = "replanningType";

    private int receiverReplanningInterval = 1;
    private double timeWindowMutationStep = 900.0;
    private ReceiverReplanningType replanningType = ReceiverReplanningType.timeWindow;

    public ReceiverConfigGroup() {
        super(NAME);
    }

    @StringGetter(REPLAN_INTERVAL)
    public int getReceiverReplanningInterval() {
        return receiverReplanningInterval;
    }

    @StringSetter(REPLAN_INTERVAL)
    public void setReceiverReplanningInterval(int receiverReplanningInterval) {
        this.receiverReplanningInterval = Math.max(1, receiverReplanningInterval);
    }

    @StringGetter(TIME_WINDOW_STEP)
    public double getTimeWindowMutationStep() {
        return timeWindowMutationStep;
    }

    @StringSetter(TIME_WINDOW_STEP)
    public void setTimeWindowMutationStep(double timeWindowMutationStep) {
        this.timeWindowMutationStep = Math.max(0.0, timeWindowMutationStep);
    }

    @StringGetter(REPLANNING_TYPE)
    public String getReplanningTypeAsString() {
        return replanningType.name();
    }

    @StringSetter(REPLANNING_TYPE)
    public void setReplanningTypeAsString(String type) {
        this.replanningType = type == null ? ReceiverReplanningType.none : ReceiverReplanningType.valueOf(type);
    }

    public ReceiverReplanningType getReplanningType() {
        return replanningType;
    }

    public void setReplanningType(ReceiverReplanningType replanningType) {
        this.replanningType = replanningType;
    }
}
