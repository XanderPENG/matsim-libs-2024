package org.matsim.contrib.freightcollaboration.utils;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.SchrimpfFactory;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;

import java.util.Collection;

/** Production {@link CarrierRouteSolver}. */
public final class JspritCarrierRouteSolver implements CarrierRouteSolver {
	private static final String JSPRIT_COMPUTATION_TIME_ATTRIBUTE = "jspritComputationTime";

	private final int maxIterations;

	public JspritCarrierRouteSolver(int maxIterations) {
		if (maxIterations < 1) {
			throw new IllegalArgumentException("maxIterations must be positive");
		}
		this.maxIterations = maxIterations;
	}

	@Override
	public CarrierPlan solve(Carrier carrier, Scenario scenario) {
		VehicleRoutingProblem.Builder builder = MatsimJspritFactory.createRoutingProblemBuilder(
			carrier, scenario.getNetwork());
		NetworkBasedTransportCosts costs = NetworkBasedTransportCosts.Builder.newInstance(
			scenario.getNetwork(), carrier.getCarrierCapabilities().getVehicleTypes()).build();
		VehicleRoutingProblem problem = builder.setRoutingCost(costs).build();
		VehicleRoutingAlgorithm algorithm = new SchrimpfFactory().createAlgorithm(problem);
		algorithm.setMaxIterations(maxIterations);
		long startedAt = System.nanoTime();
		Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
		if (solutions.isEmpty()) {
			throw new IllegalStateException("jsprit returned no carrier plan for " + carrier.getId());
		}
		CarrierPlan solvedPlan = MatsimJspritFactory.createPlan(Solutions.bestOf(solutions));
		NetworkRouter.routePlan(solvedPlan, costs);
		double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
		recordSolveMetadata(carrier, maxIterations, elapsedSeconds);
		return solvedPlan;
	}

	static void recordSolveMetadata(Carrier carrier, int iterations, double elapsedSeconds) {
		if (iterations < 1) {
			throw new IllegalArgumentException("iterations must be positive");
		}
		if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0.0) {
			throw new IllegalArgumentException("elapsedSeconds must be finite and non-negative");
		}
		CarriersUtils.setJspritIterations(carrier, iterations);
		Object current = carrier.getAttributes().getAttribute(JSPRIT_COMPUTATION_TIME_ATTRIBUTE);
		double previous = current instanceof Number number && Double.isFinite(number.doubleValue())
			&& number.doubleValue() >= 0.0 ? number.doubleValue() : 0.0;
		double cumulative = previous + elapsedSeconds;
		if (!Double.isFinite(cumulative)) {
			throw new IllegalStateException("Cumulative jsprit computation time is non-finite for "
				+ carrier.getId());
		}
		CarriersUtils.setJspritComputationTime(carrier, cumulative);
	}
}
