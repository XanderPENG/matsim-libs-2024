package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelApproxShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelMarginalContribution;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelProportional;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModelShapleyValue;
import org.matsim.contrib.freightcollaboration.allocation.AllocationModels;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationUtilsTest {

	@Test
	void subsetGenerationCoversZeroOneAndNAndEnforcesExactCap() {
		assertEquals(Set.of(Set.of()), AllocationUtils.generateSubsets(Map.of()).keySet());
		Id<?> a = Id.create("a", Object.class);
		Id<?> b = Id.create("b", Object.class);
		Id<?> c = Id.create("c", Object.class);
		Map<Id<?>, Object> players = new LinkedHashMap<>();
		players.put(c, new Object());
		players.put(a, new Object());
		players.put(b, new Object());

		assertEquals(8, AllocationUtils.generateSubsets(players).size());
		assertTrue(AllocationUtils.generateSubsets(players).containsKey(Set.of(a, b, c)));
		assertThrows(IllegalArgumentException.class, () -> AllocationUtils.generateSubsets(players, 2));
		assertThrows(IllegalArgumentException.class, () -> AllocationUtils.generateSubsets(players, -1));
		assertEquals(Set.of(c), AllocationUtils.identifyNonCollaboratingMembers(Set.of(a, b, c), Set.of(a, b)));
	}

	@Test
	void allocationFactoryDispatchesEverySupportedModel() {
		var store = FreightCollaborationTestFixtures.emptyDataStore();
		assertInstanceOf(AllocationModelProportional.class,
			AllocationUtils.createAllocationModel(AllocationModels.PROPORTIONAL, store, null, List.of(),
				1, null, 1));
		assertInstanceOf(AllocationModelShapleyValue.class,
			AllocationUtils.createAllocationModel(AllocationModels.SHAPLEY, store, null, List.of(),
				1, null, 1));
		assertInstanceOf(AllocationModelMarginalContribution.class,
			AllocationUtils.createAllocationModel(AllocationModels.MARGINAL, store, null, List.of(),
				1, null, 1));
		assertInstanceOf(AllocationModelApproxShapleyValue.class,
			AllocationUtils.createAllocationModel(AllocationModels.APPROX_SHAPLEY, store,
				() -> null, List.of(), 1, null, 1));
		assertThrows(NullPointerException.class,
			() -> AllocationUtils.createAllocationModel(null, store, null, List.of(), 1, null, 1));
	}

	@Test
	void extractsRoleAwarePlayersAndExactlyOneDistributor() {
		MutableFreightCoalition carrierReceiver =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		assertEquals(Set.of(Id.create("receiver", Receiver.class)),
			AllocationUtils.extractValidPlayers(carrierReceiver).keySet());
		assertEquals(CollaboratorRole.RECEIVER, AllocationUtils.extractPlayerRole(carrierReceiver));
		assertEquals("carrier", AllocationUtils.extractSingleDistributor(carrierReceiver).getId().toString());

		MutableFreightCoalition noDistributor =
			new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		noDistributor.addCollaborator(FreightCollaborationTestFixtures.receiverCollaborator("receiver"));
		assertThrows(IllegalStateException.class, () -> AllocationUtils.extractSingleDistributor(noDistributor));
	}

	@Test
	void collaboratorCopiesIsolateCarrierReceiverAndLspState() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Carrier carrier = carrierWithVehicleAndPlan();
		carrier.getAttributes().putAttribute("marker", "original");
		FreightCollaborator<Carrier> carrierCollaborator =
			org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory.createCollaborator(carrier);
		carrierCollaborator.enableCollaboration();
		carrierCollaborator.setCollaborationPartners(Set.of(Id.create("partner", Object.class)));

		@SuppressWarnings("unchecked")
		FreightCollaborator<Carrier> carrierCopy = AllocationUtils.deepCopyCollaboratorsMap(
			Map.<Id<?>, FreightCollaborator<Carrier>>of(carrier.getId(), carrierCollaborator), scenario)
			.get(carrier.getId());
		assertNotSame(carrier, carrierCopy.getDelegate());
		assertNotSame(carrier.getCarrierCapabilities(), carrierCopy.getDelegate().getCarrierCapabilities());
		assertNotSame(carrier.getCarrierCapabilities().getCarrierVehicles(),
			carrierCopy.getDelegate().getCarrierCapabilities().getCarrierVehicles());
		assertNotSame(carrier.getSelectedPlan(), carrierCopy.getDelegate().getSelectedPlan());
		assertEquals(carrierCopy.getDelegate(), carrierCopy.getDelegate().getSelectedPlan().getCarrier());
		carrierCopy.getDelegate().getCarrierCapabilities().getCarrierVehicles().clear();
		assertEquals(1, carrier.getCarrierCapabilities().getCarrierVehicles().size());
		assertEquals(carrierCollaborator.getCollaborationPartners(), carrierCopy.getCollaborationPartners());

		FreightCollaborator<Receiver> receiver = FreightCollaborationTestFixtures.receiverCollaborator("receiver");
		FreightCollaborator<Receiver> receiverCopy = AllocationUtils.deepCopyCollaboratorsMap(
			Map.<Id<?>, FreightCollaborator<Receiver>>of(receiver.getId(), receiver), scenario).get(receiver.getId());
		assertNotSame(receiver.getDelegate(), receiverCopy.getDelegate());
		assertNotSame(receiver.getTypedSelectedPlan(), receiverCopy.getTypedSelectedPlan());

		FreightCollaborator<LSP> lsp = FreightCollaborationTestFixtures.lspCollaborator("lsp");
		FreightCollaborator<LSP> lspCopy = AllocationUtils.deepCopyCollaboratorsMap(
			Map.<Id<?>, FreightCollaborator<LSP>>of(lsp.getId(), lsp), scenario).get(lsp.getId());
		assertNotSame(lsp.getDelegate(), lspCopy.getDelegate());
		assertNotSame(lsp.getTypedSelectedPlan(), lspCopy.getTypedSelectedPlan());
		assertThrows(IllegalArgumentException.class,
			() -> AllocationUtils.deepCopyCollaboratorsMap(
				Map.<Id<?>, FreightCollaborator<LSP>>of(lsp.getId(), lsp)));
	}

	private static Carrier carrierWithVehicleAndPlan() {
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		VehicleType type = VehicleUtils.createVehicleType(Id.create("type", VehicleType.class));
		CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
			Id.createVehicleId("vehicle"), Id.createLinkId("depot"), type).build();
		carrier.setCarrierCapabilities(CarrierCapabilities.Builder.newInstance()
			.setFleetSize(CarrierCapabilities.FleetSize.FINITE)
			.addVehicle(vehicle)
			.build());
		CarrierPlan plan = new CarrierPlan(carrier, List.of());
		plan.setScore(-2.0);
		carrier.addPlan(plan);
		carrier.setSelectedPlan(plan);
		return carrier;
	}
}
