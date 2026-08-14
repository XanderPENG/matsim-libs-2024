package org.matsim.contrib.freightcollaboration.learning;

import org.matsim.api.core.v01.Id;
import org.matsim.freight.receiver.Receiver;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** One deterministic ranking definition shared by all mutable-AF decisions. */
public final class MutableAfSolutionSelector {

	private final double carrierBaseline;
	private final Map<Id<Receiver>, Double> receiverBaselines;

	/** Useful for isolated policy tests whose scores are already expressed as gains. */
	public MutableAfSolutionSelector() {
		this(0.0, Map.of());
	}

	public MutableAfSolutionSelector(double carrierBaseline,
			Map<Id<Receiver>, Double> receiverBaselines) {
		this.carrierBaseline = carrierBaseline;
		this.receiverBaselines = Map.copyOf(Objects.requireNonNull(receiverBaselines,
			"receiverBaselines"));
	}

	public Optional<ExecutedJointSnapshot> selectBestObservation(
			Collection<ExecutedJointSnapshot> candidates, MutableAfSelectionPolicy policy) {
		Objects.requireNonNull(candidates, "candidates");
		return candidates.stream()
			.filter(candidate -> candidate.observation().participationFeasible())
			.max(comparator(ExecutedJointSnapshot::observation,
				snapshot -> snapshot.observation().factorIndex(), policy));
	}

	public Optional<FactorCheckpoint> selectBestCheckpoint(
			Collection<FactorCheckpoint> candidates, MutableAfSelectionPolicy policy) {
		Objects.requireNonNull(candidates, "candidates");
		return candidates.stream()
			.filter(FactorCheckpoint::participationFeasible)
			.max(comparator(checkpoint -> checkpoint.executedState().observation(),
				FactorCheckpoint::factorIndex, policy));
	}

	public double objective(ExecutedJointSnapshot snapshot, MutableAfSelectionPolicy policy) {
		return metrics(snapshot.observation(), policy).objective;
	}

	public double carrierGain(ExecutedJointObservation observation) {
		return observation.carrierScore() - carrierBaseline;
	}

	public double receiverAggregateBaseline() {
		return receiverBaselines.values().stream().mapToDouble(Double::doubleValue).sum();
	}

	public double receiverAggregateGain(ExecutedJointObservation observation) {
		return observation.receiverAggregateScore() - receiverAggregateBaseline();
	}

	public double minimumReceiverGain(ExecutedJointObservation observation) {
		return observation.receiverScores().entrySet().stream()
			.mapToDouble(entry -> entry.getValue() - receiverBaselines.getOrDefault(entry.getKey(), 0.0))
			.min().orElse(0.0);
	}

	private <T> Comparator<T> comparator(Function<T, ExecutedJointObservation> observation,
			java.util.function.ToIntFunction<T> factorIndex, MutableAfSelectionPolicy policy) {
		Objects.requireNonNull(policy, "policy");
		return Comparator
			.comparingDouble((T candidate) -> metrics(observation.apply(candidate), policy).objective)
			.thenComparingDouble(candidate -> metrics(observation.apply(candidate), policy).minimumReceiverGain)
			.thenComparingDouble(candidate -> metrics(observation.apply(candidate), policy).carrierGain)
			.thenComparingDouble(candidate -> metrics(observation.apply(candidate), policy).receiverAggregateGain)
			.thenComparingDouble(candidate -> observation.apply(candidate).totalSurplus())
			.thenComparingInt(candidate -> observation.apply(candidate).executionIteration())
			.thenComparingInt(candidate -> -factorIndex.applyAsInt(candidate));
	}

	private Metrics metrics(ExecutedJointObservation observation, MutableAfSelectionPolicy policy) {
		double carrierGain = carrierGain(observation);
		double receiverGain = receiverAggregateGain(observation);
		double objective = switch (policy) {
			case CARRIER_BEST -> carrierGain;
			case RECEIVER_BEST -> receiverGain;
			case BEST_SURPLUS -> observation.totalSurplus();
		};
		return new Metrics(objective, minimumReceiverGain(observation), carrierGain, receiverGain);
	}

	private record Metrics(double objective, double minimumReceiverGain,
			double carrierGain, double receiverAggregateGain) {
	}
}
