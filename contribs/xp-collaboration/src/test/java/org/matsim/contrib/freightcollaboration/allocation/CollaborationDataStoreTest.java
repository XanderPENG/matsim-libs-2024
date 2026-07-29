package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.freight.carriers.Carrier;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationDataStoreTest {

	@Test
	void snapshotsInputMapsAndResetsIterationData() {
		Map<CollaboratorRole, Map<Id<?>, ? extends org.matsim.api.core.v01.population.BasicPlan>> originals =
			new java.util.EnumMap<>(CollaboratorRole.class);
		Map<Id<?>, org.matsim.api.core.v01.population.BasicPlan> receiverPlans = new HashMap<>();
		originals.put(CollaboratorRole.RECEIVER, receiverPlans);
		CollaborationDataStore store = new CollaborationDataStore(originals);
		receiverPlans.put(Id.create("later", Object.class),
			FreightCollaborationTestFixtures.receiver("later").getSelectedPlan());
		assertTrue(store.getOriginalPlans().get(CollaboratorRole.RECEIVER).isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> store.getOriginalPlans().clear());

		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		Map<Set<Id<?>>, Double> scores = new HashMap<>();
		scores.put(Set.of(), 1.0);
		store.addSimulatedCoalitionScores(coalition, scores);
		scores.put(Set.of(Id.create("receiver", Object.class)), 2.0);
		assertEquals(1, store.getSimulatedCoalitionScores().get(coalition).size());

		CollaboratorKey key = new CollaboratorKey(CollaboratorRole.RECEIVER,
			Id.create("receiver", Object.class));
		Map<CollaboratorKey, Double> allocations = new HashMap<>();
		allocations.put(key, 3.0);
		store.setAllocatedValues(allocations);
		allocations.put(key, 9.0);
		assertEquals(3.0, store.getAllocatedValue(key.role(), key.id()));

		store.reset();
		assertNull(store.getSimulatedCoalitionScores());
		assertNull(store.getAllocatedValues());
	}

	@Test
	void baselineAndCarrierSnapshotsAreDefensive() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		Id<Carrier> carrierId = Id.create("carrier", Carrier.class);
		Map<Id<Carrier>, Double> baseline = new HashMap<>();
		baseline.put(carrierId, -10.0);
		store.setIter0CarrierBaselineFeeFree(baseline);
		store.setIter0CarrierBaselineFeeIncluded(baseline);
		baseline.put(carrierId, 99.0);
		assertEquals(-10.0, store.getIter0CarrierBaselineFeeFree().get(carrierId));
		assertEquals(-10.0, store.getIter0CarrierBaselineFeeIncluded().get(carrierId));
	}
}
