package org.matsim.contrib.freightcollaboration.learning;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Mutable-AF run state. Long-run factor quality lives here rather than in the most recently
 * executed {@link CarrierPlan#getScore()}.
 */
@Singleton
public final class MutableAfLearningStore {

	private final Scenario scenario;
	private final MutableAllocationFactorConfigGroup config;
	private final CollaborationDataStore collaborationDataStore;
	private final FreightCoalitionManager coalitionManager;
	private final Map<Id<Carrier>, CarrierState> carrierStates = new LinkedHashMap<>();
	private final Map<Id<Receiver>, Id<Carrier>> receiverOwners = new LinkedHashMap<>();
	private final Map<Id<Receiver>, Double> receiverBaselines = new LinkedHashMap<>();
	private final List<CheckpointEvent> checkpointEvents = new ArrayList<>();
	private boolean initialized;
	private int checkpointSequence;

	@Inject
	public MutableAfLearningStore(Scenario scenario, MutableAllocationFactorConfigGroup config,
			CollaborationDataStore collaborationDataStore, FreightCoalitionManager coalitionManager) {
		this.scenario = Objects.requireNonNull(scenario, "scenario");
		this.config = Objects.requireNonNull(config, "config");
		this.collaborationDataStore = Objects.requireNonNull(collaborationDataStore, "collaborationDataStore");
		this.coalitionManager = Objects.requireNonNull(coalitionManager, "coalitionManager");
		config.validateGrid();
		config.finalizationIteration(scenario.getConfig().controller().getFirstIteration(),
			scenario.getConfig().controller().getLastIteration());
	}

	public synchronized void initialize() {
		if (initialized) {
			return;
		}
		assignReceiversToCarriers();
		for (Carrier carrier : sortedCarriers()) {
			CarrierPlan selected = Objects.requireNonNull(carrier.getSelectedPlan(),
				"Mutable-AF carrier needs an initial selected plan before controller startup: " + carrier.getId());
			if (CarrierAllocationFactor.find(selected).isEmpty()) {
				CarrierAllocationFactor.set(selected, config.getInitialAllocationFactor(), config);
			}
			int selectedIndex = config.indexOf(CarrierAllocationFactor.require(selected, config));
			CarrierState state = new CarrierState(carrier, selectedIndex);
			for (CarrierPlan plan : carrier.getPlans()) {
				if (CarrierAllocationFactor.find(plan).isEmpty()) {
					CarrierAllocationFactor.set(plan, config.getInitialAllocationFactor(), config);
				}
				int index = config.indexOf(CarrierAllocationFactor.require(plan, config));
				if (state.livePlans.putIfAbsent(index, plan) != null) {
					throw new IllegalStateException("Carrier " + carrier.getId()
						+ " has multiple live plans for allocation-factor grid index " + index);
				}
			}
			if (state.livePlans.size() > config.getMaxFactorPlans()) {
				throw new IllegalStateException("Carrier " + carrier.getId() + " starts with "
					+ state.livePlans.size() + " factor plans, exceeding MAX_FACTOR_PLANS="
					+ config.getMaxFactorPlans());
			}
			state.factor(selectedIndex).visits = 1;
			state.factor(selectedIndex).maturity = FactorMaturity.ADAPTING;
			state.factor(selectedIndex).lastVisitedIteration = scenario.getConfig().controller().getFirstIteration();
			carrierStates.put(carrier.getId(), state);
			initializeReceiverContext(state);
		}
		initialized = true;
	}

	public synchronized void prepareReplanning(int iteration) {
		initialize();
		int finalization = config.finalizationIteration(scenario.getConfig().controller().getFirstIteration(),
			scenario.getConfig().controller().getLastIteration());
		for (CarrierState state : carrierStates.values()) {
			state.routeReplanned = false;
			state.lastEviction = "";
			if (iteration >= finalization && state.phase != MutableAfPhase.FINAL_VALIDATION) {
				finalizeState(state, iteration);
				continue;
			}
			switch (state.phase) {
				case BASELINE -> {
					state.phase = MutableAfPhase.ADAPT;
					state.lastDecision = "INITIAL_FACTOR";
				}
				case SWITCH_PENDING -> switchFactor(state, iteration);
				case EVALUATE -> selectEvaluationIncumbents(state);
				case ADAPT, WARM_START_TRIAL, FINAL_VALIDATION -> {
					// The ReceiverStrategyManager performs the phase-appropriate action later in replanning.
				}
			}
		}
	}

	public synchronized void observeIterationEnd(int iteration) {
		initialize();
		int first = scenario.getConfig().controller().getFirstIteration();
		for (CarrierState state : carrierStates.values()) {
			if (state.phase == MutableAfPhase.WARM_START_TRIAL) {
				Observation trial = observeWarmStartTrial(state, iteration);
				state.lastObservation = trial;
				continue;
			}
			Observation observation = observe(state, iteration);
			state.lastObservation = observation;
			if (iteration == first) {
				captureBaseline(state, observation);
				continue;
			}
			switch (state.phase) {
				case ADAPT -> observeAdaptation(state, observation);
				case EVALUATE -> observeEvaluation(state, observation);
				case FINAL_VALIDATION -> state.finalValidationObservations.add(observation);
				case BASELINE, SWITCH_PENDING, WARM_START_TRIAL -> {
					// No transition is expected at iteration end in these phases.
				}
			}
		}
	}

	public synchronized MutableAfPhase phaseForReceiver(Id<Receiver> receiverId) {
		initialize();
		Id<Carrier> owner = receiverOwners.get(receiverId);
		return owner == null ? MutableAfPhase.FINAL_VALIDATION : carrierStates.get(owner).phase;
	}

	public synchronized int activeFactorIndexForReceiver(Id<Receiver> receiverId) {
		initialize();
		Id<Carrier> owner = receiverOwners.get(receiverId);
		if (owner == null) {
			throw new IllegalArgumentException("Receiver is not assigned to a mutable carrier: " + receiverId);
		}
		return carrierStates.get(owner).activeFactorIndex;
	}

	public synchronized Optional<ReceiverPlan> incumbent(Receiver receiver) {
		initialize();
		return receiver.getPlans().stream()
			.filter(plan -> contextIndex(plan).orElse(-1) == activeFactorIndexForReceiver(receiver.getId()))
			.filter(plan -> !MutableAfPlanUtils.isPendingEvaluation(plan))
			.filter(plan -> plan.getScore() != null && Double.isFinite(plan.getScore()))
			.max(Comparator.comparingDouble(ReceiverPlan::getScore)
				.thenComparing(MutableAfPlanUtils::receiverPlanSignature));
	}

	public synchronized boolean isOutsideOption(ReceiverPlan plan) {
		return Boolean.TRUE.equals(plan.getAttributes().getAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION));
	}

	public synchronized void recordRouteReplanned(Id<Carrier> carrierId, boolean replanned) {
		initialize();
		CarrierState state = requireState(carrierId);
		state.routeReplanned = state.routeReplanned || replanned;
	}

	public synchronized boolean hasPendingWarmStartTrial() {
		initialize();
		return carrierStates.values().stream()
			.anyMatch(state -> state.phase == MutableAfPhase.WARM_START_TRIAL);
	}

	/**
	 * Converts the finite compatibility score used during replanning into an unevaluated plan just
	 * before execution. The normal MATSim scoring lifecycle must write a target-factor score back.
	 */
	public synchronized void beginWarmStartExecution(int iteration) {
		initialize();
		Map<CarrierState, List<ReceiverPlan>> selectedPlansByCarrier = new LinkedHashMap<>();
		for (CarrierState state : carrierStates.values()) {
			if (state.phase != MutableAfPhase.WARM_START_TRIAL) {
				continue;
			}
			if (state.warmStartExecutionIteration == iteration) {
				continue;
			}
			if (state.warmStartExecutionIteration >= 0) {
				throw new IllegalStateException("Warm-start trial for carrier " + state.carrier.getId()
					+ " was already started in iteration " + state.warmStartExecutionIteration);
			}
			List<ReceiverPlan> selectedPlans = state.receiverIds.stream()
				.map(receiverId -> requireWarmStartSelected(state, receiverId,
					"Cannot begin warm-start execution without a finite temporary Receiver score"))
				.toList();
			selectedPlansByCarrier.put(state, selectedPlans);
		}
		for (Map.Entry<CarrierState, List<ReceiverPlan>> entry : selectedPlansByCarrier.entrySet()) {
			CarrierState state = entry.getKey();
			entry.getValue().forEach(plan -> plan.setScore(null));
			state.warmStartExecutionIteration = iteration;
			state.warmStartScoreClearedBeforeMobsim = true;
		}
	}

	public synchronized CarrierSnapshot snapshot(Id<Carrier> carrierId) {
		initialize();
		CarrierState state = requireState(carrierId);
		Observation last = state.lastObservation;
		FactorRecord active = state.factor(state.activeFactorIndex);
		return new CarrierSnapshot(
			carrierId,
			state.phase,
			state.activeFactorIndex,
			config.valueAt(state.activeFactorIndex),
			state.visit,
			state.dwell,
			state.stableStreak,
			state.evaluationCount,
			state.lastDecision,
			state.routeReplanned,
			last == null ? null : last.carrierScore,
			state.baselineCarrierScore,
			active.stableMean(),
			active.stableVariance(),
			last == null ? "" : last.incumbentProfile,
			last != null && !last.coalitionReceivers.isEmpty(),
			last == null ? 0 : last.coalitionReceivers.size(),
			last == null ? 0.0 : last.totalSurplus,
			collaborationDataStore.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, carrierId),
			state.livePlans.keySet().stream().sorted().toList(),
			state.lastEviction,
			state.factors.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getKey() + ":" + entry.getValue().maturity)
				.toList(),
			state.phase == MutableAfPhase.WARM_START_TRIAL,
			state.warmStartSourceFactorIndex,
			state.warmStartScoreClearedBeforeMobsim,
			state.finalStatus
		);
	}

	public synchronized Map<Integer, FactorSummary> factorSummaries(Id<Carrier> carrierId) {
		initialize();
		CarrierState state = requireState(carrierId);
		Map<Integer, FactorSummary> result = new LinkedHashMap<>();
		state.factors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			FactorRecord record = entry.getValue();
			result.put(entry.getKey(), new FactorSummary(entry.getKey(), config.valueAt(entry.getKey()),
				record.visits, record.matureEvaluations, record.stableMean(), record.stableVariance(),
				record.lastVisitedIteration, record.maturity, record.participationFeasible,
				state.livePlans.containsKey(entry.getKey())));
		});
		return Map.copyOf(result);
	}

	public synchronized List<CheckpointEvent> checkpointEvents() {
		return List.copyOf(checkpointEvents);
	}

	public synchronized int retainedFactorCount(Id<Carrier> carrierId) {
		return requireState(carrierId).livePlans.size();
	}

	public synchronized Optional<Integer> contextIndex(ReceiverPlan plan) {
		Object value = plan.getAttributes().getAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX);
		if (value == null) {
			return Optional.empty();
		}
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("Receiver factor context must be numeric, got " + value);
		}
		return Optional.of(number.intValue());
	}

	private void captureBaseline(CarrierState state, Observation observation) {
		state.baselineCarrierScore = requireFiniteScore(observation.carrierScore,
			"iteration-0 carrier score for " + state.carrier.getId());
		state.baselineTours = MutableAfPlanUtils.copyTours(state.carrier.getSelectedPlan().getScheduledTours());
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			Double score = receiver.getSelectedPlan() == null ? null : receiver.getSelectedPlan().getScore();
			receiverBaselines.put(receiverId,
				requireFiniteScore(score, "iteration-0 receiver score for " + receiverId));
		}
		state.lastDecision = "BASELINE_CAPTURED";
	}

	private Observation observeWarmStartTrial(CarrierState state, int iteration) {
		if (state.warmStartExecutionIteration != iteration
			|| !state.warmStartScoreClearedBeforeMobsim) {
			throw new IllegalStateException("Warm-start trial for carrier " + state.carrier.getId()
				+ " reached iteration end without beginWarmStartExecution in iteration " + iteration);
		}
		List<ReceiverPlan> selectedPlans = state.receiverIds.stream()
			.map(receiverId -> requireWarmStartSelected(state, receiverId,
				"Warm-start trial completed without a finite Receiver score"))
			.toList();
		requireFiniteScore(state.carrier.getSelectedPlan() == null
				? null : state.carrier.getSelectedPlan().getScore(),
			"warm-start carrier score at iteration " + iteration + " for " + state.carrier.getId());
		selectedPlans.forEach(MutableAfPlanUtils::clearPendingEvaluation);
		Observation observation = observe(state, iteration);
		state.dwell = 1;
		state.stableStreak = 0;
		state.previousAdaptObservation = observation;
		state.phase = MutableAfPhase.ADAPT;
		state.lastDecision = "WARM_START_TRIAL_COMPLETE";
		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.lastVisitedIteration = iteration;
		if (factor.bestStableCheckpoint == null) {
			factor.maturity = FactorMaturity.ADAPTING;
		}
		return observation;
	}

	private ReceiverPlan requireWarmStartSelected(CarrierState state, Id<Receiver> receiverId,
			String missingScoreMessage) {
		ReceiverPlan selected = receiver(receiverId).getSelectedPlan();
		if (selected == null) {
			throw new IllegalStateException("Warm-start trial receiver has no selected plan: receiver="
				+ receiverId + ", factorIndex=" + state.activeFactorIndex + ", phase=" + state.phase);
		}
		int context = contextIndex(selected).orElseThrow(() -> new IllegalStateException(
			"Warm-start trial selected plan has no factor context for receiver " + receiverId));
		if (context != state.activeFactorIndex || !MutableAfPlanUtils.isPendingEvaluation(selected)) {
			throw new IllegalStateException("Invalid warm-start trial selected plan for receiver " + receiverId
				+ ": activeFactorIndex=" + state.activeFactorIndex + ", planFactorIndex=" + context
				+ ", pendingEvaluation=" + MutableAfPlanUtils.isPendingEvaluation(selected)
				+ ", phase=" + state.phase);
		}
		if (selected.getScore() == null || !Double.isFinite(selected.getScore())) {
			throw new IllegalStateException(missingScoreMessage + ": receiver="
				+ receiverId + ", factorIndex=" + state.activeFactorIndex + ", score=" + selected.getScore());
		}
		return selected;
	}

	private void observeAdaptation(CarrierState state, Observation observation) {
		state.dwell++;
		boolean stable = state.previousAdaptObservation != null
			&& state.previousAdaptObservation.incumbentProfile.equals(observation.incumbentProfile)
			&& state.previousAdaptObservation.coalitionReceivers.equals(observation.coalitionReceivers)
			&& close(state.previousAdaptObservation.carrierScore, observation.carrierScore)
			&& close(state.previousAdaptObservation.receiverAggregateScore, observation.receiverAggregateScore)
			&& close(state.previousAdaptObservation.totalSurplus, observation.totalSurplus);
		state.stableStreak = stable ? state.stableStreak + 1 : 0;
		state.previousAdaptObservation = observation;
		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.lastVisitedIteration = observation.iteration;
		factor.maturity = factor.bestStableCheckpoint == null ? FactorMaturity.ADAPTING : factor.maturity;

		int minimumDwell = factor.bestStableCheckpoint == null
			? config.getNewFactorMinDwell() : config.getRevisitFactorMinDwell();
		boolean converged = state.dwell >= minimumDwell && state.stableStreak >= config.getStabilityWindow();
		boolean forced = state.dwell >= config.getMaxAdaptDwell();
		if (converged || forced) {
			state.evaluationForced = !converged;
			state.evaluationSelection = selectIncumbentPlans(state);
			state.evaluationObservations.clear();
			state.evaluationCount = 0;
			state.phase = MutableAfPhase.EVALUATE;
			state.lastDecision = converged ? "START_STABLE_EVALUATION" : "START_FORCED_EVALUATION";
		}
	}

	private void observeEvaluation(CarrierState state, Observation observation) {
		state.evaluationObservations.add(observation);
		state.evaluationCount++;
		if (state.evaluationCount < config.getEvaluationWindow()) {
			return;
		}
		completeEvaluation(state);
		state.phase = MutableAfPhase.SWITCH_PENDING;
		state.lastDecision = "EVALUATION_COMPLETE";
	}

	private void completeEvaluation(CarrierState state) {
		List<Observation> observations = List.copyOf(state.evaluationObservations);
		double carrierMean = observations.stream().mapToDouble(o -> o.carrierScore).average().orElseThrow();
		double surplusMean = observations.stream().mapToDouble(o -> o.totalSurplus).average().orElse(0.0);
		Map<Id<Receiver>, Double> receiverMeans = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			receiverMeans.put(receiverId, observations.stream()
				.map(o -> o.receiverScores.get(receiverId))
				.filter(Objects::nonNull)
				.mapToDouble(Double::doubleValue)
				.average().orElse(Double.NaN));
		}
		Set<Id<Receiver>> collaborating = observations.getLast().coalitionReceivers;
		boolean scoreStable = rangeIsStable(observations.stream().map(o -> o.carrierScore).toList())
			&& rangeIsStable(observations.stream().map(o -> o.receiverAggregateScore).toList())
			&& rangeIsStable(observations.stream().map(o -> o.totalSurplus).toList());
		boolean matureStable = !state.evaluationForced && scoreStable;
		boolean participationFeasible = noWorse(carrierMean, state.baselineCarrierScore,
			config.getParticipationRelativeTolerance());
		for (Id<Receiver> receiverId : collaborating) {
			participationFeasible &= noWorse(receiverMeans.getOrDefault(receiverId, Double.NaN),
				receiverBaselines.getOrDefault(receiverId, Double.NaN),
				config.getParticipationRelativeTolerance());
		}

		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.lastVisitedIteration = observations.getLast().iteration;
		FactorCheckpoint checkpoint = createCheckpoint(state, observations.getFirst().iteration,
			observations.getLast().iteration, carrierMean, surplusMean, receiverMeans, collaborating,
			participationFeasible, matureStable);
		factor.lastCheckpoint = checkpoint;
		if (matureStable) {
			factor.participationFeasible |= participationFeasible;
			factor.addStableScore(carrierMean);
			factor.matureEvaluations++;
			factor.maturity = FactorMaturity.MATURE_STABLE;
			if (factor.bestStableCheckpoint == null
				|| (participationFeasible && (!factor.bestStableCheckpoint.participationFeasible
				|| carrierMean > factor.bestStableCheckpoint.carrierScoreMean))) {
				factor.bestStableCheckpoint = checkpoint;
			}
		} else if (factor.bestStableCheckpoint == null) {
			factor.maturity = FactorMaturity.VALIDATED_UNSTABLE;
		}
		state.rollbackCheckpoint = !matureStable && factor.bestStableCheckpoint != null
			? factor.bestStableCheckpoint : null;

		checkpointEvents.add(toEvent(++checkpointSequence, state, checkpoint, "CHECKPOINT", false));
	}

	private FactorCheckpoint createCheckpoint(CarrierState state, int evaluationStart, int evaluationEnd,
			double carrierMean, double surplusMean, Map<Id<Receiver>, Double> receiverMeans,
			Set<Id<Receiver>> collaborating, boolean participationFeasible, boolean matureStable) {
		Map<Id<Receiver>, ReceiverContextSnapshot> receiverSnapshots = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			if (receiver.getPlans().stream().anyMatch(MutableAfPlanUtils::isPendingEvaluation)) {
				throw new IllegalStateException("Cannot create checkpoint with pending Receiver plans: " + receiverId);
			}
			List<ReceiverPlan> copies = receiver.getPlans().stream()
				.map(plan -> MutableAfPlanUtils.copyReceiverPlan(plan, true)).toList();
			int selected = receiver.getSelectedPlan() == null ? -1 : receiver.getPlans().indexOf(receiver.getSelectedPlan());
			receiverSnapshots.put(receiverId, new ReceiverContextSnapshot(copies, selected));
		}
		return new FactorCheckpoint(state.activeFactorIndex, evaluationStart, evaluationEnd,
			carrierMean, surplusMean, Map.copyOf(receiverMeans), Set.copyOf(collaborating),
			participationFeasible, matureStable,
			MutableAfPlanUtils.copyTours(state.carrier.getSelectedPlan().getScheduledTours()),
			Map.copyOf(receiverSnapshots));
	}

	private void switchFactor(CarrierState state, int iteration) {
		Map<Id<Receiver>, ReceiverPlan> seedPlans;
		if (state.rollbackCheckpoint != null) {
			FactorCheckpoint rollback = state.rollbackCheckpoint;
			Map<Id<Receiver>, ReceiverContext> rollbackContexts = checkpointContexts(rollback);
			state.receiverContexts.put(state.activeFactorIndex, rollbackContexts);
			seedPlans = selectedPlans(rollbackContexts);
			CarrierPlan outgoing = state.carrier.getSelectedPlan();
			MutableAfPlanUtils.replaceTours(outgoing, rollback.tours);
			outgoing.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
				checkpointProfile(rollback));
			outgoing.setScore(null);
			state.rollbackCheckpoint = null;
		} else {
			archiveActiveContext(state);
			seedPlans = selectedPlans(state.receiverIds);
		}
		int current = state.activeFactorIndex;
		List<Integer> matureAlternatives = state.livePlans.keySet().stream()
			.filter(index -> index != current)
			.filter(index -> state.factor(index).maturity == FactorMaturity.MATURE_STABLE)
			.toList();
		double coverage = 1.0 - state.livePlans.size() / (double) config.getMaxFactorPlans();
		double explorationProbability = clamp(config.getMinExplorationProbability(),
			config.getMaxExplorationProbability(), config.getMutationWeight() * coverage);
		boolean explore = matureAlternatives.isEmpty()
			|| MatsimRandom.getLocalInstance().nextDouble() < explorationProbability;
		int target = explore ? adjacentFactor(current) : softmaxFactor(state, matureAlternatives);
		boolean revisit = state.receiverContexts.containsKey(target);
		String decision = explore ? "EXPLORE" : "EXPLOIT";
		if (!activateFactor(state, target, current, seedPlans)) {
			state.phase = MutableAfPhase.ADAPT;
			state.dwell = 0;
			state.stableStreak = 0;
			state.previousAdaptObservation = null;
			state.factor(current).visits++;
			state.lastDecision = "MEMORY_BLOCKED";
			restoreReceiverContextWithoutTrial(state, current);
			return;
		}
		state.phase = MutableAfPhase.WARM_START_TRIAL;
		state.visit++;
		state.dwell = 0;
		state.stableStreak = 0;
		state.evaluationCount = 0;
		state.previousAdaptObservation = null;
		state.evaluationSelection = Map.of();
		state.warmStartSourceFactorIndex = current;
		state.warmStartExecutionIteration = -1;
		state.warmStartScoreClearedBeforeMobsim = false;
		state.factor(target).visits++;
		state.factor(target).lastVisitedIteration = iteration;
		if (state.factor(target).bestStableCheckpoint == null) {
			state.factor(target).maturity = FactorMaturity.ADAPTING;
		}
		state.lastDecision = "WARM_START_" + decision + "_" + (revisit ? "REVISIT" : "UNSEEN")
			+ "_TO_" + config.valueAt(target);
	}

	private boolean activateFactor(CarrierState state, int target, int sourceFactorIndex,
			Map<Id<Receiver>, ReceiverPlan> seedPlans) {
		CarrierPlan targetPlan = state.livePlans.get(target);
		if (targetPlan == null) {
			Integer victim = null;
			if (state.livePlans.size() >= config.getMaxFactorPlans()) {
				victim = chooseEviction(state);
				if (victim == null) {
					return false;
				}
			}
			CarrierPlan source = state.carrier.getSelectedPlan();
			targetPlan = MutableAfPlanUtils.copyCarrierPlan(source, false);
			CarrierAllocationFactor.set(targetPlan, config.valueAt(target), config);
			targetPlan.getAttributes().removeAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE);
			state.carrier.addPlan(targetPlan);
			state.livePlans.put(target, targetPlan);
			state.carrier.setSelectedPlan(targetPlan);
			if (victim != null) {
				evict(state, victim);
			}
		} else {
			state.carrier.setSelectedPlan(targetPlan);
			targetPlan.setScore(null);
		}
		state.activeFactorIndex = target;
		restoreReceiverContextForTrial(state, target, sourceFactorIndex, seedPlans);
		return true;
	}

	private Integer chooseEviction(CarrierState state) {
		Optional<Integer> protectedBest = state.livePlans.keySet().stream()
			.filter(index -> state.factor(index).maturity == FactorMaturity.MATURE_STABLE)
			.filter(index -> state.factor(index).bestStableCheckpoint != null)
			.filter(index -> state.factor(index).bestStableCheckpoint.participationFeasible)
			.max(Comparator.comparingDouble(index -> state.factor(index).stableMean()));
		return state.livePlans.keySet().stream()
			.filter(index -> protectedBest.isEmpty() || !protectedBest.get().equals(index))
			.min(Comparator
				.comparingInt((Integer index) -> evictionTier(state.factor(index)))
				.thenComparingDouble(index -> finiteOrNegativeInfinity(state.factor(index).stableMean()))
				.thenComparingInt(index -> state.factor(index).lastVisitedIteration)
				.thenComparingInt(Integer::intValue))
			.orElse(null);
	}

	private void evict(CarrierState state, int factorIndex) {
		CarrierPlan victim = state.livePlans.remove(factorIndex);
		if (victim != null) {
			state.carrier.removePlan(victim);
		}
		state.receiverContexts.remove(factorIndex);
		FactorRecord record = state.factor(factorIndex);
		FactorCheckpoint historical = record.bestStableCheckpoint != null
			? record.bestStableCheckpoint : record.lastCheckpoint;
		state.lastEviction = "EVICT_" + config.valueAt(factorIndex);
		if (historical != null) {
			checkpointEvents.add(toEvent(++checkpointSequence, state, historical,
				"EVICTION", false));
		}
		record.lastCheckpoint = null;
		record.bestStableCheckpoint = null;
		record.maturity = FactorMaturity.EVICTED;
	}

	private void finalizeState(CarrierState state, int iteration) {
		archiveActiveContext(state);
		Optional<Map.Entry<Integer, FactorRecord>> best = state.factors.entrySet().stream()
			.filter(entry -> state.livePlans.containsKey(entry.getKey()))
			.filter(entry -> entry.getValue().maturity == FactorMaturity.MATURE_STABLE)
			.filter(entry -> entry.getValue().bestStableCheckpoint != null)
			.filter(entry -> entry.getValue().bestStableCheckpoint.participationFeasible)
			.filter(entry -> noWorse(entry.getValue().bestStableCheckpoint.carrierScoreMean,
				state.baselineCarrierScore, config.getParticipationRelativeTolerance()))
			.max(Comparator.comparingDouble(entry -> entry.getValue().bestStableCheckpoint.carrierScoreMean));
		if (best.isPresent()) {
			int target = best.get().getKey();
			FactorCheckpoint checkpoint = best.get().getValue().bestStableCheckpoint;
			CarrierPlan plan = state.livePlans.get(target);
			state.carrier.setSelectedPlan(plan);
			MutableAfPlanUtils.replaceTours(plan, checkpoint.tours);
			plan.setScore(null);
			state.activeFactorIndex = target;
			restoreCheckpointReceivers(state, checkpoint);
			state.finalStatus = "SELECTED_FACTOR";
			state.lastDecision = "FINAL_SELECT_" + config.valueAt(target);
			checkpointEvents.add(toEvent(++checkpointSequence, state, checkpoint,
				"FINAL_SELECTION", true));
		} else {
			restoreBaseline(state);
			state.finalStatus = "NO_FEASIBLE_MATURE_FACTOR";
			state.lastDecision = "FINAL_BASELINE_FALLBACK";
		}
		state.phase = MutableAfPhase.FINAL_VALIDATION;
		state.dwell = 0;
		state.stableStreak = 0;
		state.evaluationCount = 0;
		state.warmStartSourceFactorIndex = null;
		state.warmStartExecutionIteration = -1;
		state.warmStartScoreClearedBeforeMobsim = false;
		state.factor(state.activeFactorIndex).lastVisitedIteration = iteration;
	}

	private void restoreBaseline(CarrierState state) {
		int initial = config.indexOf(config.getInitialAllocationFactor());
		CarrierPlan plan = state.livePlans.get(initial);
		if (plan == null) {
			Integer victim = state.livePlans.size() >= config.getMaxFactorPlans()
				? chooseEviction(state) : null;
			if (state.livePlans.size() >= config.getMaxFactorPlans() && victim == null) {
				throw new IllegalStateException("Cannot restore baseline within MAX_FACTOR_PLANS for carrier "
					+ state.carrier.getId());
			}
			plan = new CarrierPlan(state.carrier, MutableAfPlanUtils.copyTours(state.baselineTours));
			CarrierAllocationFactor.set(plan, config.getInitialAllocationFactor(), config);
			state.carrier.addPlan(plan);
			state.livePlans.put(initial, plan);
			state.carrier.setSelectedPlan(plan);
			if (victim != null) {
				evict(state, victim);
			}
		}
		state.carrier.setSelectedPlan(plan);
		MutableAfPlanUtils.replaceTours(plan, state.baselineTours);
		plan.setScore(null);
		state.activeFactorIndex = initial;
		Map<Id<?>, ? extends BasicPlan> originals = collaborationDataStore.getOriginalPlans()
			.getOrDefault(CollaboratorRole.RECEIVER, Map.of());
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			ReceiverPlan original = (ReceiverPlan) originals.get(receiverId);
			if (original == null) {
				throw new IllegalStateException("Missing original receiver plan for baseline fallback: " + receiverId);
			}
			ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(original, false);
			MutableAfPlanUtils.clearPendingEvaluation(copy);
			tagReceiverPlan(copy, initial, 0, true);
			copy.setScore(receiverBaselines.get(receiverId));
			receiver.getPlans().clear();
			receiver.setSelectedPlan(copy);
		}
	}

	private void initializeReceiverContext(CarrierState state) {
		Map<Id<Receiver>, ReceiverContext> contexts = new LinkedHashMap<>();
		Map<Id<?>, ? extends BasicPlan> originals = collaborationDataStore.getOriginalPlans()
			.getOrDefault(CollaboratorRole.RECEIVER, Map.of());
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			ReceiverPlan original = (ReceiverPlan) originals.get(receiverId);
			if (original == null) {
				throw new IllegalStateException("Missing original receiver plan for " + receiverId);
			}
			String originalSignature = MutableAfPlanUtils.receiverPlanSignature(original);
			boolean hasOutside = false;
			for (ReceiverPlan plan : receiver.getPlans()) {
				MutableAfPlanUtils.clearPendingEvaluation(plan);
				boolean outside = MutableAfPlanUtils.receiverPlanSignature(plan).equals(originalSignature);
				tagReceiverPlan(plan, state.activeFactorIndex, 0, outside);
				hasOutside |= outside;
			}
			if (!hasOutside) {
				ReceiverPlan outside = MutableAfPlanUtils.copyReceiverPlan(original, false);
				MutableAfPlanUtils.clearPendingEvaluation(outside);
				tagReceiverPlan(outside, state.activeFactorIndex, 0, true);
				receiver.addPlan(outside);
			}
			trimInitialReceiverMemory(receiver);
			contexts.put(receiverId, new ReceiverContext(new ArrayList<>(receiver.getPlans()),
				receiver.getSelectedPlan(), 0));
		}
		state.receiverContexts.put(state.activeFactorIndex, contexts);
	}

	private void archiveActiveContext(CarrierState state) {
		Map<Id<Receiver>, ReceiverContext> archived = new LinkedHashMap<>();
		Map<Id<Receiver>, ReceiverContext> existing = state.receiverContexts.get(state.activeFactorIndex);
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			int generation = existing == null || existing.get(receiverId) == null
				? 0 : existing.get(receiverId).generation;
			archived.put(receiverId, new ReceiverContext(new ArrayList<>(receiver.getPlans()),
				receiver.getSelectedPlan(), generation));
		}
		state.receiverContexts.put(state.activeFactorIndex, archived);
	}

	private void restoreReceiverContextForTrial(CarrierState state, int target, int sourceFactorIndex,
			Map<Id<Receiver>, ReceiverPlan> unseenSeedPlans) {
		Map<Id<Receiver>, ReceiverContext> contexts = state.receiverContexts.get(target);
		if (contexts == null) {
			contexts = createUnseenContexts(state, target, sourceFactorIndex, unseenSeedPlans);
			state.receiverContexts.put(target, contexts);
		} else {
			contexts = prepareRevisitContexts(state, target, contexts);
			state.receiverContexts.put(target, contexts);
		}
		restoreReceiverContexts(state, contexts);
	}

	private void restoreReceiverContextWithoutTrial(CarrierState state, int target) {
		Map<Id<Receiver>, ReceiverContext> contexts = Objects.requireNonNull(
			state.receiverContexts.get(target), "Missing Receiver context for factor " + target);
		restoreReceiverContexts(state, contexts);
	}

	private void restoreReceiverContexts(CarrierState state,
			Map<Id<Receiver>, ReceiverContext> contexts) {
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			ReceiverContext context = Objects.requireNonNull(contexts.get(receiverId));
			receiver.getPlans().clear();
			context.plans.forEach(receiver::addPlan);
			ReceiverPlan selected = context.plans.contains(context.selected)
				? context.selected : bestOrFirst(context.plans);
			receiver.setSelectedPlan(selected);
		}
	}

	private Map<Id<Receiver>, ReceiverContext> createUnseenContexts(CarrierState state, int target,
			int sourceFactorIndex, Map<Id<Receiver>, ReceiverPlan> seedPlans) {
		Map<Id<Receiver>, ReceiverContext> result = new LinkedHashMap<>();
		Map<Id<?>, ? extends BasicPlan> originals = collaborationDataStore.getOriginalPlans()
			.getOrDefault(CollaboratorRole.RECEIVER, Map.of());
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverPlan seed = seedPlans == null ? null : seedPlans.get(receiverId);
			if (seed == null || seed.getScore() == null || !Double.isFinite(seed.getScore())) {
				throw new IllegalStateException("Cannot warm-start receiver " + receiverId + " from factor "
					+ sourceFactorIndex + ": outgoing selected plan has no finite score");
			}
			ReceiverPlan adaptive = MutableAfPlanUtils.copyReceiverPlan(seed, true);
			MutableAfPlanUtils.clearPendingEvaluation(adaptive);
			tagReceiverPlan(adaptive, target, 1, false);
			ReceiverPlan original = (ReceiverPlan) originals.get(receiverId);
			if (original == null) {
				throw new IllegalStateException("Missing original receiver plan for warm start: " + receiverId);
			}
			ReceiverPlan outside = MutableAfPlanUtils.copyReceiverPlan(original, false);
			MutableAfPlanUtils.clearPendingEvaluation(outside);
			tagReceiverPlan(outside, target, 1, true);
			outside.setScore(requireFiniteScore(receiverBaselines.get(receiverId),
				"iteration-0 receiver baseline for " + receiverId));
			boolean duplicateOutside = MutableAfPlanUtils.receiverPlanSignature(adaptive)
				.equals(MutableAfPlanUtils.receiverPlanSignature(outside));
			List<ReceiverPlan> plans = duplicateOutside
				? new ArrayList<>(List.of(outside)) : new ArrayList<>(List.of(outside, adaptive));
			ReceiverPlan selected = duplicateOutside ? outside : adaptive;
			MutableAfPlanUtils.markPendingEvaluation(selected, sourceFactorIndex);
			result.put(receiverId, new ReceiverContext(plans, selected, 1));
		}
		return result;
	}

	private Map<Id<Receiver>, ReceiverContext> prepareRevisitContexts(CarrierState state, int target,
			Map<Id<Receiver>, ReceiverContext> archived) {
		Map<Id<Receiver>, ReceiverContext> result = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverContext context = Objects.requireNonNull(archived.get(receiverId),
				"Missing archived Receiver context for " + receiverId + " at factor " + target);
			ReceiverPlan selected = finiteNonPending(context.selected) && context.plans.contains(context.selected)
				? context.selected : context.plans.stream()
					.filter(plan -> !isOutsideOption(plan))
					.filter(this::finiteNonPending)
					.max(Comparator.comparingDouble(ReceiverPlan::getScore)
						.thenComparing(MutableAfPlanUtils::receiverPlanSignature))
					.orElseGet(() -> context.plans.stream()
						.filter(this::isOutsideOption)
						.filter(this::finiteNonPending)
						.findFirst().orElseThrow(() -> new IllegalStateException(
							"Cannot revisit receiver " + receiverId + " at factor " + target
								+ ": archived context has no finite score")));
			MutableAfPlanUtils.markPendingEvaluation(selected, target);
			result.put(receiverId, new ReceiverContext(context.plans, selected, context.generation));
		}
		return result;
	}

	private boolean finiteNonPending(ReceiverPlan plan) {
		return plan != null && !MutableAfPlanUtils.isPendingEvaluation(plan)
			&& plan.getScore() != null && Double.isFinite(plan.getScore());
	}

	private void restoreCheckpointReceivers(CarrierState state, FactorCheckpoint checkpoint) {
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverContextSnapshot snapshot = checkpoint.receiverSnapshots.get(receiverId);
			if (snapshot == null) {
				throw new IllegalStateException("Checkpoint lacks receiver " + receiverId);
			}
			Receiver receiver = receiver(receiverId);
			List<ReceiverPlan> copies = snapshot.plans.stream()
				.map(plan -> {
					ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(plan, true);
					MutableAfPlanUtils.clearPendingEvaluation(copy);
					return copy;
				}).toList();
			receiver.getPlans().clear();
			copies.forEach(receiver::addPlan);
			int selectedIndex = snapshot.selectedIndex >= 0 && snapshot.selectedIndex < copies.size()
				? snapshot.selectedIndex : 0;
			receiver.setSelectedPlan(copies.get(selectedIndex));
		}
	}

	private Map<Id<Receiver>, ReceiverContext> checkpointContexts(FactorCheckpoint checkpoint) {
		Map<Id<Receiver>, ReceiverContext> result = new LinkedHashMap<>();
		for (Map.Entry<Id<Receiver>, ReceiverContextSnapshot> entry : checkpoint.receiverSnapshots.entrySet()) {
			List<ReceiverPlan> copies = entry.getValue().plans.stream()
				.map(plan -> {
					ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(plan, true);
					MutableAfPlanUtils.clearPendingEvaluation(copy);
					return copy;
				}).toList();
			int selectedIndex = entry.getValue().selectedIndex >= 0
				&& entry.getValue().selectedIndex < copies.size() ? entry.getValue().selectedIndex : 0;
			result.put(entry.getKey(), new ReceiverContext(new ArrayList<>(copies),
				copies.get(selectedIndex), 0));
		}
		return result;
	}

	private static Map<Id<Receiver>, ReceiverPlan> selectedPlans(
			Map<Id<Receiver>, ReceiverContext> contexts) {
		Map<Id<Receiver>, ReceiverPlan> result = new LinkedHashMap<>();
		contexts.forEach((receiverId, context) -> result.put(receiverId, context.selected));
		return result;
	}

	private static String checkpointProfile(FactorCheckpoint checkpoint) {
		StringJoiner profile = new StringJoiner("|");
		checkpoint.receiverSnapshots.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.comparing(Id::toString)))
			.forEach(entry -> {
				ReceiverContextSnapshot snapshot = entry.getValue();
				int selectedIndex = snapshot.selectedIndex >= 0 && snapshot.selectedIndex < snapshot.plans.size()
					? snapshot.selectedIndex : 0;
				profile.add(entry.getKey() + "="
					+ MutableAfPlanUtils.receiverPlanSignature(snapshot.plans.get(selectedIndex)));
			});
		return profile.toString();
	}

	private void selectEvaluationIncumbents(CarrierState state) {
		for (Map.Entry<Id<Receiver>, ReceiverPlan> entry : state.evaluationSelection.entrySet()) {
			Receiver receiver = receiver(entry.getKey());
			if (receiver.getPlans().contains(entry.getValue())) {
				receiver.setSelectedPlan(entry.getValue());
			}
		}
	}

	private Map<Id<Receiver>, ReceiverPlan> selectIncumbentPlans(CarrierState state) {
		Map<Id<Receiver>, ReceiverPlan> result = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			result.put(receiverId, incumbent(receiver).orElse(receiver.getSelectedPlan()));
		}
		return Map.copyOf(result);
	}

	private Observation observe(CarrierState state, int iteration) {
		CarrierPlan selected = Objects.requireNonNull(state.carrier.getSelectedPlan(),
			"Carrier has no selected plan at iteration end: " + state.carrier.getId());
		double carrierScore = requireFiniteScore(selected.getScore(),
			"carrier score at iteration " + iteration + " for " + state.carrier.getId());
		Map<Id<Receiver>, Double> receiverScores = new LinkedHashMap<>();
		double receiverAggregate = 0.0;
		StringJoiner profile = new StringJoiner("|");
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			ReceiverPlan selectedReceiverPlan = Objects.requireNonNull(receiver.getSelectedPlan(),
				"Receiver has no selected plan: " + receiverId);
			double receiverScore = requireFiniteScore(selectedReceiverPlan.getScore(),
				"receiver score at iteration " + iteration + " for " + receiverId);
			receiverScores.put(receiverId, receiverScore);
			receiverAggregate += receiverScore;
			ReceiverPlan incumbent = incumbent(receiver).orElse(selectedReceiverPlan);
			profile.add(receiverId + "=" + MutableAfPlanUtils.receiverPlanSignature(incumbent));
		}
		Set<Id<Receiver>> coalitionReceivers = coalitionReceivers(state.carrier.getId());
		return new Observation(iteration, carrierScore, Map.copyOf(receiverScores), receiverAggregate,
			profile.toString(), coalitionReceivers, totalSurplus(state.carrier.getId()));
	}

	private Set<Id<Receiver>> coalitionReceivers(Id<Carrier> carrierId) {
		List<MutableFreightCoalition> coalitions = coalitionManager.getMutableFreightCoalitions();
		if (coalitions == null) {
			return Set.of();
		}
		Set<Id<Receiver>> result = new LinkedHashSet<>();
		for (MutableFreightCoalition coalition : coalitions) {
			if (coalition.contains(CollaboratorRole.CARRIER, carrierId)) {
				coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER).forEach(collaborator ->
					result.add(Id.create(collaborator.getId().toString(), Receiver.class)));
			}
		}
		return Set.copyOf(result);
	}

	private double totalSurplus(Id<Carrier> carrierId) {
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> scores =
			collaborationDataStore.getSimulatedCoalitionScores();
		if (scores == null) {
			return 0.0;
		}
		double result = 0.0;
		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : scores.entrySet()) {
			if (!entry.getKey().contains(CollaboratorRole.CARRIER, carrierId) || entry.getValue().isEmpty()) {
				continue;
			}
			result += entry.getValue().entrySet().stream()
				.max(Comparator.comparingInt(value -> value.getKey().size()))
				.map(Map.Entry::getValue).orElse(0.0);
		}
		return result;
	}

	private void assignReceiversToCarriers() {
		for (Receiver receiver : sortedReceivers()) {
			ReceiverPlan selected = Objects.requireNonNull(receiver.getSelectedPlan(),
				"Mutable-AF receiver has no selected plan: " + receiver.getId());
			Set<Id<Carrier>> carriers = new LinkedHashSet<>();
			selected.getReceiverOrders().forEach(order -> carriers.add(order.getCarrierId()));
			if (carriers.size() != 1) {
				throw new IllegalStateException("Mutable-AF v1 requires each receiver to be assigned to exactly one "
					+ "carrier; receiver " + receiver.getId() + " references " + carriers);
			}
			receiverOwners.put(receiver.getId(), carriers.iterator().next());
		}
		for (Map.Entry<Id<Receiver>, Id<Carrier>> entry : receiverOwners.entrySet()) {
			if (!CarriersUtils.getCarriers(scenario).getCarriers().containsKey(entry.getValue())) {
				throw new IllegalStateException("Receiver " + entry.getKey() + " references missing carrier "
					+ entry.getValue());
			}
		}
	}

	private List<Carrier> sortedCarriers() {
		return CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
			.sorted(Comparator.comparing(carrier -> carrier.getId().toString())).toList();
	}

	private List<Receiver> sortedReceivers() {
		return ReceiverUtils.getReceivers(scenario).getReceivers().values().stream()
			.sorted(Comparator.comparing(receiver -> receiver.getId().toString())).toList();
	}

	private Receiver receiver(Id<Receiver> receiverId) {
		Receiver receiver = ReceiverUtils.getReceivers(scenario).getReceivers().get(receiverId);
		if (receiver == null) {
			throw new IllegalStateException("Missing receiver " + receiverId);
		}
		return receiver;
	}

	private int adjacentFactor(int current) {
		int last = config.gridPointCount() - 1;
		if (current == 0) {
			return 1;
		}
		if (current == last) {
			return last - 1;
		}
		return current + (MatsimRandom.getLocalInstance().nextBoolean() ? 1 : -1);
	}

	private int softmaxFactor(CarrierState state, List<Integer> candidates) {
		double min = candidates.stream().mapToDouble(index -> state.factor(index).stableMean()).min().orElse(0.0);
		double max = candidates.stream().mapToDouble(index -> state.factor(index).stableMean()).max().orElse(min);
		double range = max - min;
		double[] weights = new double[candidates.size()];
		double total = 0.0;
		for (int i = 0; i < candidates.size(); i++) {
			double normalized = range == 0.0 ? 1.0 : (state.factor(candidates.get(i)).stableMean() - min) / range;
			weights[i] = Math.exp(config.getExploitationBeta() * normalized);
			total += weights[i];
		}
		double draw = MatsimRandom.getLocalInstance().nextDouble() * total;
		for (int i = 0; i < weights.length; i++) {
			draw -= weights[i];
			if (draw <= 0.0) {
				return candidates.get(i);
			}
		}
		return candidates.getLast();
	}

	private CheckpointEvent toEvent(int sequence, CarrierState state, FactorCheckpoint checkpoint,
			String eventType, boolean finalSelection) {
		List<ReceiverOutcome> outcomes = new ArrayList<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverContextSnapshot snapshot = checkpoint.receiverSnapshots.get(receiverId);
			ReceiverPlan selected = snapshot.plans.get(Math.max(0,
				Math.min(snapshot.selectedIndex, snapshot.plans.size() - 1)));
			TimeWindow window = selected.getTimeWindows().isEmpty() ? null : selected.getTimeWindows().getFirst();
			double score = checkpoint.receiverScoreMeans.getOrDefault(receiverId, Double.NaN);
			double baseline = receiverBaselines.getOrDefault(receiverId, Double.NaN);
			outcomes.add(new ReceiverOutcome(receiverId,
				window == null ? Double.NaN : window.getStart(), window == null ? Double.NaN : window.getEnd(),
				checkpoint.collaboratingReceivers.contains(receiverId), score, baseline, score - baseline));
		}
		return new CheckpointEvent(sequence, eventType, state.carrier.getId(), checkpoint.factorIndex,
			config.valueAt(checkpoint.factorIndex), state.visit,
			checkpoint.matureStable ? FactorMaturity.MATURE_STABLE : FactorMaturity.VALIDATED_UNSTABLE,
			checkpoint.evaluationStart, checkpoint.evaluationEnd, checkpoint.carrierScoreMean,
			state.baselineCarrierScore, checkpoint.carrierScoreMean - state.baselineCarrierScore,
			checkpoint.totalSurplusMean, checkpoint.participationFeasible,
			state.livePlans.containsKey(checkpoint.factorIndex), finalSelection, List.copyOf(outcomes));
	}

	private Map<Id<Receiver>, ReceiverPlan> selectedPlans(Collection<Id<Receiver>> receiverIds) {
		Map<Id<Receiver>, ReceiverPlan> result = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : receiverIds) {
			result.put(receiverId, receiver(receiverId).getSelectedPlan());
		}
		return result;
	}

	private void tagReceiverPlan(ReceiverPlan plan, int factorIndex, int generation, boolean outside) {
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, factorIndex);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION, generation);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, outside);
	}

	private void trimInitialReceiverMemory(Receiver receiver) {
		while (receiver.getPlans().size() > config.getMaxReceiverPlansPerFactor()) {
			ReceiverPlan removable = receiver.getPlans().stream()
				.filter(plan -> plan != receiver.getSelectedPlan())
				.filter(plan -> !isOutsideOption(plan))
				.min(Comparator.comparingDouble(plan -> plan.getScore() == null
					? Double.NEGATIVE_INFINITY : plan.getScore()))
				.orElseThrow(() -> new IllegalStateException("Cannot trim protected Receiver plan memory for "
					+ receiver.getId()));
			receiver.removePlan(removable);
		}
	}

	private static ReceiverPlan bestOrFirst(List<ReceiverPlan> plans) {
		return plans.stream().filter(plan -> plan.getScore() != null && Double.isFinite(plan.getScore()))
			.max(Comparator.comparingDouble(ReceiverPlan::getScore)).orElse(plans.getFirst());
	}

	private boolean close(double first, double second) {
		return relativeDelta(first, second) <= config.getStabilityRelativeTolerance();
	}

	private boolean rangeIsStable(List<Double> values) {
		if (values.isEmpty()) {
			return false;
		}
		double min = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		double max = values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		return relativeDelta(min, max) <= config.getStabilityRelativeTolerance();
	}

	private static double relativeDelta(double first, double second) {
		return Math.abs(first - second) / Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
	}

	private static boolean noWorse(double value, double baseline, double tolerance) {
		if (!Double.isFinite(value) || !Double.isFinite(baseline)) {
			return false;
		}
		double scale = Math.max(1.0, Math.max(Math.abs(value), Math.abs(baseline)));
		return value + tolerance * scale >= baseline;
	}

	private static double requireFiniteScore(Double value, String description) {
		if (value == null || !Double.isFinite(value)) {
			throw new IllegalStateException("Missing or non-finite " + description + ": " + value);
		}
		return value;
	}

	private static double clamp(double min, double max, double value) {
		return Math.max(min, Math.min(max, value));
	}

	private static int evictionTier(FactorRecord record) {
		return switch (record.maturity) {
			case VALIDATED_UNSTABLE, EVICTED, UNVISITED, ADAPTING -> 0;
			case MATURE_STABLE -> record.participationFeasible ? 2 : 1;
		};
	}

	private static double finiteOrNegativeInfinity(double value) {
		return Double.isFinite(value) ? value : Double.NEGATIVE_INFINITY;
	}

	private CarrierState requireState(Id<Carrier> carrierId) {
		CarrierState state = carrierStates.get(carrierId);
		if (state == null) {
			throw new IllegalArgumentException("Unknown mutable-AF carrier: " + carrierId);
		}
		return state;
	}

	public record CarrierSnapshot(
		Id<Carrier> carrierId,
		MutableAfPhase phase,
		int activeFactorIndex,
		double activeFactor,
		int visit,
		int dwell,
		int stableStreak,
		int evaluationCount,
		String decision,
		boolean routeReplanned,
		Double carrierScore,
		double baselineCarrierScore,
		double stableMean,
		double stableVariance,
		String incumbentProfile,
		boolean activeCoalition,
		int receiverCount,
		double totalSurplus,
		double signedTransfer,
		List<Integer> retainedFactorIndices,
		String evictionEvent,
		List<String> factorStates,
		boolean warmStartTrial,
		Integer warmStartSourceFactorIndex,
		boolean warmStartScoreClearedBeforeMobsim,
		String finalStatus
	) {
	}

	public record CheckpointEvent(
		int sequence,
		String eventType,
		Id<Carrier> carrierId,
		int factorIndex,
		double factor,
		int visit,
		FactorMaturity maturity,
		int evaluationStart,
		int evaluationEnd,
		double carrierScoreMean,
		double carrierBaseline,
		double carrierGain,
		double totalSurplus,
		boolean participationFeasible,
		boolean retained,
		boolean finalSelection,
		List<ReceiverOutcome> receiverOutcomes
	) {
	}

	public record ReceiverOutcome(
		Id<Receiver> receiverId,
		double timeWindowStart,
		double timeWindowEnd,
		boolean collaborating,
		double scoreMean,
		double baseline,
		double gain
	) {
	}

	private final class CarrierState {
		private final Carrier carrier;
		private final List<Id<Receiver>> receiverIds;
		private final Map<Integer, CarrierPlan> livePlans = new LinkedHashMap<>();
		private final Map<Integer, FactorRecord> factors = new LinkedHashMap<>();
		private final Map<Integer, Map<Id<Receiver>, ReceiverContext>> receiverContexts = new HashMap<>();
		private int activeFactorIndex;
		private MutableAfPhase phase = MutableAfPhase.BASELINE;
		private int visit = 1;
		private int dwell;
		private int stableStreak;
		private int evaluationCount;
		private boolean evaluationForced;
		private boolean routeReplanned;
		private double baselineCarrierScore = Double.NaN;
		private List<ScheduledTour> baselineTours = List.of();
		private String lastDecision = "INITIALIZE";
		private String lastEviction = "";
		private String finalStatus = "";
		private Observation lastObservation;
		private Observation previousAdaptObservation;
		private Map<Id<Receiver>, ReceiverPlan> evaluationSelection = Map.of();
		private FactorCheckpoint rollbackCheckpoint;
		private Integer warmStartSourceFactorIndex;
		private int warmStartExecutionIteration = -1;
		private boolean warmStartScoreClearedBeforeMobsim;
		private final List<Observation> evaluationObservations = new ArrayList<>();
		private final List<Observation> finalValidationObservations = new ArrayList<>();

		private CarrierState(Carrier carrier, int activeFactorIndex) {
			this.carrier = carrier;
			this.activeFactorIndex = activeFactorIndex;
			this.receiverIds = receiverOwners.entrySet().stream()
				.filter(entry -> entry.getValue().equals(carrier.getId()))
				.map(Map.Entry::getKey)
				.sorted(Comparator.comparing(Id::toString)).toList();
			if (receiverIds.isEmpty()) {
				throw new IllegalStateException("Mutable-AF carrier has no assigned receivers: " + carrier.getId());
			}
		}

		private FactorRecord factor(int index) {
			return factors.computeIfAbsent(index, ignored -> new FactorRecord());
		}
	}

	private static final class FactorRecord {
		private int visits;
		private int matureEvaluations;
		private int lastVisitedIteration = -1;
		private FactorMaturity maturity = FactorMaturity.UNVISITED;
		private boolean participationFeasible;
		private double mean;
		private double m2;
		private int scoreCount;
		private FactorCheckpoint bestStableCheckpoint;
		private FactorCheckpoint lastCheckpoint;

		private void addStableScore(double value) {
			scoreCount++;
			double delta = value - mean;
			mean += delta / scoreCount;
			m2 += delta * (value - mean);
		}

		private double stableMean() {
			return scoreCount == 0 ? Double.NaN : mean;
		}

		private double stableVariance() {
			return scoreCount < 2 ? 0.0 : m2 / (scoreCount - 1);
		}
	}

	private record ReceiverContext(List<ReceiverPlan> plans, ReceiverPlan selected, int generation) {
	}

	private record ReceiverContextSnapshot(List<ReceiverPlan> plans, int selectedIndex) {
	}

	private record FactorCheckpoint(
		int factorIndex,
		int evaluationStart,
		int evaluationEnd,
		double carrierScoreMean,
		double totalSurplusMean,
		Map<Id<Receiver>, Double> receiverScoreMeans,
		Set<Id<Receiver>> collaboratingReceivers,
		boolean participationFeasible,
		boolean matureStable,
		List<ScheduledTour> tours,
		Map<Id<Receiver>, ReceiverContextSnapshot> receiverSnapshots
	) {
	}

	private record Observation(
		int iteration,
		double carrierScore,
		Map<Id<Receiver>, Double> receiverScores,
		double receiverAggregateScore,
		String incumbentProfile,
		Set<Id<Receiver>> coalitionReceivers,
		double totalSurplus
	) {
	}
}
