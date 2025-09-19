package org.matsim.contrib.xpreceiver.core;

import org.matsim.core.utils.misc.Time;

/**
 * Simple immutable time window representation.
 */
public record TimeWindow(double start, double end) {

    public static TimeWindow of(double start, double end) {
        if (end < start) {
            throw new IllegalArgumentException("End time must be after start time: " + Time.writeTime(start) + "-" + Time.writeTime(end));
        }
        return new TimeWindow(start, end);
    }

    public double duration() {
        return end - start;
    }
}
