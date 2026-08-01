package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.freight.carriers.Carrier;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutableAllocationFactorAllocationTest {

	@Test
	void exactAllocationResolvesFactorPerCoalitionAndRecordsSignedTransfers() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition low = coalitionWithScores(store, "low-carrier", "low-receiver");
		MutableFreightCoalition high = coalitionWithScores(store, "high-carrier", "high-receiver");

		CoalitionAllocationFactorResolver resolver = coalition ->
			AllocationUtils.extractSingleDistributor(coalition).getId().toString().startsWith("low") ? 0.2 : 0.8;
		new AllocationModelShapleyValue(store, resolver).allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(0.2, store.getAppliedAllocationFactors().get(low));
		assertEquals(0.8, store.getAppliedAllocationFactors().get(high));
		assertEquals(2.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, id("low-receiver")), 1e-12);
		assertEquals(8.0, store.getAllocatedValue(CollaboratorRole.CARRIER, id("low-carrier")), 1e-12);
		assertEquals(8.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, id("high-receiver")), 1e-12);
		assertEquals(2.0, store.getAllocatedValue(CollaboratorRole.CARRIER, id("high-carrier")), 1e-12);
		assertEquals(2.0,
			store.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, id("low-carrier")), 1e-12);
		assertEquals(8.0,
			store.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, id("high-carrier")), 1e-12);
		assertEquals(20.0,
			store.getAllocatedValues().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
	}

	@Test
	void negativeSavingsRemainSignedAndCarrierScoreAppliesTheOppositeTransfer() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		Id<?> receiver = coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)
			.iterator().next().getId();
		store.addSimulatedCoalitionScores(coalition, Map.of(Set.of(), 10.0, Set.of(receiver), 6.0));

		new AllocationModelShapleyValue(store, ignored -> 0.5)
			.allocate(AllocationValueTypes.COST_SAVINGS);

		assertEquals(-2.0, store.getAllocatedValue(CollaboratorRole.RECEIVER, receiver), 1e-12);
		assertEquals(-2.0,
			store.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, id("carrier")), 1e-12);
		Carrier carrier = (Carrier) AllocationUtils.extractSingleDistributor(coalition).getDelegate();
		MutableAfCarrierScoringFunctionFactory.SignedDistributorTransferScoring scoring =
			new MutableAfCarrierScoringFunctionFactory.SignedDistributorTransferScoring(carrier, store);
		assertEquals(2.0, scoring.getScore(), 1e-12);
	}

	@Test
	void fixedConstructorDoesNotPopulateMutableDiagnosticsAndResolverIsValidated() {
		CollaborationDataStore fixedStore = FreightCollaborationTestFixtures.emptyDataStore();
		coalitionWithScores(fixedStore, "carrier", "receiver");
		new AllocationModelShapleyValue(fixedStore, 0.4).allocate(AllocationValueTypes.COST_SAVINGS);
		assertTrue(fixedStore.getAppliedAllocationFactors().isEmpty());
		assertTrue(fixedStore.getDistributorPlayerTransfers().isEmpty());

		CollaborationDataStore invalidStore = FreightCollaborationTestFixtures.emptyDataStore();
		coalitionWithScores(invalidStore, "carrier", "receiver");
		assertThrows(IllegalArgumentException.class,
			() -> new AllocationModelShapleyValue(invalidStore, ignored -> 1.1)
				.allocate(AllocationValueTypes.COST_SAVINGS));
	}

	@Test
	void dataStoreViewsAreDefensiveAndResetClearsMutableIterationState() {
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		CollaboratorKey carrier = new CollaboratorKey(CollaboratorRole.CARRIER, id("carrier"));
		store.recordAppliedAllocationFactor(coalition, 0.7);
		store.recordDistributorPlayerTransfer(carrier, 3.0);
		store.recordDistributorPlayerTransfer(carrier, -1.0);

		assertEquals(2.0, store.getDistributorPlayerTransfers().get(carrier));
		assertThrows(UnsupportedOperationException.class, () -> store.getAppliedAllocationFactors().clear());
		assertThrows(UnsupportedOperationException.class, () -> store.getDistributorPlayerTransfers().clear());
		store.reset();
		assertTrue(store.getAppliedAllocationFactors().isEmpty());
		assertTrue(store.getDistributorPlayerTransfers().isEmpty());
	}

	private static MutableFreightCoalition coalitionWithScores(CollaborationDataStore store,
			String carrier, String receiver) {
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition(carrier, receiver);
		Id<?> receiverId = coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)
			.iterator().next().getId();
		store.addSimulatedCoalitionScores(coalition, Map.of(Set.of(), 10.0, Set.of(receiverId), 20.0));
		return coalition;
	}

	private static Id<?> id(String id) {
		return Id.create(id, Object.class);
	}
}
