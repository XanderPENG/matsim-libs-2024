package org.matsim.contrib.freightcollaboration.strategy;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPhase;
import org.matsim.contrib.freightcollaboration.learning.MutableAfPlanUtils;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.replanning.GenericPlanStrategy;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.selectors.PlanSelector;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.replanning.ReceiverStrategyManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Factor-aware Receiver replanning used only by the mutable-AF run. The standard receiver
 * listener calls this manager with cumulative collections; processed receiver ids are therefore
 * de-duplicated per iteration.
 */
@Singleton
public final class MutableAfReceiverStrategyManager implements ReceiverStrategyManager {

	private static final double SELECTION_WEIGHT = 0.7;
	private static final double MUTATION_WEIGHT = 0.3;
	private static final double EXP_BETA = 10.0;
	private static final double TW_STEP = 3600.0;
	private static final double MAX_TW_WIDTH = 12 * 3600.0;
	private static final double LATEST_TW_END = 18 * 3600.0;

	private final MutableAfLearningStore learningStore;
	private final MutableAllocationFactorConfigGroup config;
	private final CollaborationDataStore collaborationDataStore;
	private final Set<Id<Receiver>> processed = new HashSet<>();
	private int processedIteration = Integer.MIN_VALUE;
	private int maxPlans;

	@Inject
	public MutableAfReceiverStrategyManager(MutableAfLearningStore learningStore,
			MutableAllocationFactorConfigGroup config, CollaborationDataStore collaborationDataStore) {
		this.learningStore = Objects.requireNonNull(learningStore, "learningStore");
		this.config = Objects.requireNonNull(config, "config");
		this.collaborationDataStore = Objects.requireNonNull(collaborationDataStore, "collaborationDataStore");
		this.maxPlans = config.getMaxReceiverPlansPerFactor();
	}

	@Override
	public synchronized void run(Iterable<? extends HasPlansAndId<ReceiverPlan, Receiver>> receivers,
			int iteration, ReplanningContext replanningContext) {
		if (iteration != processedIteration) {
			processedIteration = iteration;
			processed.clear();
		}
		for (HasPlansAndId<ReceiverPlan, Receiver> owner : receivers) {
			Receiver receiver = (Receiver) owner;
			if (!processed.add(receiver.getId())) {
				continue;
			}
			MutableAfPhase phase = learningStore.phaseForReceiver(receiver.getId());
			switch (phase) {
				case WARM_START_TRIAL -> keepWarmStartSelected(receiver, phase);
				case EVALUATE, FINAL_VALIDATION ->
					learningStore.incumbent(receiver).ifPresent(receiver::setSelectedPlan);
				case ADAPT -> selectOrMutate(receiver);
				case BASELINE, SWITCH_PENDING -> {
					// Keep the current plan. Factor/context changes are owned by the coordinator.
				}
			}
		}
	}

	private void keepWarmStartSelected(Receiver receiver, MutableAfPhase phase) {
		ReceiverPlan selected = receiver.getSelectedPlan();
		if (selected == null) {
			throw new IllegalStateException("Warm-start trial has no selected plan: receiver="
				+ receiver.getId() + ", phase=" + phase);
		}
		int activeFactor = learningStore.activeFactorIndexForReceiver(receiver.getId());
		int planFactor = learningStore.contextIndex(selected).orElse(-1);
		if (planFactor != activeFactor || !MutableAfPlanUtils.isPendingEvaluation(selected)
			|| selected.getScore() == null || !Double.isFinite(selected.getScore())) {
			throw new IllegalStateException("Invalid Receiver warm-start plan: receiver=" + receiver.getId()
				+ ", factor=" + activeFactor + ", planFactor=" + planFactor + ", phase=" + phase
				+ ", pending=" + MutableAfPlanUtils.isPendingEvaluation(selected)
				+ ", score=" + selected.getScore());
		}
	}

	private void selectOrMutate(Receiver receiver) {
		double draw = MatsimRandom.getLocalInstance().nextDouble() * (SELECTION_WEIGHT + MUTATION_WEIGHT);
		if (draw < SELECTION_WEIGHT || receiver.getPlans().isEmpty()) {
			receiver.setSelectedPlan(select(receiver));
		} else {
			mutate(receiver);
		}
	}

	private void mutate(Receiver receiver) {
		ReceiverPlan parent = select(receiver);
		ReceiverPlan mutated = MutableAfPlanUtils.copyReceiverPlan(parent, false);
		mutated.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_OUTSIDE_OPTION, false);
		mutated.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_FACTOR_INDEX,
			learningStore.activeFactorIndexForReceiver(receiver.getId()));
		Object generation = parent.getAttributes().getAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION);
		mutated.getAttributes().putAttribute(MutableAfPlanUtils.RECEIVER_CONTEXT_GENERATION,
			generation instanceof Number number ? number.intValue() + 1 : 1);
		if (mutated.getTimeWindows().isEmpty()) {
			throw new IllegalStateException("Receiver plan has no time window: " + receiver.getId());
		}
		TimeWindow current = mutated.getTimeWindows().getFirst();
		double originalEnd = originalUpperBound(receiver.getId());
		double maximumEnd = Math.min(LATEST_TW_END, current.getStart() + MAX_TW_WIDTH);
		double candidate = MatsimRandom.getLocalInstance().nextBoolean()
			? current.getEnd() + TW_STEP : current.getEnd() - TW_STEP;
		double newEnd = Math.max(originalEnd, Math.min(maximumEnd, candidate));
		mutated.getTimeWindows().set(0, TimeWindow.newInstance(current.getStart(), newEnd));
		receiver.addPlan(mutated);
		receiver.setSelectedPlan(mutated);
		trim(receiver);
	}

	private ReceiverPlan select(Receiver receiver) {
		List<ReceiverPlan> plans = receiver.getPlans().stream()
			.filter(plan -> !MutableAfPlanUtils.isPendingEvaluation(plan))
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		if (plans.isEmpty()) {
			throw new IllegalStateException("Receiver has no plans: " + receiver.getId());
		}
		List<ReceiverPlan> scored = plans.stream()
			.filter(plan -> plan.getScore() != null && Double.isFinite(plan.getScore())).toList();
		if (scored.isEmpty()) {
			return receiver.getSelectedPlan() == null ? plans.getFirst() : receiver.getSelectedPlan();
		}
		double min = scored.stream().mapToDouble(ReceiverPlan::getScore).min().orElseThrow();
		double max = scored.stream().mapToDouble(ReceiverPlan::getScore).max().orElseThrow();
		double range = max - min;
		double total = 0.0;
		double[] weights = new double[scored.size()];
		for (int index = 0; index < scored.size(); index++) {
			double normalized = range == 0.0 ? 1.0 : (scored.get(index).getScore() - min) / range;
			weights[index] = Math.exp(EXP_BETA * normalized);
			total += weights[index];
		}
		double draw = MatsimRandom.getLocalInstance().nextDouble() * total;
		for (int index = 0; index < weights.length; index++) {
			draw -= weights[index];
			if (draw <= 0.0) {
				return scored.get(index);
			}
		}
		return scored.getLast();
	}

	private void trim(Receiver receiver) {
		while (receiver.getPlans().size() > maxPlans) {
			ReceiverPlan duplicate = findInferiorDuplicate(receiver);
			ReceiverPlan removable = duplicate != null ? duplicate : receiver.getPlans().stream()
				.filter(plan -> plan != receiver.getSelectedPlan())
				.filter(plan -> !learningStore.isOutsideOption(plan))
				.min(Comparator.comparingDouble(MutableAfReceiverStrategyManager::removalScore)
					.thenComparing(MutableAfPlanUtils::receiverPlanSignature))
				.orElseThrow(() -> new IllegalStateException("No removable Receiver plan for " + receiver.getId()));
			receiver.removePlan(removable);
		}
	}

	private ReceiverPlan findInferiorDuplicate(Receiver receiver) {
		Map<String, List<ReceiverPlan>> bySignature = receiver.getPlans().stream()
			.filter(plan -> plan != receiver.getSelectedPlan())
			.filter(plan -> !learningStore.isOutsideOption(plan))
			.collect(java.util.stream.Collectors.groupingBy(MutableAfPlanUtils::receiverPlanSignature));
		return bySignature.values().stream().filter(plans -> plans.size() > 1)
			.flatMap(List::stream)
			.min(Comparator.comparingDouble(MutableAfReceiverStrategyManager::removalScore))
			.orElse(null);
	}

	private double originalUpperBound(Id<Receiver> receiverId) {
		Map<Id<?>, ? extends org.matsim.api.core.v01.population.BasicPlan> originals =
			collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER);
		if (originals == null || !(originals.get(receiverId) instanceof ReceiverPlan original)
			|| original.getTimeWindows().isEmpty()) {
			throw new IllegalStateException("Missing original Receiver time window for " + receiverId);
		}
		return original.getTimeWindows().getFirst().getEnd();
	}

	private static double removalScore(ReceiverPlan plan) {
		return plan.getScore() == null || !Double.isFinite(plan.getScore())
			? Double.NEGATIVE_INFINITY : plan.getScore();
	}

	@Override
	public void addStrategy(GenericPlanStrategy<ReceiverPlan, Receiver> strategy, String subpopulation, double weight) {
		throw new UnsupportedOperationException("MutableAfReceiverStrategyManager has a fixed factor-aware policy.");
	}

	@Override
	public void setMaxPlansPerAgent(int maxPlansPerAgent) {
		if (maxPlansPerAgent < 1) {
			throw new IllegalArgumentException("maxPlansPerAgent must be positive");
		}
		this.maxPlans = maxPlansPerAgent;
	}

	@Override
	public void addChangeRequest(int iteration, GenericPlanStrategy<ReceiverPlan, Receiver> strategy,
			String subpopulation, double newWeight) {
		throw new UnsupportedOperationException("Mutable-AF phase changes are controlled by MutableAfLearningStore.");
	}

	@Override
	public void setPlanSelectorForRemoval(PlanSelector<ReceiverPlan, Receiver> planSelector) {
		throw new UnsupportedOperationException("Mutable-AF protects factor contexts and its outside option explicitly.");
	}

	@Override
	public List<GenericPlanStrategy<ReceiverPlan, Receiver>> getStrategies(String subpopulation) {
		return List.of();
	}

	@Override
	public List<Double> getWeights(String subpopulation) {
		return List.of(SELECTION_WEIGHT, MUTATION_WEIGHT);
	}
}
