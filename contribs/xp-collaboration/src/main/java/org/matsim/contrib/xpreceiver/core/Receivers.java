package org.matsim.contrib.xpreceiver.core;

import org.matsim.api.core.v01.Id;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Container for all receivers in a scenario.
 */
public final class Receivers {
    private final Map<Id<Receiver>, Receiver> receivers = new LinkedHashMap<>();
    private final Attributes attributes = new AttributesImpl();

    public Receivers() {
    }

    public Map<Id<Receiver>, Receiver> getReceivers() {
        return Collections.unmodifiableMap(receivers);
    }

    public Collection<Receiver> values() {
        return receivers.values();
    }

    public void addReceiver(Receiver receiver) {
        receivers.put(receiver.getId(), receiver);
    }

    public Receiver getReceiver(Id<Receiver> id) {
        return receivers.get(id);
    }

    public Attributes getAttributes() {
        return attributes;
    }
}
