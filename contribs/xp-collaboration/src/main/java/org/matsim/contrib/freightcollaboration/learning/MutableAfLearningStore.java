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
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
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
 * Mutable-AF joint learning state. Checkpoints are deep copies of states that completed a real
 * MATSim iteration; no plan assembled from independently best Receiver plans is eligible.
 */
@Singleton
public final class MutableAfLearningStore {
	private static final double FINAL_SURPLUS_EPSILON = 1e-9;
	private static final String JSPRIT_SCORE_ATTRIBUTE = "jspritScore";

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
		this.collaborationDataStore = Objects.requireNonNull(collaborationDataStore,
			"collaborationDataStore");
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
				"Mutable-AF carrier needs an initial selected plan before startup: " + carrier.getId());
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
						+ " has multiple live plans for AF grid index " + index);
				}
			}
			if (state.livePlans.size() > config.getMaxFactorPlans()) {
				throw new IllegalStateException("Carrier " + carrier.getId() + " starts with "
					+ state.livePlans.size() + " factor plans, exceeding MAX_FACTOR_PLANS="
					+ config.getMaxFactorPlans());
			}
			FactorRecord factor = state.factor(selectedIndex);
			factor.visits = 1;
			factor.maturity = FactorMaturity.ADAPTING;
			factor.lastVisitedIteration = scenario.getConfig().controller().getFirstIteration();
			carrierStates.put(carrier.getId(), state);
			initializeReceiverContext(state);
		}
		initialized = true;
	}

	/** Performs only transitions that are safe at the beginning of Replanning. */
	public synchronized void prepareReplanning(int iteration) {
		initialize();
		int finalization = config.finalizationIteration(scenario.getConfig().controller().getFirstIteration(),
			scenario.getConfig().controller().getLastIteration());
		int lastIteration = scenario.getConfig().controller().getLastIteration();
		for (CarrierState state : carrierStates.values()) {
			state.routeReplanned = false;
			state.lastEviction = "";
			state.lastDecision = "NO_DECISION";
			state.warmStartTrialThisIteration = false;
			state.warmStartSourceFactorIndex = null;
			state.warmStartScoreClearedBeforeMobsim = false;
			if (iteration == lastIteration) {
				prepareFinalSelection(state, iteration);
				continue;
			}
			if (iteration >= finalization) {
				if (state.phase != MutableAfPhase.FINAL_REVISIT) {
					enterFinalRevisit(state, iteration);
				} else {
					selectFinalRevisitCandidate(state, iteration);
				}
				continue;
			}
			switch (state.phase) {
				case BASELINE -> {
					state.phase = MutableAfPhase.ADAPT;
					state.lastDecision = "INITIAL_FACTOR";
				}
				case SWITCH_PENDING -> switchFactor(state, iteration);
				case ADAPT, WARM_START_TRIAL -> {
					// The Receiver manager performs the phase-specific action later in Replanning.
				}
				case FINAL_REVISIT, FINAL_SELECTION -> throw new IllegalStateException(
					"Final mutable-AF phase started before its configured iteration: " + state.phase);
			}
		}
	}

	/** Captures the state that actually executed and was scored in this iteration. */
	public synchronized void observeIterationEnd(int iteration) {
		initialize();
		int first = scenario.getConfig().controller().getFirstIteration();
		for (CarrierState state : carrierStates.values()) {
			if (iteration <= state.lastCapturedIteration) {
				throw new IllegalStateException("Carrier " + state.carrier.getId()
					+ " already captured iteration " + iteration + " (last="
					+ state.lastCapturedIteration + ")");
			}
			if (iteration == first) {
				if (state.phase != MutableAfPhase.BASELINE) {
					throw new IllegalStateException("Iteration-0 baseline captured in phase " + state.phase);
				}
				ExecutedJointSnapshot baseline = captureExecutedState(state, iteration, true);
				state.lastCapturedIteration = iteration;
				state.lastExecuted = baseline;
				captureBaseline(state, baseline);
				continue;
			}

			if (state.phase == MutableAfPhase.WARM_START_TRIAL) {
				observeWarmStartTrial(state, iteration);
				continue;
			}
			if (state.phase == MutableAfPhase.SWITCH_PENDING) {
				throw new IllegalStateException("Carrier " + state.carrier.getId()
					+ " executed iteration " + iteration + " while a factor switch was pending");
			}
			ExecutedJointSnapshot executed = captureExecutedState(state, iteration, false);
			state.lastCapturedIteration = iteration;
			state.lastExecuted = executed;
			switch (state.phase) {
				case ADAPT -> observeAdaptation(state, executed);
				case FINAL_REVISIT -> observeFinalRevisit(state, executed);
				case FINAL_SELECTION -> {
					if (state.finalExecutionIteration != iteration) {
						throw new IllegalStateException("FINAL_SELECTION was not prepared for real execution "
							+ "in iteration " + iteration);
					}
					state.finalSelectionExecutedScore = executed.observation().carrierScore();
					state.lastDecision = "FINAL_SELECTION_EXECUTED";
				}
				case BASELINE, SWITCH_PENDING, WARM_START_TRIAL -> throw new IllegalStateException(
					"Unexpected mutable-AF phase at iteration end: " + state.phase);
			}
		}
	}

	public synchronized MutableAfPhase phaseForReceiver(Id<Receiver> receiverId) {
		initialize();
		Id<Carrier> owner = receiverOwners.get(receiverId);
		return owner == null ? MutableAfPhase.FINAL_SELECTION : carrierStates.get(owner).phase;
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
		return Boolean.TRUE.equals(plan.getAttributes().getAttribute(
			MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION));
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

	public synchronized boolean hasPendingFinalExecution() {
		initialize();
		return carrierStates.values().stream().anyMatch(state ->
			state.phase == MutableAfPhase.FINAL_REVISIT
				|| state.phase == MutableAfPhase.FINAL_SELECTION);
	}

	/** Clears only the temporary cross-factor compatibility score immediately before execution. */
	public synchronized void beginWarmStartExecution(int iteration) {
		initialize();
		Map<CarrierState, List<ReceiverPlan>> validated = new LinkedHashMap<>();
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
			validated.put(state, state.receiverIds.stream()
				.map(receiverId -> requireWarmStartSelected(state, receiverId,
					"Cannot begin warm-start execution without a finite temporary Receiver score"))
				.toList());
		}
		validated.forEach((state, plans) -> {
			plans.forEach(plan -> plan.setScore(null));
			state.warmStartExecutionIteration = iteration;
			state.warmStartScoreClearedBeforeMobsim = true;
		});
	}

	/** Clears restored historical scores only after replanning has safely consumed them. */
	public synchronized void beginFinalExecution(int iteration) {
		initialize();
		for (CarrierState state : carrierStates.values()) {
			if (state.phase != MutableAfPhase.FINAL_REVISIT
				&& state.phase != MutableAfPhase.FINAL_SELECTION) {
				continue;
			}
			if (state.finalExecutionIteration == iteration) {
				continue;
			}
			CarrierPlan selectedCarrier = Objects.requireNonNull(state.carrier.getSelectedPlan(),
				"Final execution has no selected CarrierPlan for " + state.carrier.getId());
			for (Id<Receiver> receiverId : state.receiverIds) {
				ReceiverPlan selectedReceiver = Objects.requireNonNull(receiver(receiverId).getSelectedPlan(),
					"Final execution has no selected ReceiverPlan for " + receiverId);
				int context = contextIndex(selectedReceiver).orElseThrow(() -> new IllegalStateException(
					"Final execution ReceiverPlan has no factor context: " + receiverId));
				if (context != state.activeFactorIndex) {
					throw new IllegalStateException("Final execution Receiver context " + context
						+ " differs from active factor " + state.activeFactorIndex);
				}
				selectedReceiver.setScore(null);
			}
			selectedCarrier.setScore(null);
			state.finalExecutionIteration = iteration;
		}
	}

	public synchronized CarrierSnapshot snapshot(Id<Carrier> carrierId) {
		initialize();
		CarrierState state = requireState(carrierId);
		ExecutedJointObservation last = state.lastExecuted == null
			? null : state.lastExecuted.observation();
		WindowStatistics window = state.windowStatistics;
		return new CarrierSnapshot(carrierId, state.phase, state.activeFactorIndex,
			config.valueAt(state.activeFactorIndex), state.visit, state.dwell, state.lastDecision,
			state.routeReplanned, last == null ? null : last.executionIteration(),
			last == null ? null : last.carrierScore(), state.baselineCarrierScore,
			last == null ? "" : last.executedReceiverProfileHash(),
			last == null ? "" : last.carrierRouteProfileHash(), window.size(),
			window.carrierMin(), window.carrierMax(), window.carrierRelativeRange(),
			window.receiverMin(), window.receiverMax(), window.receiverRelativeRange(),
			window.coalitionSimilarity(), window.receiverParticipationFeasible(),
			last != null && !last.collaboratingReceivers().isEmpty(),
			last == null ? 0 : last.collaboratingReceivers().size(),
			last == null ? 0.0 : last.totalSurplus(),
			last == null ? 0.0 : last.signedTransfer(), config.getSolutionSelectionPolicy(),
			state.visitBest == null ? Double.NaN : selector(state).objective(state.visitBest,
				config.getSolutionSelectionPolicy()), state.lastCheckpointReason,
			state.lastCheckpointSourceIteration, state.livePlans.keySet().stream().sorted().toList(),
			state.lastEviction, state.factors.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getKey() + ":" + entry.getValue().maturity).toList(),
			state.warmStartTrialThisIteration, state.warmStartSourceFactorIndex,
			state.warmStartScoreClearedBeforeMobsim, state.finalStatus,
			state.finalRevisitCandidates.keySet().stream().sorted().toList(),
			state.finalRevisitSelectionCount, state.checkpointSelectionScore,
			state.finalSelectionExecutedScore);
	}

	public synchronized Map<Integer, FactorSummary> factorSummaries(Id<Carrier> carrierId) {
		initialize();
		CarrierState state = requireState(carrierId);
		Map<Integer, FactorSummary> result = new LinkedHashMap<>();
		state.factors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			FactorRecord record = entry.getValue();
			result.put(entry.getKey(), new FactorSummary(entry.getKey(), config.valueAt(entry.getKey()),
				record.visits, record.checkpoints, record.lastObjective,
				record.lastCheckpointSourceIteration, record.lastCheckpointReason,
				record.lastVisitedIteration, record.maturity, record.participationFeasible,
				state.livePlans.containsKey(entry.getKey())));
		});
		return Map.copyOf(result);
	}

	public synchronized Optional<FactorCheckpoint> checkpoint(Id<Carrier> carrierId, int factorIndex) {
		return Optional.ofNullable(requireState(carrierId).factor(factorIndex).checkpoint)
			.map(MutableAfLearningStore::copyCheckpoint);
	}

	public synchronized List<ExecutedJointObservation> recentObservations(Id<Carrier> carrierId) {
		return requireState(carrierId).recent.stream().map(ExecutedJointSnapshot::observation).toList();
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

	private void captureBaseline(CarrierState state, ExecutedJointSnapshot baseline) {
		state.baseline = baseline;
		state.baselineCarrierScore = baseline.observation().carrierScore();
		baseline.observation().receiverScores().forEach(receiverBaselines::put);
		for (Id<Receiver> receiverId : state.receiverIds) {
			Receiver receiver = receiver(receiverId);
			double score = receiverBaselines.get(receiverId);
			receiver.getPlans().stream().filter(this::isOutsideOption).forEach(plan -> plan.setScore(score));
		}
		state.lastDecision = "BASELINE_CAPTURED";
	}

	private void observeWarmStartTrial(CarrierState state, int iteration) {
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
		ExecutedJointSnapshot executed = captureExecutedState(state, iteration, false);
		state.lastCapturedIteration = iteration;
		state.lastExecuted = executed;
		state.dwell = 1;
		addVisitObservation(state, executed);
		state.phase = MutableAfPhase.ADAPT;
		state.lastDecision = "WARM_START_TRIAL_COMPLETE";
		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.lastVisitedIteration = iteration;
		if (factor.checkpoint == null) {
			factor.maturity = FactorMaturity.ADAPTING;
		}
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
			throw new IllegalStateException(missingScoreMessage + ": receiver=" + receiverId
				+ ", factorIndex=" + state.activeFactorIndex + ", score=" + selected.getScore());
		}
		return selected;
	}

	private void observeAdaptation(CarrierState state, ExecutedJointSnapshot executed) {
		state.dwell++;
		addVisitObservation(state, executed);
		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.lastVisitedIteration = executed.observation().executionIteration();
		if (factor.checkpoint == null) {
			factor.maturity = FactorMaturity.ADAPTING;
		}
		int minimumDwell = factor.checkpoint == null
			? config.getNewFactorMinDwell() : config.getRevisitFactorMinDwell();
		boolean stable = state.dwell >= minimumDwell
			&& state.windowStatistics.satisfies(config.getStabilityWindow(),
				config.getStabilityRelativeTolerance(),
				config.getCoalitionStabilityThreshold());
		if (stable) {
			ExecutedJointSnapshot candidate = executed.observation().participationFeasible()
				? executed : selector(state).selectBestObservation(state.recent,
					config.getSolutionSelectionPolicy()).orElse(null);
			if (candidate != null) {
				createCheckpoint(state, candidate, CheckpointReason.STABLE_WINDOW,
					FactorMaturity.STABLE_CHECKPOINT, state.windowStatistics);
				state.phase = MutableAfPhase.SWITCH_PENDING;
				state.lastDecision = "STABLE_CHECKPOINT_CREATED";
				return;
			}
		}
		if (state.dwell >= config.getMaxAdaptDwell()) {
			if (state.visitBest != null) {
				createCheckpoint(state, state.visitBest, CheckpointReason.MAX_DWELL_FALLBACK,
					FactorMaturity.FALLBACK_CHECKPOINT, state.windowStatistics);
				state.lastDecision = "MAX_DWELL_FALLBACK_CREATED";
			} else {
				if (factor.checkpoint == null) {
					factor.maturity = FactorMaturity.NO_FEASIBLE_CHECKPOINT;
				}
				state.lastDecision = "MAX_DWELL_NO_FEASIBLE_SOLUTION";
			}
			state.phase = MutableAfPhase.SWITCH_PENDING;
		}
	}

	private void addVisitObservation(CarrierState state, ExecutedJointSnapshot executed) {
		state.recent.addLast(executed);
		while (state.recent.size() > config.getStabilityWindow()) {
			state.recent.removeFirst();
		}
		state.windowStatistics = calculateWindowStatistics(state.recent);
		if (executed.observation().participationFeasible()) {
			List<ExecutedJointSnapshot> candidates = state.visitBest == null
				? List.of(executed) : List.of(state.visitBest, executed);
			state.visitBest = selector(state).selectBestObservation(candidates,
				config.getSolutionSelectionPolicy()).orElseThrow();
		}
	}

	private FactorCheckpoint createCheckpoint(CarrierState state, ExecutedJointSnapshot executed,
			CheckpointReason reason, FactorMaturity maturity, WindowStatistics statistics) {
		if (!executed.observation().participationFeasible()) {
			throw new IllegalArgumentException("Cannot checkpoint a participation-infeasible state.");
		}
		MutableAfSelectionPolicy policy = config.getSolutionSelectionPolicy();
		FactorCheckpoint candidate = new FactorCheckpoint(state.activeFactorIndex,
			config.valueAt(state.activeFactorIndex), state.visit,
			executed.observation().executionIteration(), reason, maturity, policy, executed,
			statistics, selector(state).objective(executed, policy), true);
		FactorRecord factor = state.factor(state.activeFactorIndex);
		factor.checkpoints++;
		factor.lastObjective = candidate.objectiveValue();
		factor.lastCheckpointSourceIteration = candidate.sourceIteration();
		factor.lastCheckpointReason = reason;
		factor.participationFeasible = true;
		boolean retained = shouldReplace(factor.checkpoint, candidate, state);
		if (retained) {
			factor.checkpoint = candidate;
			factor.maturity = maturity;
		}
		state.lastCheckpointReason = reason;
		state.lastCheckpointSourceIteration = candidate.sourceIteration();
		checkpointEvents.add(toEvent(++checkpointSequence, state, candidate,
			"CHECKPOINT", retained, false, ""));
		return candidate;
	}

	private boolean shouldReplace(FactorCheckpoint existing, FactorCheckpoint candidate,
			CarrierState state) {
		if (existing == null) {
			return true;
		}
		int existingTier = checkpointTier(existing.maturity());
		int candidateTier = checkpointTier(candidate.maturity());
		if (candidateTier != existingTier) {
			return candidateTier > existingTier;
		}
		return selector(state).selectBestCheckpoint(List.of(existing, candidate),
			config.getSolutionSelectionPolicy()).orElseThrow() == candidate;
	}

	private void switchFactor(CarrierState state, int iteration) {
		archiveActiveContext(state);
		Map<Id<Receiver>, ReceiverPlan> seedPlans = selectedPlans(state.receiverIds);
		int current = state.activeFactorIndex;
		List<Integer> stableAlternatives = factorCandidates(state, current,
			FactorMaturity.STABLE_CHECKPOINT);
		List<Integer> fallbackAlternatives = stableAlternatives.isEmpty()
			? factorCandidates(state, current, FactorMaturity.FALLBACK_CHECKPOINT) : List.of();
		List<Integer> exploitationCandidates = stableAlternatives.isEmpty()
			? fallbackAlternatives : stableAlternatives;
		double coverage = 1.0 - state.livePlans.size() / (double) config.getMaxFactorPlans();
		double explorationProbability = clamp(config.getMinExplorationProbability(),
			config.getMaxExplorationProbability(), config.getMutationWeight() * coverage);
		boolean explore = exploitationCandidates.isEmpty()
			|| MatsimRandom.getLocalInstance().nextDouble() < explorationProbability;
		int target = explore ? adjacentFactor(current)
			: softmaxFactor(state, exploitationCandidates);
		boolean revisit = state.receiverContexts.containsKey(target)
			|| state.factor(target).checkpoint != null;
		if (!activateFactor(state, target, current, seedPlans, revisit)) {
			state.phase = MutableAfPhase.ADAPT;
			startVisit(state, current, iteration);
			state.lastDecision = "MEMORY_BLOCKED";
			restoreReceiverContext(state, current);
			return;
		}
		startVisit(state, target, iteration);
		state.warmStartSourceFactorIndex = revisit ? null : current;
		state.warmStartExecutionIteration = -1;
		state.warmStartScoreClearedBeforeMobsim = false;
		state.warmStartTrialThisIteration = !revisit;
		state.phase = revisit ? MutableAfPhase.ADAPT : MutableAfPhase.WARM_START_TRIAL;
		state.lastDecision = (revisit ? "SWITCH_" : "WARM_START_")
			+ (explore ? "EXPLORE" : "EXPLOIT") + "_"
			+ (revisit ? "REVISIT" : "UNSEEN") + "_TO_" + config.valueAt(target);
	}

	private List<Integer> factorCandidates(CarrierState state, int current, FactorMaturity maturity) {
		return state.livePlans.keySet().stream().filter(index -> index != current)
			.filter(index -> state.factor(index).maturity == maturity)
			.filter(index -> state.factor(index).checkpoint != null
				&& state.factor(index).checkpoint.participationFeasible())
			.sorted().toList();
	}

	private boolean activateFactor(CarrierState state, int target, int sourceFactorIndex,
			Map<Id<Receiver>, ReceiverPlan> seedPlans, boolean revisit) {
		CarrierPlan targetPlan = state.livePlans.get(target);
		if (targetPlan == null) {
			Integer victim = state.livePlans.size() >= config.getMaxFactorPlans()
				? chooseEviction(state) : null;
			if (state.livePlans.size() >= config.getMaxFactorPlans() && victim == null) {
				return false;
			}
			CarrierPlan source = state.carrier.getSelectedPlan();
			targetPlan = MutableAfPlanUtils.copyCarrierPlan(source, false);
			CarrierAllocationFactor.set(targetPlan, config.valueAt(target), config);
			state.carrier.addPlan(targetPlan);
			state.livePlans.put(target, targetPlan);
			state.carrier.setSelectedPlan(targetPlan);
			if (victim != null) {
				evict(state, victim);
			}
		} else {
			state.carrier.setSelectedPlan(targetPlan);
		}
		state.activeFactorIndex = target;
		if (revisit) {
			restoreRevisitState(state, target);
		} else {
			targetPlan.setScore(null);
			Map<Id<Receiver>, ReceiverContext> contexts = createUnseenContexts(state, target,
				sourceFactorIndex, seedPlans);
			state.receiverContexts.put(target, contexts);
			restoreReceiverContexts(state, contexts);
		}
		return true;
	}

	private void startVisit(CarrierState state, int target, int iteration) {
		state.visit++;
		state.dwell = 0;
		state.recent.clear();
		state.windowStatistics = WindowStatistics.empty();
		state.visitBest = null;
		state.lastCheckpointReason = null;
		state.lastCheckpointSourceIteration = null;
		FactorRecord factor = state.factor(target);
		factor.visits++;
		factor.lastVisitedIteration = iteration;
		factor.maturity = factor.checkpoint == null
			? FactorMaturity.ADAPTING : factor.checkpoint.maturity();
	}

	private Integer chooseEviction(CarrierState state) {
		List<FactorCheckpoint> stable = state.livePlans.keySet().stream()
			.map(index -> state.factor(index).checkpoint)
			.filter(Objects::nonNull)
			.filter(checkpoint -> checkpoint.maturity() == FactorMaturity.STABLE_CHECKPOINT)
			.toList();
		List<FactorCheckpoint> pool = stable.isEmpty() ? state.livePlans.keySet().stream()
			.map(index -> state.factor(index).checkpoint).filter(Objects::nonNull)
			.filter(checkpoint -> checkpoint.maturity() == FactorMaturity.FALLBACK_CHECKPOINT)
			.toList() : stable;
		Optional<Integer> protectedBest = selector(state).selectBestCheckpoint(pool,
			config.getSolutionSelectionPolicy()).map(FactorCheckpoint::factorIndex);
		return state.livePlans.keySet().stream()
			.filter(index -> protectedBest.isEmpty() || !protectedBest.get().equals(index))
			.min(Comparator.comparingInt((Integer index) -> evictionTier(state.factor(index)))
				.thenComparingDouble(index -> finiteOrNegativeInfinity(state.factor(index).lastObjective))
				.thenComparingInt(index -> state.factor(index).lastVisitedIteration)
				.thenComparingInt(Integer::intValue)).orElse(null);
	}

	private void evict(CarrierState state, int factorIndex) {
		CarrierPlan victim = state.livePlans.remove(factorIndex);
		if (victim != null) {
			state.carrier.removePlan(victim);
		}
		state.receiverContexts.remove(factorIndex);
		FactorRecord record = state.factor(factorIndex);
		FactorCheckpoint historical = record.checkpoint;
		state.lastEviction = "EVICT_" + config.valueAt(factorIndex);
		if (historical != null) {
			checkpointEvents.add(toEvent(++checkpointSequence, state, historical,
				"EVICTION", false, false, ""));
		}
		// Keep the immutable checkpoint archive. It is not a live CarrierPlan and is needed
		// to reconstruct the globally best MAX_FACTOR_PLANS candidates for FINAL_REVISIT.
		record.maturity = FactorMaturity.EVICTED;
	}

	private void settleCurrentVisitBeforeFinalRevisit(CarrierState state) {
		if (state.phase == MutableAfPhase.ADAPT && state.visitBest != null) {
			createCheckpoint(state, state.visitBest, CheckpointReason.FINALIZATION_FALLBACK,
				FactorMaturity.FALLBACK_CHECKPOINT, state.windowStatistics);
			state.lastDecision = "FINALIZATION_VISIT_SETTLED";
		} else if (state.phase == MutableAfPhase.ADAPT
			&& state.factor(state.activeFactorIndex).checkpoint == null) {
			state.factor(state.activeFactorIndex).maturity = FactorMaturity.NO_FEASIBLE_CHECKPOINT;
		}
	}

	private void enterFinalRevisit(CarrierState state, int iteration) {
		settleCurrentVisitBeforeFinalRevisit(state);
		initializeFinalRevisitCandidates(state);
		state.phase = MutableAfPhase.FINAL_REVISIT;
		state.dwell = 0;
		state.recent.clear();
		state.windowStatistics = WindowStatistics.empty();
		state.visitBest = null;
		state.warmStartSourceFactorIndex = null;
		state.warmStartExecutionIteration = -1;
		state.warmStartScoreClearedBeforeMobsim = false;
		selectFinalRevisitCandidate(state, iteration);
	}

	private void initializeFinalRevisitCandidates(CarrierState state) {
		List<FactorCheckpoint> checkpoints = state.factors.values().stream()
			.map(record -> record.checkpoint).filter(Objects::nonNull)
			.filter(checkpoint -> isFinalSelectionEligible(
				checkpoint.executedState().observation()))
			.sorted(Comparator
				.comparingDouble((FactorCheckpoint checkpoint) ->
					checkpoint.executedState().observation().carrierScore()).reversed()
				.thenComparing(Comparator.comparingInt(FactorCheckpoint::sourceIteration).reversed())
				.thenComparingInt(FactorCheckpoint::factorIndex))
			.limit(config.getMaxFactorPlans()).toList();

		state.carrier.clearPlans();
		state.livePlans.clear();
		state.receiverContexts.clear();
		state.finalRevisitCandidates.clear();
		if (checkpoints.isEmpty()) {
			int initial = config.indexOf(config.getInitialAllocationFactor());
			installFinalRevisitCandidate(state, new FinalRevisitCandidate(initial, null, state.baseline));
			state.finalStatus = "FINAL_REVISIT_BASELINE_ONLY";
			return;
		}
		for (FactorCheckpoint checkpoint : checkpoints) {
			installFinalRevisitCandidate(state, new FinalRevisitCandidate(checkpoint.factorIndex(),
				checkpoint, checkpoint.executedState()));
		}
		state.finalStatus = "FINAL_REVISIT_ACTIVE";
	}

	private void installFinalRevisitCandidate(CarrierState state, FinalRevisitCandidate candidate) {
		ExecutedJointSnapshot snapshot = candidate.latestExecuted;
		CarrierPlan plan = new CarrierPlan(state.carrier,
			MutableAfPlanUtils.copyTours(snapshot.carrierTours()));
		restoreCarrierPlan(plan, snapshot);
		state.carrier.addPlan(plan);
		state.livePlans.put(candidate.factorIndex, plan);
		state.finalRevisitCandidates.put(candidate.factorIndex, candidate);
		if (candidate.checkpoint != null) {
			state.factor(candidate.factorIndex).maturity = candidate.checkpoint.maturity();
		}
	}

	private void selectFinalRevisitCandidate(CarrierState state, int iteration) {
		if (state.finalRevisitCandidates.isEmpty()) {
			throw new IllegalStateException("FINAL_REVISIT has no candidates for carrier "
				+ state.carrier.getId());
		}
		List<FinalRevisitCandidate> candidates = state.finalRevisitCandidates.values().stream()
			.sorted(Comparator.comparingInt(candidate -> candidate.factorIndex)).toList();
		double min = candidates.stream().mapToDouble(FinalRevisitCandidate::carrierScore)
			.min().orElseThrow();
		double max = candidates.stream().mapToDouble(FinalRevisitCandidate::carrierScore)
			.max().orElseThrow();
		double range = max - min;
		double[] weights = new double[candidates.size()];
		double total = 0.0;
		for (int index = 0; index < candidates.size(); index++) {
			double normalized = range == 0.0 ? 1.0
				: (candidates.get(index).carrierScore() - min) / range;
			weights[index] = Math.exp(config.getExploitationBeta() * (normalized - 1.0));
			total += weights[index];
		}
		double draw = MatsimRandom.getLocalInstance().nextDouble() * total;
		FinalRevisitCandidate selected = candidates.getLast();
		for (int index = 0; index < candidates.size(); index++) {
			draw -= weights[index];
			if (draw <= 0.0) {
				selected = candidates.get(index);
				break;
			}
		}
		restoreFinalRevisitCandidate(state, selected);
		state.finalRevisitSelectionCount++;
		state.finalExecutionIteration = -1;
		state.lastDecision = "FINAL_REVISIT_SELECT_" + config.valueAt(selected.factorIndex);
		state.factor(selected.factorIndex).lastVisitedIteration = iteration;
	}

	private void observeFinalRevisit(CarrierState state, ExecutedJointSnapshot executed) {
		if (state.finalExecutionIteration != executed.observation().executionIteration()) {
			throw new IllegalStateException("FINAL_REVISIT was not prepared for real execution in iteration "
				+ executed.observation().executionIteration());
		}
		FinalRevisitCandidate candidate = Objects.requireNonNull(
			state.finalRevisitCandidates.get(state.activeFinalRevisitFactorIndex),
			"Missing active FINAL_REVISIT candidate for factor " + state.activeFactorIndex);
		if (executed.observation().factorIndex() != candidate.factorIndex) {
			throw new IllegalStateException("FINAL_REVISIT executed factor "
				+ executed.observation().factorIndex() + " but selected candidate is "
				+ candidate.factorIndex);
		}
		candidate.latestExecuted = executed;
		candidate.evaluations++;
		if (candidate.checkpoint != null && isFinalSelectionEligible(executed.observation())) {
			candidate.latestEligible = executed;
			state.lastDecision = "FINAL_REVISIT_EVALUATED_" + config.valueAt(candidate.factorIndex);
		} else if (candidate.checkpoint == null) {
			state.lastDecision = "FINAL_REVISIT_BASELINE_PROBE_"
				+ config.valueAt(candidate.factorIndex);
		} else {
			state.lastDecision = "FINAL_REVISIT_INELIGIBLE_" + config.valueAt(candidate.factorIndex);
		}
	}

	private void prepareFinalSelection(CarrierState state, int iteration) {
		if (state.phase == MutableAfPhase.FINAL_SELECTION) {
			return;
		}
		if (state.phase != MutableAfPhase.FINAL_REVISIT) {
			settleCurrentVisitBeforeFinalRevisit(state);
			initializeFinalRevisitCandidates(state);
		}
		FinalRevisitCandidate selected = state.finalRevisitCandidates.values().stream()
			.max(Comparator.comparingDouble(FinalRevisitCandidate::eligibleCarrierScore)
				.thenComparingInt(candidate -> candidate.latestEligible.observation().executionIteration())
				.thenComparingInt(candidate -> -candidate.factorIndex))
			.orElseThrow(() -> new IllegalStateException("No final mutable-AF candidate for carrier "
				+ state.carrier.getId()));
		restoreFinalRevisitCandidate(state, selected, selected.latestEligible);
		state.phase = MutableAfPhase.FINAL_SELECTION;
		state.checkpointSelectionScore = selected.eligibleCarrierScore();
		state.lastCheckpointSourceIteration = selected.latestEligible.observation().executionIteration();
		if (selected.checkpoint == null) {
			state.finalStatus = "NO_FEASIBLE_FACTOR_BASELINE_FALLBACK";
			state.lastDecision = "FINAL_SELECTION_BASELINE";
			checkpointEvents.add(baselineEvent(++checkpointSequence, state,
				selected.latestEligible));
		} else {
			FactorCheckpoint finalView = finalSelectionView(state, selected);
			String tier = selected.checkpoint.maturity() == FactorMaturity.STABLE_CHECKPOINT
				? "STABLE" : "FALLBACK";
			state.finalStatus = "FINAL_SELECTION_" + tier;
			state.lastDecision = "FINAL_SELECTION_" + config.valueAt(selected.factorIndex);
			checkpointEvents.add(toEvent(++checkpointSequence, state, finalView,
				"FINAL_SELECTION", true, true, tier));
		}
		state.factor(state.activeFactorIndex).lastVisitedIteration = iteration;
		state.finalExecutionIteration = -1;
	}

	private FactorCheckpoint finalSelectionView(CarrierState state,
			FinalRevisitCandidate candidate) {
		FactorCheckpoint source = Objects.requireNonNull(candidate.checkpoint);
		ExecutedJointSnapshot executed = candidate.latestEligible;
		return new FactorCheckpoint(candidate.factorIndex, config.valueAt(candidate.factorIndex),
			source.visit(), executed.observation().executionIteration(), source.reason(),
			source.maturity(), MutableAfSelectionPolicy.CARRIER_BEST, executed,
			source.windowStatistics(), executed.observation().carrierScore()
				- state.baselineCarrierScore, executed.observation().participationFeasible());
	}

	private void restoreFinalRevisitCandidate(CarrierState state,
			FinalRevisitCandidate candidate) {
		restoreFinalRevisitCandidate(state, candidate, candidate.latestExecuted);
	}

	private void restoreFinalRevisitCandidate(CarrierState state,
			FinalRevisitCandidate candidate, ExecutedJointSnapshot snapshot) {
		CarrierPlan plan = Objects.requireNonNull(state.livePlans.get(candidate.factorIndex),
			"Final-revisit CarrierPlan is missing for factor " + candidate.factorIndex);
		state.carrier.setSelectedPlan(plan);
		restoreCarrierPlan(plan, snapshot);
		state.activeFactorIndex = candidate.factorIndex;
		state.activeFinalRevisitFactorIndex = candidate.factorIndex;
		restoreCheckpointReceivers(state, snapshot);
	}

	private void restoreCheckpoint(CarrierState state, FactorCheckpoint checkpoint) {
		CarrierPlan plan = Objects.requireNonNull(state.livePlans.get(checkpoint.factorIndex()),
			"Final checkpoint factor is no longer retained: " + checkpoint.factorIndex());
		state.carrier.setSelectedPlan(plan);
		restoreCarrierPlan(plan, checkpoint.executedState());
		state.activeFactorIndex = checkpoint.factorIndex();
		restoreCheckpointReceivers(state, checkpoint.executedState());
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
			plan = new CarrierPlan(state.carrier,
				MutableAfPlanUtils.copyTours(state.baseline.carrierTours()));
			state.carrier.addPlan(plan);
			state.livePlans.put(initial, plan);
			state.carrier.setSelectedPlan(plan);
			if (victim != null) {
				evict(state, victim);
			}
		}
		state.carrier.setSelectedPlan(plan);
		restoreCarrierPlan(plan, state.baseline);
		state.activeFactorIndex = initial;
		Map<Id<?>, ? extends BasicPlan> originals = collaborationDataStore.getOriginalPlans()
			.getOrDefault(CollaboratorRole.RECEIVER, Map.of());
		Map<Id<Receiver>, ReceiverContext> contexts = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverPlan original = (ReceiverPlan) originals.get(receiverId);
			if (original == null) {
				throw new IllegalStateException("Missing original receiver plan for baseline fallback: "
					+ receiverId);
			}
			ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(original, false);
			tagReceiverPlan(copy, initial, 0, true);
			copy.setScore(receiverBaselines.get(receiverId));
			Receiver receiver = receiver(receiverId);
			receiver.getPlans().clear();
			receiver.addPlan(copy);
			receiver.setSelectedPlan(copy);
			contexts.put(receiverId, new ReceiverContext(new ArrayList<>(List.of(copy)), copy, 0));
		}
		state.receiverContexts.put(initial, contexts);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			selectedProfile(selectedPlans(state.receiverIds)));
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

	private void restoreRevisitState(CarrierState state, int target) {
		Map<Id<Receiver>, ReceiverContext> contexts = state.receiverContexts.get(target);
		FactorCheckpoint checkpoint = state.factor(target).checkpoint;
		if (contexts == null) {
			if (checkpoint == null) {
				throw new IllegalStateException("Missing Receiver context and checkpoint for factor " + target);
			}
			CarrierPlan plan = state.carrier.getSelectedPlan();
			restoreCarrierPlan(plan, checkpoint.executedState());
			restoreCheckpointReceivers(state, checkpoint.executedState());
			return;
		}
		if (checkpoint != null) {
			CarrierPlan plan = state.carrier.getSelectedPlan();
			restoreCarrierPlan(plan, checkpoint.executedState());
			contexts = mergeCheckpointSelection(contexts, checkpoint.executedState());
			state.receiverContexts.put(target, contexts);
		}
		restoreReceiverContexts(state, contexts);
	}

	private Map<Id<Receiver>, ReceiverContext> mergeCheckpointSelection(
			Map<Id<Receiver>, ReceiverContext> contexts, ExecutedJointSnapshot checkpoint) {
		Map<Id<Receiver>, ReceiverContext> merged = new LinkedHashMap<>();
		for (Map.Entry<Id<Receiver>, ReceiverContext> entry : contexts.entrySet()) {
			ReceiverPlan checkpointPlan = checkpoint.selectedReceiverPlans().get(entry.getKey());
			if (checkpointPlan == null) {
				throw new IllegalStateException("Checkpoint lacks receiver " + entry.getKey());
			}
			List<ReceiverPlan> plans = new ArrayList<>(entry.getValue().plans);
			String signature = MutableAfPlanUtils.receiverPlanSignature(checkpointPlan);
			ReceiverPlan selected = plans.stream()
				.filter(plan -> MutableAfPlanUtils.receiverPlanSignature(plan).equals(signature))
				.findFirst().orElseGet(() -> {
					ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(checkpointPlan, true);
					plans.add(copy);
					return copy;
				});
			selected.setScore(checkpointPlan.getScore());
			MutableAfPlanUtils.clearPendingEvaluation(selected);
			merged.put(entry.getKey(), new ReceiverContext(plans, selected, entry.getValue().generation));
		}
		return merged;
	}

	private void restoreReceiverContext(CarrierState state, int target) {
		restoreReceiverContexts(state, Objects.requireNonNull(state.receiverContexts.get(target),
			"Missing Receiver context for factor " + target));
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

	private void restoreCheckpointReceivers(CarrierState state, ExecutedJointSnapshot checkpoint) {
		Map<Id<Receiver>, ReceiverContext> contexts = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverPlan snapshot = checkpoint.selectedReceiverPlans().get(receiverId);
			if (snapshot == null) {
				throw new IllegalStateException("Checkpoint lacks receiver " + receiverId);
			}
			ReceiverPlan copy = MutableAfPlanUtils.copyReceiverPlan(snapshot, true);
			MutableAfPlanUtils.clearPendingEvaluation(copy);
			Receiver receiver = receiver(receiverId);
			receiver.getPlans().clear();
			receiver.addPlan(copy);
			receiver.setSelectedPlan(copy);
			contexts.put(receiverId, new ReceiverContext(new ArrayList<>(List.of(copy)), copy, 0));
		}
		state.receiverContexts.put(state.activeFactorIndex, contexts);
	}

	private ExecutedJointSnapshot captureExecutedState(CarrierState state, int iteration,
			boolean baseline) {
		CarrierPlan selected = Objects.requireNonNull(state.carrier.getSelectedPlan(),
			"Carrier has no selected plan at iteration end: " + state.carrier.getId());
		int selectedFactor = config.indexOf(CarrierAllocationFactor.require(selected, config));
		if (selectedFactor != state.activeFactorIndex) {
			throw new IllegalStateException("Selected CarrierPlan factor " + selectedFactor
				+ " differs from active factor " + state.activeFactorIndex + " at iteration " + iteration);
		}
		double carrierScore = requireFiniteScore(selected.getScore(),
			"carrier score at iteration " + iteration + " for " + state.carrier.getId());
		Map<Id<Receiver>, Double> receiverScores = new LinkedHashMap<>();
		Map<Id<Receiver>, ReceiverPlan> selectedReceiverPlans = new LinkedHashMap<>();
		double receiverAggregate = 0.0;
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverPlan receiverPlan = Objects.requireNonNull(receiver(receiverId).getSelectedPlan(),
				"Receiver has no selected plan: " + receiverId);
			int context = contextIndex(receiverPlan).orElseThrow(() -> new IllegalStateException(
				"Receiver selected plan has no AF context: " + receiverId));
			if (context != state.activeFactorIndex) {
				throw new IllegalStateException("Receiver " + receiverId + " context " + context
					+ " differs from active factor " + state.activeFactorIndex + " at iteration " + iteration);
			}
			if (MutableAfPlanUtils.isPendingEvaluation(receiverPlan)) {
				throw new IllegalStateException("Cannot capture pending Receiver plan at iteration end: "
					+ receiverId);
			}
			double score = requireFiniteScore(receiverPlan.getScore(),
				"receiver score at iteration " + iteration + " for " + receiverId);
			receiverScores.put(receiverId, score);
			selectedReceiverPlans.put(receiverId, receiverPlan);
			receiverAggregate += score;
		}
		String executedProfile = selectedProfile(selectedReceiverPlans);
		Object storedRouteProfile = selected.getAttributes().getAttribute(
			MutableAfPlanUtils.CARRIER_ROUTE_PROFILE);
		if (!(storedRouteProfile instanceof String routeProfile)
			|| !routeProfile.equals(executedProfile)) {
			throw new IllegalStateException("Carrier route profile does not match executed Receiver plans at iteration "
				+ iteration + ": carrier=" + state.carrier.getId() + ", stored=" + storedRouteProfile
				+ ", executed=" + executedProfile);
		}
		if (!baseline && collaborationDataStore.getCollaborationResultIteration() != iteration) {
			throw new IllegalStateException("Collaboration result for carrier " + state.carrier.getId()
				+ " is stamped iteration " + collaborationDataStore.getCollaborationResultIteration()
				+ " but execution snapshot is iteration " + iteration);
		}
		Set<Id<Receiver>> collaborating = coalitionReceivers(state.carrier.getId());
		if (!baseline) {
			validateCollaborationResults(state, iteration, collaborating);
		}
		double surplus = selectionSurplus(state.carrier.getId());
		double transfer = collaborationDataStore.getDistributorPlayerTransfer(
			CollaboratorRole.CARRIER, state.carrier.getId());
		boolean receiverFeasible = baseline || receiverParticipationFeasible(receiverScores, collaborating);
		boolean feasible = baseline || receiverFeasible
			&& noWorse(carrierScore, state.baselineCarrierScore,
				config.getParticipationRelativeTolerance());
		ExecutedJointObservation observation = new ExecutedJointObservation(iteration, iteration,
			state.carrier.getId(), state.activeFactorIndex, state.visit, carrierScore,
			Map.copyOf(receiverScores), receiverAggregate, collaborating, surplus, transfer,
			hash(executedProfile), hash(routeProfile), receiverFeasible, feasible);
		return new ExecutedJointSnapshot(observation, config.valueAt(state.activeFactorIndex),
			selected.getJspritScore(), MutableAfPlanUtils.copyTours(selected.getScheduledTours()),
			selectedReceiverPlans);
	}

	private void validateCollaborationResults(CarrierState state, int iteration,
			Set<Id<Receiver>> collaborating) {
		if (collaborating.isEmpty()) {
			return;
		}
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> simulatedScores =
			collaborationDataStore.getSimulatedCoalitionScores();
		boolean hasCharacteristicFunction = simulatedScores != null
			&& simulatedScores.entrySet().stream().anyMatch(entry ->
				entry.getKey().contains(CollaboratorRole.CARRIER, state.carrier.getId())
					&& !entry.getValue().isEmpty());
		if (!hasCharacteristicFunction) {
			throw new IllegalStateException("Active collaboration for carrier " + state.carrier.getId()
				+ " at iteration " + iteration
				+ " has no current-iteration characteristic-function result");
		}
		double active = config.valueAt(state.activeFactorIndex);
		boolean hasAppliedFactor = false;
		for (Map.Entry<MutableFreightCoalition, Double> entry
				: collaborationDataStore.getAppliedAllocationFactors().entrySet()) {
			if (!entry.getKey().contains(CollaboratorRole.CARRIER, state.carrier.getId())) {
				continue;
			}
			hasAppliedFactor = true;
			if (Math.abs(entry.getValue() - active) > 1e-9) {
				throw new IllegalStateException("Allocation at iteration " + iteration + " used factor "
					+ entry.getValue() + " but selected CarrierPlan uses " + active);
			}
		}
		if (!hasAppliedFactor) {
			throw new IllegalStateException("Active collaboration for carrier " + state.carrier.getId()
				+ " at iteration " + iteration + " has no recorded allocation factor");
		}
	}

	private boolean receiverParticipationFeasible(Map<Id<Receiver>, Double> receiverScores,
			Set<Id<Receiver>> collaborating) {
		for (Id<Receiver> receiverId : collaborating) {
			if (!noWorse(receiverScores.getOrDefault(receiverId, Double.NaN),
				receiverBaselines.getOrDefault(receiverId, Double.NaN),
				config.getParticipationRelativeTolerance())) {
				return false;
			}
		}
		return true;
	}

	private WindowStatistics calculateWindowStatistics(Collection<ExecutedJointSnapshot> window) {
		if (window.isEmpty()) {
			return WindowStatistics.empty();
		}
		List<ExecutedJointObservation> observations = window.stream()
			.map(ExecutedJointSnapshot::observation).toList();
		double carrierMin = observations.stream().mapToDouble(ExecutedJointObservation::carrierScore)
			.min().orElseThrow();
		double carrierMax = observations.stream().mapToDouble(ExecutedJointObservation::carrierScore)
			.max().orElseThrow();
		double receiverMin = observations.stream()
			.mapToDouble(ExecutedJointObservation::receiverAggregateScore).min().orElseThrow();
		double receiverMax = observations.stream()
			.mapToDouble(ExecutedJointObservation::receiverAggregateScore).max().orElseThrow();
		double surplusMean = observations.stream().mapToDouble(ExecutedJointObservation::totalSurplus)
			.average().orElseThrow();
		double surplusMin = observations.stream().mapToDouble(ExecutedJointObservation::totalSurplus)
			.min().orElseThrow();
		double surplusMax = observations.stream().mapToDouble(ExecutedJointObservation::totalSurplus)
			.max().orElseThrow();
		double variance = observations.size() < 2 ? 0.0 : observations.stream()
			.mapToDouble(value -> Math.pow(value.totalSurplus() - surplusMean, 2)).sum()
			/ (observations.size() - 1);
		double coalitionSimilarity = coalitionSimilarity(observations.stream()
			.map(ExecutedJointObservation::collaboratingReceivers).toList());
		boolean receiverFeasible = observations.stream()
			.allMatch(ExecutedJointObservation::receiverParticipationFeasible);
		return new WindowStatistics(observations.size(), carrierMin, carrierMax,
			relativeRange(carrierMin, carrierMax), receiverMin, receiverMax,
			relativeRange(receiverMin, receiverMax), surplusMean, surplusMin, surplusMax,
			variance, coalitionSimilarity, receiverFeasible);
	}

	static double coalitionSimilarity(Collection<Set<Id<Receiver>>> memberships) {
		if (memberships.isEmpty()) {
			return Double.NaN;
		}
		Set<Id<Receiver>> union = new LinkedHashSet<>();
		Set<Id<Receiver>> intersection = null;
		for (Set<Id<Receiver>> members : memberships) {
			union.addAll(members);
			if (intersection == null) {
				intersection = new LinkedHashSet<>(members);
			} else {
				intersection.retainAll(members);
			}
		}
		return union.isEmpty() ? 1.0
			: intersection.size() / (double) union.size();
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

	/** COST_SAVINGS selection metric: full-coalition score minus empty-coalition score. */
	private double selectionSurplus(Id<Carrier> carrierId) {
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> scores =
			collaborationDataStore.getSimulatedCoalitionScores();
		if (scores == null) {
			return 0.0;
		}
		double result = 0.0;
		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : scores.entrySet()) {
			if (!entry.getKey().contains(CollaboratorRole.CARRIER, carrierId)
				|| entry.getValue().isEmpty()) {
				continue;
			}
			Double baseline = entry.getValue().get(Set.of());
			if (baseline == null || !Double.isFinite(baseline)) {
				throw new IllegalStateException("Mutable COST_SAVINGS observation lacks an empty-coalition baseline.");
			}
			double full = entry.getValue().entrySet().stream()
				.max(Comparator.comparingInt(value -> value.getKey().size()))
				.map(Map.Entry::getValue).orElse(baseline);
			if (!Double.isFinite(full)) {
				throw new IllegalStateException("Mutable COST_SAVINGS observation has non-finite full score.");
			}
			result += full - baseline;
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
				throw new IllegalStateException("Mutable-AF v1 requires each receiver to reference exactly one "
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
		double min = candidates.stream().mapToDouble(index -> state.factor(index).checkpoint.objectiveValue())
			.min().orElse(0.0);
		double max = candidates.stream().mapToDouble(index -> state.factor(index).checkpoint.objectiveValue())
			.max().orElse(min);
		double range = max - min;
		double[] weights = new double[candidates.size()];
		double total = 0.0;
		for (int i = 0; i < candidates.size(); i++) {
			double objective = state.factor(candidates.get(i)).checkpoint.objectiveValue();
			double normalized = range == 0.0 ? 1.0 : (objective - min) / range;
			weights[i] = Math.exp(config.getExploitationBeta() * (normalized - 1.0));
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
			String eventType, boolean retained, boolean finalSelection, String finalSelectionTier) {
		ExecutedJointObservation observation = checkpoint.executedState().observation();
		MutableAfSolutionSelector selector = selector(state);
		List<ReceiverOutcome> outcomes = new ArrayList<>();
		for (Id<Receiver> receiverId : state.receiverIds) {
			ReceiverPlan selected = checkpoint.executedState().selectedReceiverPlans().get(receiverId);
			TimeWindow window = selected.getTimeWindows().isEmpty()
				? null : selected.getTimeWindows().getFirst();
			double score = observation.receiverScores().getOrDefault(receiverId, Double.NaN);
			double baseline = receiverBaselines.getOrDefault(receiverId, Double.NaN);
			outcomes.add(new ReceiverOutcome(receiverId,
				window == null ? Double.NaN : window.getStart(),
				window == null ? Double.NaN : window.getEnd(),
				observation.collaboratingReceivers().contains(receiverId), score, baseline,
				score - baseline));
		}
		return new CheckpointEvent(sequence, eventType, state.carrier.getId(),
			checkpoint.factorIndex(), checkpoint.factor(), checkpoint.visit(), checkpoint.reason(),
			checkpoint.maturity(), checkpoint.sourceIteration(), checkpoint.selectionPolicy(),
			checkpoint.objectiveValue(), observation.carrierScore(), state.baselineCarrierScore,
			selector.carrierGain(observation), observation.receiverAggregateScore(),
			selector.receiverAggregateBaseline(), selector.receiverAggregateGain(observation),
			selector.minimumReceiverGain(observation), observation.totalSurplus(),
			checkpoint.participationFeasible(), checkpoint.reason() == CheckpointReason.STABLE_WINDOW,
			retained, finalSelection, finalSelectionTier, List.copyOf(outcomes));
	}

	private CheckpointEvent baselineEvent(int sequence, CarrierState state,
			ExecutedJointSnapshot executed) {
		ExecutedJointObservation observation = executed.observation();
		MutableAfSolutionSelector selector = selector(state);
		return new CheckpointEvent(sequence, "FINAL_SELECTION", state.carrier.getId(),
			observation.factorIndex(), executed.allocationFactor(), observation.visit(), null,
			FactorMaturity.NO_FEASIBLE_CHECKPOINT, observation.executionIteration(),
			MutableAfSelectionPolicy.CARRIER_BEST, observation.carrierScore() - state.baselineCarrierScore,
			observation.carrierScore(), state.baselineCarrierScore,
			0.0, observation.receiverAggregateScore(), selector.receiverAggregateBaseline(), 0.0,
			0.0, observation.totalSurplus(), true, false, true, true, "BASELINE", List.of());
	}

	private Map<Id<Receiver>, ReceiverPlan> selectedPlans(Collection<Id<Receiver>> receiverIds) {
		Map<Id<Receiver>, ReceiverPlan> result = new LinkedHashMap<>();
		for (Id<Receiver> receiverId : receiverIds) {
			result.put(receiverId, receiver(receiverId).getSelectedPlan());
		}
		return result;
	}

	private static String selectedProfile(Map<Id<Receiver>, ReceiverPlan> plans) {
		StringJoiner profile = new StringJoiner("|");
		plans.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Id::toString)))
			.forEach(entry -> profile.add(entry.getKey() + "="
				+ MutableAfPlanUtils.receiverPlanSignature(entry.getValue())));
		return profile.toString();
	}

	private void tagReceiverPlan(ReceiverPlan plan, int factorIndex, int generation, boolean outside) {
		MutableAfPlanUtils.clearPendingEvaluation(plan);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX, factorIndex);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION, generation);
		plan.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, outside);
	}

	private void trimInitialReceiverMemory(Receiver receiver) {
		while (receiver.getPlans().size() > config.getMaxReceiverPlansPerFactor()) {
			ReceiverPlan removable = receiver.getPlans().stream()
				.filter(plan -> plan != receiver.getSelectedPlan()).filter(plan -> !isOutsideOption(plan))
				.min(Comparator.comparingDouble(plan -> plan.getScore() == null
					? Double.NEGATIVE_INFINITY : plan.getScore()))
				.orElseThrow(() -> new IllegalStateException(
					"Cannot trim protected Receiver plan memory for " + receiver.getId()));
			receiver.removePlan(removable);
		}
	}

	private static ReceiverPlan bestOrFirst(List<ReceiverPlan> plans) {
		return plans.stream().filter(plan -> plan.getScore() != null && Double.isFinite(plan.getScore()))
			.max(Comparator.comparingDouble(ReceiverPlan::getScore)).orElse(plans.getFirst());
	}

	private MutableAfSolutionSelector selector(CarrierState state) {
		Map<Id<Receiver>, Double> baselines = new LinkedHashMap<>();
		state.receiverIds.forEach(id -> baselines.put(id, receiverBaselines.getOrDefault(id, 0.0)));
		return new MutableAfSolutionSelector(Double.isFinite(state.baselineCarrierScore)
			? state.baselineCarrierScore : 0.0, baselines);
	}

	private static double relativeRange(double min, double max) {
		return Math.abs(max - min) / Math.max(1.0, Math.max(Math.abs(max), Math.abs(min)));
	}

	private static boolean noWorse(double value, double baseline, double tolerance) {
		if (!Double.isFinite(value) || !Double.isFinite(baseline)) {
			return false;
		}
		double scale = Math.max(1.0, Math.max(Math.abs(value), Math.abs(baseline)));
		return value + tolerance * scale >= baseline;
	}

	static boolean isFinalSelectionEligible(ExecutedJointObservation observation) {
		Objects.requireNonNull(observation, "observation");
		return observation.participationFeasible()
			&& Double.isFinite(observation.totalSurplus())
			&& observation.totalSurplus() >= -FINAL_SURPLUS_EPSILON;
	}

	private void restoreCarrierPlan(CarrierPlan plan, ExecutedJointSnapshot snapshot) {
		MutableAfPlanUtils.replaceTours(plan, snapshot.carrierTours());
		plan.setScore(snapshot.observation().carrierScore());
		CarrierAllocationFactor.set(plan, snapshot.allocationFactor(), config);
		if (snapshot.carrierJspritScore() == null) {
			plan.getAttributes().removeAttribute(JSPRIT_SCORE_ATTRIBUTE);
		} else {
			plan.setJspritScore(snapshot.carrierJspritScore());
		}
		plan.getAttributes().putAttribute(MutableAfPlanUtils.CARRIER_ROUTE_PROFILE,
			selectedProfile(snapshot.selectedReceiverPlans()));
	}

	private static double requireFiniteScore(Double value, String description) {
		if (value == null || !Double.isFinite(value)) {
			throw new IllegalStateException("Missing or non-finite " + description + ": " + value);
		}
		return value;
	}

	private static String hash(String value) {
		return Integer.toUnsignedString(value.hashCode(), 16);
	}

	private static double clamp(double min, double max, double value) {
		return Math.max(min, Math.min(max, value));
	}

	private static int checkpointTier(FactorMaturity maturity) {
		return switch (maturity) {
			case STABLE_CHECKPOINT -> 2;
			case FALLBACK_CHECKPOINT -> 1;
			case UNVISITED, ADAPTING, NO_FEASIBLE_CHECKPOINT, EVICTED -> 0;
		};
	}

	private static int evictionTier(FactorRecord record) {
		return checkpointTier(record.maturity);
	}

	private static double finiteOrNegativeInfinity(double value) {
		return Double.isFinite(value) ? value : Double.NEGATIVE_INFINITY;
	}

	private static FactorCheckpoint copyCheckpoint(FactorCheckpoint source) {
		ExecutedJointSnapshot snapshot = source.executedState();
		ExecutedJointSnapshot copy = new ExecutedJointSnapshot(snapshot.observation(),
			snapshot.allocationFactor(), snapshot.carrierJspritScore(), snapshot.carrierTours(),
			snapshot.selectedReceiverPlans());
		return new FactorCheckpoint(source.factorIndex(), source.factor(), source.visit(),
			source.sourceIteration(), source.reason(), source.maturity(), source.selectionPolicy(),
			copy, source.windowStatistics(), source.objectiveValue(), source.participationFeasible());
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
		String decision,
		boolean routeReplanned,
		Integer executionIteration,
		Double carrierScore,
		double baselineCarrierScore,
		String executedProfileHash,
		String carrierRouteProfileHash,
		int stabilityWindowSize,
		double carrierWindowMin,
		double carrierWindowMax,
		double carrierWindowRelativeRange,
		double receiverWindowMin,
		double receiverWindowMax,
		double receiverWindowRelativeRange,
		double coalitionWindowSimilarity,
		boolean receiverParticipationWindowFeasible,
		boolean activeCoalition,
		int receiverCount,
		double totalSurplus,
		double signedTransfer,
		MutableAfSelectionPolicy selectionPolicy,
		double visitBestObjective,
		CheckpointReason checkpointReason,
		Integer checkpointSourceIteration,
		List<Integer> retainedFactorIndices,
		String evictionEvent,
		List<String> factorStates,
		boolean warmStartTrial,
		Integer warmStartSourceFactorIndex,
		boolean warmStartScoreClearedBeforeMobsim,
		String finalStatus,
		List<Integer> finalRevisitCandidateIndices,
		int finalRevisitSelectionCount,
		double checkpointSelectionScore,
		double finalSelectionExecutedScore
	) {
	}

	public record CheckpointEvent(
		int sequence,
		String eventType,
		Id<Carrier> carrierId,
		int factorIndex,
		double factor,
		int visit,
		CheckpointReason checkpointReason,
		FactorMaturity maturity,
		int sourceIteration,
		MutableAfSelectionPolicy selectionPolicy,
		double objectiveValue,
		double carrierScore,
		double carrierBaseline,
		double carrierGain,
		double receiverAggregateScore,
		double receiverAggregateBaseline,
		double receiverAggregateGain,
		double minimumReceiverGain,
		double totalSurplus,
		boolean participationFeasible,
		boolean stableWindow,
		boolean retained,
		boolean finalSelection,
		String finalSelectionTier,
		List<ReceiverOutcome> receiverOutcomes
	) {
	}

	public record ReceiverOutcome(
		Id<Receiver> receiverId,
		double timeWindowStart,
		double timeWindowEnd,
		boolean collaborating,
		double score,
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
		private final Deque<ExecutedJointSnapshot> recent = new ArrayDeque<>();
		private int activeFactorIndex;
		private MutableAfPhase phase = MutableAfPhase.BASELINE;
		private int visit = 1;
		private int dwell;
		private boolean routeReplanned;
		private double baselineCarrierScore = Double.NaN;
		private ExecutedJointSnapshot baseline;
		private ExecutedJointSnapshot lastExecuted;
		private ExecutedJointSnapshot visitBest;
		private WindowStatistics windowStatistics = WindowStatistics.empty();
		private int lastCapturedIteration = -1;
		private String lastDecision = "INITIALIZE";
		private String lastEviction = "";
		private String finalStatus = "";
		private CheckpointReason lastCheckpointReason;
		private Integer lastCheckpointSourceIteration;
		private Integer warmStartSourceFactorIndex;
		private int warmStartExecutionIteration = -1;
		private boolean warmStartScoreClearedBeforeMobsim;
		private boolean warmStartTrialThisIteration;
		private final Map<Integer, FinalRevisitCandidate> finalRevisitCandidates =
			new LinkedHashMap<>();
		private int activeFinalRevisitFactorIndex = -1;
		private int finalRevisitSelectionCount;
		private int finalExecutionIteration = -1;
		private double checkpointSelectionScore = Double.NaN;
		private double finalSelectionExecutedScore = Double.NaN;

		private CarrierState(Carrier carrier, int activeFactorIndex) {
			this.carrier = carrier;
			this.activeFactorIndex = activeFactorIndex;
			this.receiverIds = receiverOwners.entrySet().stream()
				.filter(entry -> entry.getValue().equals(carrier.getId())).map(Map.Entry::getKey)
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
		private int checkpoints;
		private int lastVisitedIteration = -1;
		private FactorMaturity maturity = FactorMaturity.UNVISITED;
		private boolean participationFeasible;
		private double lastObjective = Double.NaN;
		private int lastCheckpointSourceIteration = -1;
		private CheckpointReason lastCheckpointReason;
		private FactorCheckpoint checkpoint;
	}

	private static final class FinalRevisitCandidate {
		private final int factorIndex;
		private final FactorCheckpoint checkpoint;
		private ExecutedJointSnapshot latestExecuted;
		private ExecutedJointSnapshot latestEligible;
		private int evaluations;

		private FinalRevisitCandidate(int factorIndex, FactorCheckpoint checkpoint,
				ExecutedJointSnapshot latestExecuted) {
			this.factorIndex = factorIndex;
			this.checkpoint = checkpoint;
			this.latestExecuted = Objects.requireNonNull(latestExecuted, "latestExecuted");
			this.latestEligible = latestExecuted;
		}

		private double carrierScore() {
			return latestExecuted.observation().carrierScore();
		}

		private double eligibleCarrierScore() {
			return latestEligible.observation().carrierScore();
		}
	}

	private record ReceiverContext(List<ReceiverPlan> plans, ReceiverPlan selected, int generation) {
	}
}
