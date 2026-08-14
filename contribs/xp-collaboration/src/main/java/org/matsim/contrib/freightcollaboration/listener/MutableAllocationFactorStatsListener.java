package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarriersUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

/** Writes immutable iteration observations and append-only real-execution checkpoints. */
public final class MutableAllocationFactorStatsListener implements StartupListener, IterationEndsListener {

	public static final String OUTPUT_FILE = "mutable_allocation_factor_stats.csv";
	public static final String LOCAL_OPTIMA_FILE = "mutable_allocation_factor_local_optima.csv";
	public static final String RECEIVER_OUTCOMES_FILE = "mutable_allocation_factor_receiver_outcomes.csv";

	private final Scenario scenario;
	private final OutputDirectoryHierarchy output;
	private final MutableAfLearningStore learningStore;
	private int lastWrittenCheckpointSequence;

	@Inject
	public MutableAllocationFactorStatsListener(Scenario scenario, OutputDirectoryHierarchy output,
			MutableAfLearningStore learningStore) {
		this.scenario = Objects.requireNonNull(scenario, "scenario");
		this.output = Objects.requireNonNull(output, "output");
		this.learningStore = Objects.requireNonNull(learningStore, "learningStore");
	}

	@Override
	public double priority() {
		return -100.0;
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		learningStore.initialize();
		writeHeader(OUTPUT_FILE, "iteration,carrierId,phase,activeFactorIndex,activeFactor,visit,dwell,"
			+ "decision,routeReplanned,warmStartTrial,warmStartSourceFactorIndex,"
			+ "warmStartScoreClearedBeforeMobsim,executionIteration,carrierScore,carrierBaseline,"
			+ "executedProfileHash,carrierRouteProfileHash,stabilityWindowSize,carrierWindowMin,"
			+ "carrierWindowMax,carrierWindowRelativeRange,receiverWindowMin,receiverWindowMax,"
			+ "receiverWindowRelativeRange,coalitionWindowSimilarity,"
			+ "receiverParticipationWindowFeasible,activeCoalition,receiverCount,"
			+ "totalSurplus,signedTransfer,selectionPolicy,visitBestObjective,checkpointReason,"
			+ "checkpointSourceIteration,retainedFactorIndices,evictionEvent,factorStates,finalStatus,"
			+ "finalRevisitCandidateIndices,finalRevisitSelectionCount,checkpointSelectionScore,"
			+ "finalSelectionExecutedScore\n");
		writeHeader(LOCAL_OPTIMA_FILE, "sequence,eventType,carrierId,factorIndex,factor,visit,"
			+ "checkpointReason,maturity,sourceIteration,selectionPolicy,objectiveValue,carrierScore,"
			+ "carrierBaseline,carrierGain,receiverAggregateScore,receiverAggregateBaseline,"
			+ "receiverAggregateGain,minimumReceiverGain,totalSurplus,participationFeasible,"
			+ "stableWindow,retained,finalSelection,finalSelectionTier\n");
		writeHeader(RECEIVER_OUTCOMES_FILE, "sequence,eventType,carrierId,factor,checkpointIteration,"
			+ "receiverId,timeWindowStart,timeWindowEnd,collaborating,score,baseline,gain\n");
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		try (BufferedWriter writer = IOUtils.getAppendingBufferedWriter(output.getOutputFilename(OUTPUT_FILE))) {
			for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
				.sorted(Comparator.comparing(value -> value.getId().toString())).toList()) {
				writeIteration(writer, event.getIteration(), learningStore.snapshot(carrier.getId()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not append mutable allocation-factor stats.", e);
		}
		appendNewCheckpointEvents();
	}

	private void appendNewCheckpointEvents() {
		var events = learningStore.checkpointEvents().stream()
			.filter(event -> event.sequence() > lastWrittenCheckpointSequence).toList();
		if (events.isEmpty()) {
			return;
		}
		try (BufferedWriter optima = IOUtils.getAppendingBufferedWriter(output.getOutputFilename(LOCAL_OPTIMA_FILE));
				 BufferedWriter receivers = IOUtils.getAppendingBufferedWriter(
					 output.getOutputFilename(RECEIVER_OUTCOMES_FILE))) {
			for (MutableAfLearningStore.CheckpointEvent event : events) {
				optima.write(event.sequence() + "," + event.eventType() + ","
					+ csv(event.carrierId().toString()) + "," + event.factorIndex() + "," + event.factor()
					+ "," + event.visit() + "," + nullable(event.checkpointReason()) + ","
					+ event.maturity() + "," + event.sourceIteration() + ","
					+ event.selectionPolicy().configValue()
					+ "," + event.objectiveValue() + "," + event.carrierScore() + ","
					+ event.carrierBaseline() + "," + event.carrierGain() + ","
					+ event.receiverAggregateScore() + "," + event.receiverAggregateBaseline() + ","
					+ event.receiverAggregateGain() + "," + event.minimumReceiverGain() + ","
					+ event.totalSurplus() + "," + event.participationFeasible() + ","
					+ event.stableWindow() + "," + event.retained() + "," + event.finalSelection()
					+ "," + event.finalSelectionTier() + "\n");
				for (MutableAfLearningStore.ReceiverOutcome outcome : event.receiverOutcomes()) {
					receivers.write(event.sequence() + "," + event.eventType() + ","
						+ csv(event.carrierId().toString()) + "," + event.factor() + ","
						+ event.sourceIteration() + "," + csv(outcome.receiverId().toString()) + ","
						+ outcome.timeWindowStart() + "," + outcome.timeWindowEnd() + ","
						+ outcome.collaborating() + "," + outcome.score() + "," + outcome.baseline()
						+ "," + outcome.gain() + "\n");
				}
				lastWrittenCheckpointSequence = event.sequence();
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not append mutable allocation-factor checkpoint output.", e);
		}
	}

	private static void writeIteration(BufferedWriter writer, int iteration,
			MutableAfLearningStore.CarrierSnapshot snapshot) throws IOException {
		String retained = snapshot.retainedFactorIndices().stream().map(String::valueOf)
			.collect(Collectors.joining(";"));
		String finalCandidates = snapshot.finalRevisitCandidateIndices().stream().map(String::valueOf)
			.collect(Collectors.joining(";"));
		String factorStates = String.join(";", snapshot.factorStates());
		writer.write(iteration + "," + csv(snapshot.carrierId().toString()) + "," + snapshot.phase()
			+ "," + snapshot.activeFactorIndex() + "," + snapshot.activeFactor() + "," + snapshot.visit()
			+ "," + snapshot.dwell() + "," + snapshot.decision() + "," + snapshot.routeReplanned()
			+ "," + snapshot.warmStartTrial() + "," + nullable(snapshot.warmStartSourceFactorIndex())
			+ "," + snapshot.warmStartScoreClearedBeforeMobsim() + ","
			+ nullable(snapshot.executionIteration()) + "," + nullable(snapshot.carrierScore()) + ","
			+ snapshot.baselineCarrierScore() + "," + snapshot.executedProfileHash() + ","
			+ snapshot.carrierRouteProfileHash() + "," + snapshot.stabilityWindowSize() + ","
			+ snapshot.carrierWindowMin() + "," + snapshot.carrierWindowMax() + ","
			+ snapshot.carrierWindowRelativeRange() + "," + snapshot.receiverWindowMin() + ","
			+ snapshot.receiverWindowMax() + "," + snapshot.receiverWindowRelativeRange() + ","
			+ snapshot.coalitionWindowSimilarity() + ","
			+ snapshot.receiverParticipationWindowFeasible() + "," + snapshot.activeCoalition() + ","
			+ snapshot.receiverCount() + "," + snapshot.totalSurplus() + "," + snapshot.signedTransfer()
			+ "," + snapshot.selectionPolicy().configValue() + "," + snapshot.visitBestObjective() + ","
			+ nullable(snapshot.checkpointReason()) + "," + nullable(snapshot.checkpointSourceIteration())
			+ "," + csv(retained) + "," + csv(snapshot.evictionEvent()) + "," + csv(factorStates)
			+ "," + snapshot.finalStatus() + "," + csv(finalCandidates) + ","
			+ snapshot.finalRevisitSelectionCount() + "," + snapshot.checkpointSelectionScore() + ","
			+ snapshot.finalSelectionExecutedScore() + "\n");
	}

	private void writeHeader(String file, String header) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(output.getOutputFilename(file))) {
			writer.write(header);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not initialize mutable allocation-factor output " + file, e);
		}
	}

	private static String nullable(Object value) {
		return value == null ? "" : value.toString();
	}

	private static String csv(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
