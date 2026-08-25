package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JspritCarrierRouteSolverTest {

	@Test
	void recordsConfiguredIterationsAndAccumulatesComputationTimeAcrossSolves() {
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));

		JspritCarrierRouteSolver.recordSolveMetadata(carrier, 200, 0.25);
		assertEquals(200, CarriersUtils.getJspritIterations(carrier));
		assertEquals(0.25, CarriersUtils.getJspritComputationTime(carrier), 1e-12);

		JspritCarrierRouteSolver.recordSolveMetadata(carrier, 200, 0.75);
		assertEquals(200, CarriersUtils.getJspritIterations(carrier));
		assertEquals(1.0, CarriersUtils.getJspritComputationTime(carrier), 1e-12);
	}

	@Test
	void rejectsInvalidSolveMetadata() {
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		assertThrows(IllegalArgumentException.class,
			() -> JspritCarrierRouteSolver.recordSolveMetadata(carrier, 0, 1.0));
		assertThrows(IllegalArgumentException.class,
			() -> JspritCarrierRouteSolver.recordSolveMetadata(carrier, 1, Double.NaN));
		assertThrows(IllegalArgumentException.class,
			() -> JspritCarrierRouteSolver.recordSolveMetadata(carrier, 1, -0.1));
	}
}
