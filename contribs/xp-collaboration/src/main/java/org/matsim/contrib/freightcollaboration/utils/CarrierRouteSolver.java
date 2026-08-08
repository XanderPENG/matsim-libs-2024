package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;

/** Injectable boundary around the expensive jsprit route solve. */
@FunctionalInterface
public interface CarrierRouteSolver {
	CarrierPlan solve(Carrier carrier, Scenario scenario);
}
