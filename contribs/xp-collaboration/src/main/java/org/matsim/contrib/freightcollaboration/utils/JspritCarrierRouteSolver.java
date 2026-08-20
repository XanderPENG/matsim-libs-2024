package org.matsim.contrib.freightcollaboration.utils;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.SchrimpfFactory;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import org.matsim.api.core.v01.Scenario;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;

import java.util.Collection;

/** Production {@link CarrierRouteSolver}. */
public final class JspritCarrierRouteSolver implements CarrierRouteSolver {

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
		Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
		if (solutions.isEmpty()) {
			throw new IllegalStateException("jsprit returned no carrier plan for " + carrier.getId());
		}
		CarrierPlan solvedPlan = MatsimJspritFactory.createPlan(Solutions.bestOf(solutions));
		NetworkRouter.routePlan(solvedPlan, costs);
		return solvedPlan;
	}
}
