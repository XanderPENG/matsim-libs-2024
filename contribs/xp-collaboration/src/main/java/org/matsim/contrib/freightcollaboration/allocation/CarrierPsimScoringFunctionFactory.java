package org.matsim.contrib.freightcollaboration.allocation;

import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

/**
 * Optional extension point for factories that distinguish normal carrier scoring from the
 * characteristic-function scoring used by the freight pseudo simulator.
 */
public interface CarrierPsimScoringFunctionFactory extends CarrierScoringFunctionFactory {

	default ScoringFunction createPsimScoringFunction(
			Carrier carrier, FreightCollaborationConfigGroup.PsimScoringMode mode) {
		return createScoringFunction(carrier);
	}
}
