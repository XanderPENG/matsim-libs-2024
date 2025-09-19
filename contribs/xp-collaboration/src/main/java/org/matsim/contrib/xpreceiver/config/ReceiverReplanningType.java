package org.matsim.contrib.xpreceiver.config;

/**
 * Supported receiver replanning modes for the lightweight xp-receiver module.
 */
public enum ReceiverReplanningType {
    /**
     * Do not run any replanning logic.
     */
    none,

    /**
     * Mutate receiver order time windows.
     */
    timeWindow
}
