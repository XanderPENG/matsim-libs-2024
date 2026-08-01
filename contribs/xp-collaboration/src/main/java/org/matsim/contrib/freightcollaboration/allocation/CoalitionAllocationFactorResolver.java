package org.matsim.contrib.freightcollaboration.allocation;

import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;

@FunctionalInterface
public interface CoalitionAllocationFactorResolver {
	double resolve(MutableFreightCoalition coalition);

	static double requireValid(CoalitionAllocationFactorResolver resolver,
			MutableFreightCoalition coalition) {
		double factor = resolver.resolve(coalition);
		if (!Double.isFinite(factor) || factor < 0.0 || factor > 1.0) {
			throw new IllegalArgumentException("Resolved allocation factor must be in [0,1], got " + factor);
		}
		return factor;
	}
}
