package org.matsim.contrib.xpreceiver.core;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.utils.objectattributes.attributable.Attributable;

/**
 * Public receiver interface exposing id, access link and plan handling.
 */
public interface Receiver extends HasPlansAndId<ReceiverPlan, Receiver>, Attributable {
    Id<Link> getLinkId();

    Receiver setLinkId(Id<Link> linkId);

    void setInitialCost(double cost);

    double getInitialCost();
}
