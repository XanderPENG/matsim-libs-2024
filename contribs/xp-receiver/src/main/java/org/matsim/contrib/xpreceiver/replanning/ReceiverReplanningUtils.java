package org.matsim.contrib.xpreceiver.replanning;

import com.google.inject.Inject;
import com.google.inject.Provider;
import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;

/**
 * Provides strategy managers based on configuration.
 */
public final class ReceiverReplanningUtils {
    private ReceiverReplanningUtils() {
    }

    public static Provider<ReceiverStrategyManager> timeWindowStrategyProvider() {
        return new Provider<>() {
            @Inject
            ReceiverConfigGroup configGroup;

            @Override
            public ReceiverStrategyManager get() {
                return new TimeWindowStrategyManager(configGroup.getTimeWindowMutationStep());
            }
        };
    }
}
