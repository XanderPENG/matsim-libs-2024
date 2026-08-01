package org.matsim.contrib.freightcollaboration.listener;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.SchrimpfFactory;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.util.Solutions;
import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierShipment;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.jsprit.MatsimJspritFactory;
import org.matsim.freight.carriers.jsprit.NetworkBasedTransportCosts;
import org.matsim.freight.carriers.jsprit.NetworkRouter;
import org.matsim.freight.receiver.Order;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverConfigGroup;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Mutable-factor-specific replacement for receiver-triggered carrier replanning.
 * It updates physical routes without destroying the carrier's factor plan memory.
 */
public final class PreservingReceiverTriggeredCarrierReplanningListener implements BeforeMobsimListener {

	private static final Logger LOG = LogManager.getLogger(
		PreservingReceiverTriggeredCarrierReplanningListener.class);

	private final Scenario scenario;
	private final ReceiverConfigGroup receiverConfig;
	private final MutableAllocationFactorConfigGroup mutableConfig;
	private final CarrierRouteSolver routeSolver;

	@Inject
	public PreservingReceiverTriggeredCarrierReplanningListener(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig) {
		this(scenario, mutableConfig, new JspritCarrierRouteSolver(
			ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCollaborationConfigGroup.class)
				.getVrpMaxIterations()));
	}

	PreservingReceiverTriggeredCarrierReplanningListener(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, CarrierRouteSolver routeSolver) {
		this.scenario = Objects.requireNonNull(scenario, "scenario");
		this.receiverConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		this.mutableConfig = Objects.requireNonNull(mutableConfig, "mutableConfig");
		this.routeSolver = Objects.requireNonNull(routeSolver, "routeSolver");
		mutableConfig.validateGrid();
	}

	@Override
	public double priority() {
		return 100.0;
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		int interval = receiverConfig.getReceiverReplanningInterval();
		if (interval <= 0) {
			throw new IllegalStateException("Receiver replanning interval must be positive.");
		}
		if (event.getIteration() > scenario.getConfig().controller().getFirstIteration()
			&& (event.getIteration() + 1) % interval != 0) {
			return;
		}

		LOG.info("Receiver plans trigger carrier VRP rebuilding while preserving factor plan memory.");
		CollaborationUtils.setCoalitionFromReceiverAttributes(scenario);
		rebuildCarrierShipments();
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			CarrierPlan solvedPlan = routeSolver.solve(carrier, scenario);
			installSolvedRoute(carrier, solvedPlan);
		}
	}

	private void rebuildCarrierShipments() {
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			carrier.getShipments().clear();
			carrier.getServices().clear();
		}

		int sequence = 0;
		for (Receiver receiver : ReceiverUtils.getReceivers(scenario).getReceivers().values()) {
			ReceiverPlan receiverPlan = receiver.getSelectedPlan();
			if (receiverPlan == null) {
				throw new IllegalStateException("Receiver has no selected plan: " + receiver.getId());
			}
			if (receiverPlan.getTimeWindows().isEmpty()) {
				throw new IllegalStateException("Receiver plan has no delivery time window: " + receiver.getId());
			}
			for (ReceiverOrder receiverOrder : receiverPlan.getReceiverOrders()) {
				for (Order order : receiverOrder.getReceiverProductOrders()) {
					sequence++;
					CarrierShipment shipment = CarrierShipment.Builder.newInstance(
							Id.create("Order" + receiver.getId() + sequence, CarrierShipment.class),
							order.getProduct().getProductType().getOriginLinkId(),
							order.getReceiver().getLinkId(),
							(int) Math.round(order.getDailyOrderQuantity()
								* order.getProduct().getProductType().getRequiredCapacity()))
						.setDeliveryDuration(order.getServiceDuration())
						.setDeliveryStartingTimeWindow(receiverPlan.getTimeWindows().getFirst())
						.build();
					if (shipment.getCapacityDemand() != 0) {
						CarriersUtils.addShipment(receiverOrder.getCarrier(), shipment);
					}
				}
			}
		}
	}

	private void installSolvedRoute(Carrier carrier, CarrierPlan solvedPlan) {
		List<CarrierPlan> candidates = new ArrayList<>(carrier.getPlans());
		CarrierPlan selected = carrier.getSelectedPlan();
		if (candidates.isEmpty()) {
			CarrierAllocationFactor.set(solvedPlan, mutableConfig.getInitialAllocationFactor(), mutableConfig);
			solvedPlan.setScore(null);
			carrier.addPlan(solvedPlan);
			carrier.setSelectedPlan(solvedPlan);
			return;
		}
		if (selected == null || !candidates.contains(selected)) {
			throw new IllegalStateException("Carrier plan memory has no valid selected plan: " + carrier.getId());
		}

		for (CarrierPlan candidate : candidates) {
			if (CarrierAllocationFactor.find(candidate).isEmpty()) {
				CarrierAllocationFactor.set(candidate, mutableConfig.getInitialAllocationFactor(), mutableConfig);
			} else {
				CarrierAllocationFactor.require(candidate, mutableConfig);
			}
			Collection<ScheduledTour> scheduledTours = candidate.getScheduledTours();
			scheduledTours.clear();
			for (ScheduledTour solvedTour : solvedPlan.getScheduledTours()) {
				scheduledTours.add(ScheduledTour.newInstance(solvedTour.getTour().duplicate(),
					solvedTour.getVehicle(), solvedTour.getDeparture()));
			}
		}
		carrier.setSelectedPlan(selected);
	}

	@FunctionalInterface
	interface CarrierRouteSolver {
		CarrierPlan solve(Carrier carrier, Scenario scenario);
	}

	private record JspritCarrierRouteSolver(int maxIterations) implements CarrierRouteSolver {
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
			CarrierPlan solvedPlan = MatsimJspritFactory.createPlan(carrier, Solutions.bestOf(solutions));
			NetworkRouter.routePlan(solvedPlan, costs);
			return solvedPlan;
		}
	}
}
