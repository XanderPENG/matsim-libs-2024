package org.matsim.contrib.freightcollaboration.learning;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.receiver.Receiver;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAfSolutionSelectorTest {

	private static final Id<Carrier> CARRIER_ID = Id.create("carrier", Carrier.class);
	private static final Id<Receiver> RECEIVER_A = Id.create("receiver-a", Receiver.class);
	private static final Id<Receiver> RECEIVER_B = Id.create("receiver-b", Receiver.class);

	@Test
	void eachPolicySelectsItsOwnObjectiveAndFiltersInfeasibleCandidates() {
		MutableAfSolutionSelector selector = new MutableAfSolutionSelector(100.0,
			Map.of(RECEIVER_A, 10.0, RECEIVER_B, 20.0));
		ExecutedJointSnapshot carrierBest = snapshot(1, 0, 125.0, 11.0, 21.0, 5.0, true);
		ExecutedJointSnapshot receiverBest = snapshot(2, 1, 115.0, 25.0, 35.0, 8.0, true);
		ExecutedJointSnapshot surplusBest = snapshot(3, 2, 110.0, 15.0, 25.0, 40.0, true);
		ExecutedJointSnapshot infeasible = snapshot(4, 3, 1000.0, 1000.0, 1000.0, 1000.0, false);
		List<ExecutedJointSnapshot> candidates = List.of(
			carrierBest, receiverBest, surplusBest, infeasible);

		assertEquals(carrierBest, selector.selectBestObservation(candidates,
			MutableAfSelectionPolicy.CARRIER_BEST).orElseThrow());
		assertEquals(receiverBest, selector.selectBestObservation(candidates,
			MutableAfSelectionPolicy.RECEIVER_BEST).orElseThrow());
		assertEquals(surplusBest, selector.selectBestObservation(candidates,
			MutableAfSelectionPolicy.BEST_SURPLUS).orElseThrow());
		assertTrue(selector.selectBestObservation(List.of(infeasible),
			MutableAfSelectionPolicy.CARRIER_BEST).isEmpty());
	}

	@Test
	void observationAndCheckpointSelectionShareOneDeterministicOrdering() {
		MutableAfSolutionSelector selector = new MutableAfSolutionSelector(100.0,
			Map.of(RECEIVER_A, 10.0, RECEIVER_B, 20.0));
		ExecutedJointSnapshot older = snapshot(7, 1, 120.0, 15.0, 25.0, 10.0, true);
		ExecutedJointSnapshot newerHigherIndex = snapshot(8, 2, 120.0, 15.0, 25.0, 10.0, true);
		ExecutedJointSnapshot newerLowerIndex = snapshot(8, 0, 120.0, 15.0, 25.0, 10.0, true);

		ExecutedJointSnapshot selectedObservation = selector.selectBestObservation(
			List.of(older, newerHigherIndex, newerLowerIndex),
			MutableAfSelectionPolicy.CARRIER_BEST).orElseThrow();
		assertEquals(newerLowerIndex, selectedObservation,
			"newest execution wins first; the lower grid index breaks the final tie");

		List<FactorCheckpoint> checkpoints = List.of(
			checkpoint(older), checkpoint(newerHigherIndex), checkpoint(newerLowerIndex));
		FactorCheckpoint selectedCheckpoint = selector.selectBestCheckpoint(checkpoints,
			MutableAfSelectionPolicy.CARRIER_BEST).orElseThrow();
		assertEquals(newerLowerIndex.observation().executionIteration(),
			selectedCheckpoint.sourceIteration());
		assertEquals(newerLowerIndex.observation().factorIndex(), selectedCheckpoint.factorIndex());
	}

	@Test
	void policyXmlValuesAreStableAndMalformedValuesFailClearly() {
		assertEquals(MutableAfSelectionPolicy.CARRIER_BEST,
			MutableAfSelectionPolicy.parse("carrier-best"));
		assertEquals(MutableAfSelectionPolicy.RECEIVER_BEST,
			MutableAfSelectionPolicy.parse("receiver_best"));
		assertEquals(MutableAfSelectionPolicy.BEST_SURPLUS,
			MutableAfSelectionPolicy.parse("BEST-SURPLUS"));
		assertThrows(IllegalArgumentException.class,
			() -> MutableAfSelectionPolicy.parse("unknown"));
	}

	private static FactorCheckpoint checkpoint(ExecutedJointSnapshot snapshot) {
		return new FactorCheckpoint(snapshot.observation().factorIndex(), snapshot.allocationFactor(),
			1, snapshot.observation().executionIteration(), CheckpointReason.MAX_DWELL_FALLBACK,
			FactorMaturity.FALLBACK_CHECKPOINT, MutableAfSelectionPolicy.CARRIER_BEST,
			snapshot, WindowStatistics.empty(), snapshot.observation().carrierScore() - 100.0, true);
	}

	private static ExecutedJointSnapshot snapshot(int iteration, int factorIndex,
			double carrierScore, double receiverAScore, double receiverBScore,
			double surplus, boolean feasible) {
		Map<Id<Receiver>, Double> receiverScores = Map.of(
			RECEIVER_A, receiverAScore, RECEIVER_B, receiverBScore);
		ExecutedJointObservation observation = new ExecutedJointObservation(iteration, iteration,
			CARRIER_ID, factorIndex, 1, carrierScore, receiverScores,
			receiverAScore + receiverBScore, Set.of(RECEIVER_A), surplus, 0.0,
			"receiver-profile-" + iteration, "route-profile-" + iteration, feasible, feasible);
		return new ExecutedJointSnapshot(observation, factorIndex / 10.0, null, List.of(), Map.of());
	}
}
