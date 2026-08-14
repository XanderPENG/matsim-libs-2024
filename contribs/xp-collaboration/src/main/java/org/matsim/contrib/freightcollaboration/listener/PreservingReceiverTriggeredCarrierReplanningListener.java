package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.contrib.freightcollaboration.utils.CarrierRouteSolver;
import org.matsim.contrib.freightcollaboration.utils.JspritCarrierRouteSolver;
import org.matsim.contrib.freightcollaboration.utils.MutableAfCarrierShipmentBuilder;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.receiver.ReceiverConfigGroup;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import java.util.Objects;

/**
 * Mutable-factor replacement for the destructive receiver-triggered carrier listener. Only the
 * selected factor plan is a working plan; dormant factor plans remain untouched.
 */
public final class PreservingReceiverTriggeredCarrierReplanningListener implements BeforeMobsimListener {

	private static final Logger LOG = LogManager.getLogger(
		PreservingReceiverTriggeredCarrierReplanningListener.class);

	private final Scenario scenario;
	private final ReceiverConfigGroup receiverConfig;
	private final MutableAllocationFactorConfigGroup mutableConfig;
	private final MutableAfLearningStore learningStore;
	private final CarrierRouteSolver routeSolver;

	@Inject
	public PreservingReceiverTriggeredCarrierReplanningListener(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, MutableAfLearningStore learningStore) {
		this(scenario, mutableConfig, learningStore, new JspritCarrierRouteSolver(
			ConfigUtils.addOrGetModule(scenario.getConfig(), FreightCollaborationConfigGroup.class)
				.getVrpMaxIterations()));
	}

	PreservingReceiverTriggeredCarrierReplanningListener(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, MutableAfLearningStore learningStore,
			CarrierRouteSolver routeSolver) {
		this.scenario = Objects.requireNonNull(scenario, "scenario");
		this.receiverConfig = ConfigUtils.addOrGetModule(scenario.getConfig(), ReceiverConfigGroup.class);
		this.mutableConfig = Objects.requireNonNull(mutableConfig, "mutableConfig");
		this.learningStore = learningStore;
		this.routeSolver = Objects.requireNonNull(routeSolver, "routeSolver");
		mutableConfig.validateGrid();
	}

	/** Compatibility constructor retained for focused listener tests. */
	PreservingReceiverTriggeredCarrierReplanningListener(Scenario scenario,
			MutableAllocationFactorConfigGroup mutableConfig, CarrierRouteSolver routeSolver) {
		this(scenario, mutableConfig, null, routeSolver);
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
		boolean warmStartTrial = learningStore != null && learningStore.hasPendingWarmStartTrial();
		boolean finalExecution = learningStore != null && learningStore.hasPendingFinalExecution();
		boolean normallyDue = event.getIteration() <= scenario.getConfig().controller().getFirstIteration()
			|| (event.getIteration() + 1) % interval == 0;
		if (!normallyDue && !warmStartTrial && !finalExecution) {
			return;
		}
		if (warmStartTrial) {
			learningStore.beginWarmStartExecution(event.getIteration());
		}
		if (finalExecution) {
			learningStore.beginFinalExecution(event.getIteration());
		}

		LOG.info("Rebuilding shipments and updating only active mutable-AF carrier plans.");
		CollaborationUtils.setCoalitionFromReceiverAttributes(scenario);
		MutableAfCarrierShipmentBuilder.rebuild(scenario);
		for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values()) {
			updateActivePlan(carrier);
		}
	}

	private void updateActivePlan(Carrier carrier) {
		CarrierPlan active = carrier.getSelectedPlan();
		String profile = MutableAfPlanUtils.selectedReceiverProfile(carrier,
			ReceiverUtils.getReceivers(scenario).getReceivers().values());
		if (active == null) {
			CarrierPlan solved = routeSolver.solve(carrier, scenario);
			CarrierAllocationFactor.set(solved, mutableConfig.getInitialAllocationFactor(), mutableConfig);
			solved.setScore(null);
			solved.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE, profile);
			carrier.addPlan(solved);
			carrier.setSelectedPlan(solved);
			recordReplanned(carrier, true);
			return;
		}

		CarrierAllocationFactor.require(active, mutableConfig);
		Object previousProfile = active.getAttributes().getAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE);
		if (profile.equals(previousProfile) && !active.getScheduledTours().isEmpty()) {
			recordReplanned(carrier, false);
			return;
		}

		CarrierPlan solved = routeSolver.solve(carrier, scenario);
		MutableAfPlanUtils.replaceTours(active, solved.getScheduledTours());
		active.setJspritScore(solved.getJspritScore());
		active.setScore(null);
		active.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE, profile);
		recordReplanned(carrier, true);
	}

	private void recordReplanned(Carrier carrier, boolean replanned) {
		if (learningStore != null) {
			learningStore.recordRouteReplanned(carrier.getId(), replanned);
		}
	}
}
