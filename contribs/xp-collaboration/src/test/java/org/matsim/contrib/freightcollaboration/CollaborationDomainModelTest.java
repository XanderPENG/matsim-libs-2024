package org.matsim.contrib.freightcollaboration;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.freight.carriers.Carrier;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationDomainModelTest {

	@Test
	void collaborationTypesExposeSymmetricCompatibilityAndImmutableRoles() {
		for (CollaborationTypes type : CollaborationTypes.values()) {
			for (CollaboratorRole first : CollaboratorRole.values()) {
				for (CollaboratorRole second : CollaboratorRole.values()) {
					assertEquals(type.isCompatible(first, second), type.isCompatible(second, first), type.name());
				}
			}
			assertThrows(UnsupportedOperationException.class,
				() -> type.getAllowedRoles().add(CollaboratorRole.CARRIER));
			assertFalse(type.getType().isBlank());
			assertFalse(type.getDescription().isBlank());
		}
		assertEquals(EnumSet.of(CollaboratorRole.CARRIER, CollaboratorRole.RECEIVER),
			CollaborationTypes.CARRIER_RECEIVER.getAllowedRoles());
		assertEquals(EnumSet.allOf(CollaboratorRole.class), CollaborationTypes.MIXED.getAllowedRoles());
	}

	@Test
	void factoryCreatesTypedCollaboratorsAndRejectsNull() {
		var carrier = FreightCollaborationTestFixtures.carrier("carrier");
		var receiver = FreightCollaborationTestFixtures.receiver("receiver");
		var lsp = FreightCollaborationTestFixtures.lsp("lsp");

		FreightCollaborator<?> carrierCollaborator = FreightCollaboratorFactory.createCollaborator(carrier);
		FreightCollaborator<?> receiverCollaborator = FreightCollaboratorFactory.createCollaborator(receiver);
		FreightCollaborator<?> lspCollaborator = FreightCollaboratorFactory.createCollaborator(lsp);

		assertAll(
			() -> assertEquals(CollaboratorRole.CARRIER, carrierCollaborator.getRole()),
			() -> assertSame(carrier.getSelectedPlan(), carrierCollaborator.getTypedSelectedPlan()),
			() -> assertEquals(CollaboratorRole.RECEIVER, receiverCollaborator.getRole()),
			() -> assertSame(receiver.getSelectedPlan(), receiverCollaborator.getTypedSelectedPlan()),
			() -> assertEquals(CollaboratorRole.LSP, lspCollaborator.getRole()),
			() -> assertSame(lsp.getSelectedPlan(), lspCollaborator.getTypedSelectedPlan()),
			() -> assertThrows(IllegalArgumentException.class,
				() -> FreightCollaboratorFactory.createCollaborator(null))
		);
	}

	@Test
	void collaboratorDefensivelyCopiesRelationshipMetadata() {
		FreightCollaborator<Carrier> collaborator =
			FreightCollaborationTestFixtures.carrierCollaborator("carrier");
		Id<?> partner = Id.create("receiver", Object.class);
		var mutablePartners = new java.util.HashSet<Id<?>>();
		mutablePartners.add(partner);
		collaborator.setCollaborationPartners(mutablePartners);
		mutablePartners.clear();

		Map<CollaboratorRole, Set<Id<?>>> stakeholders =
			new java.util.EnumMap<>(CollaboratorRole.class);
		stakeholders.put(CollaboratorRole.RECEIVER, new java.util.HashSet<>(Set.of(partner)));
		collaborator.addOriginalConnectedStakeholders(stakeholders);
		stakeholders.get(CollaboratorRole.RECEIVER).clear();

		assertEquals(Set.of(partner), collaborator.getCollaborationPartners());
		assertEquals(Set.of(partner),
			collaborator.getOriginalConnectedStakeholders().get(CollaboratorRole.RECEIVER));
		assertThrows(UnsupportedOperationException.class,
			() -> collaborator.getCollaborationPartners().clear());
		assertThrows(UnsupportedOperationException.class,
			() -> collaborator.getOriginalConnectedStakeholders().clear());
		assertTrue(collaborator.getCollaborationStatus());
		collaborator.disableCollaboration();
		assertFalse(collaborator.getCollaborationStatus());
		collaborator.enableCollaboration();
		assertTrue(collaborator.getCollaborationStatus());
	}

	@Test
	void registryKeepsSameTextualIdInDifferentRoles() {
		var carrier = new FreightCollaboratorImpl<>(
			FreightCollaborationTestFixtures.carrier("same"), CollaboratorRole.CARRIER);
		var receiverRole = new FreightCollaboratorImpl<>(
			FreightCollaborationTestFixtures.carrier("same"), CollaboratorRole.RECEIVER);
		FreightCollaborators registry = new FreightCollaborators(Set.of(carrier, receiverRole));

		assertEquals(2, registry.getFreightCollaboratorsByKey().size());
		assertSame(carrier, registry.getFreightCollaborator(CollaboratorRole.CARRIER, carrier.getId()));
		assertSame(receiverRole, registry.getFreightCollaborator(CollaboratorRole.RECEIVER, receiverRole.getId()));
		assertThrows(IllegalStateException.class, registry::getFreightCollaborators);
		assertThrows(IllegalStateException.class, () -> registry.removeFreightCollaboratorById(carrier.getId()));

		registry.removeFreightCollaborator(CollaboratorRole.RECEIVER, receiverRole.getId());
		assertEquals(1, registry.getFreightCollaboratorsByKey().size());
		assertThrows(UnsupportedOperationException.class,
			() -> registry.getFreightCollaboratorsByKey().clear());
	}

	@Test
	void registryCoversDuplicateMissingBulkAndNullOperations() {
		var first = FreightCollaborationTestFixtures.carrierCollaborator("first");
		var second = FreightCollaborationTestFixtures.receiverCollaborator("second");
		FreightCollaborators registry = new FreightCollaborators();

		assertAll(
			() -> assertThrows(NullPointerException.class,
				() -> new FreightCollaborators(null)),
			() -> assertThrows(NullPointerException.class,
				() -> registry.addFreightCollaborator(null)),
			() -> assertThrows(NullPointerException.class,
				() -> registry.addFreightCollaboratorsFromCollection(null))
		);
		registry.addFreightCollaborator(first);
		registry.addFreightCollaborator(first);
		registry.addFreightCollaboratorsFromCollection(Set.of(second));
		assertEquals(2, registry.getFreightCollaborators().size());
		assertEquals(Map.of(first.getId(), first),
			registry.getFreightCollaboratorsByRole(CollaboratorRole.CARRIER));

		registry.removeFreightCollaborator(CollaboratorRole.LSP, Id.create("missing", Object.class));
		registry.removeFreightCollaboratorById(Id.create("missing", Object.class));
		registry.removeFreightCollaboratorById(first.getId());
		assertNull(registry.getFreightCollaborator(CollaboratorRole.CARRIER, first.getId()));
		assertEquals(Map.of(second.getId(), second), registry.getFreightCollaborators());
		assertThrows(NullPointerException.class, () -> registry.removeFreightCollaboratorById(null));
	}

	@Test
	void immutableAndMutableCoalitionsHonorRoleAwareIdentity() {
		var carrier = new FreightCollaboratorImpl<>(
			FreightCollaborationTestFixtures.carrier("same"), CollaboratorRole.CARRIER);
		var receiverRole = new FreightCollaboratorImpl<>(
			FreightCollaborationTestFixtures.carrier("same"), CollaboratorRole.RECEIVER);

		GrandFreightCoalition grand = new GrandFreightCoalition(Set.of(carrier, receiverRole));
		assertEquals(2, grand.size());
		assertTrue(grand.contains(CollaboratorRole.CARRIER, carrier.getId()));
		assertTrue(grand.contains(CollaboratorRole.RECEIVER, receiverRole.getId()));
		assertThrows(IllegalStateException.class, () -> grand.contains(carrier.getId()));
		assertThrows(IllegalStateException.class, grand::getCollaboratorsMap);
		assertThrows(UnsupportedOperationException.class, () -> grand.getCollaboratorsByKey().clear());
		assertThrows(IllegalArgumentException.class,
			() -> new GrandFreightCoalition(java.util.List.of(carrier, carrier)));

		MutableFreightCoalition mutable =
			new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		mutable.addCollaborators(Set.of(carrier, receiverRole));
		assertEquals(2, mutable.size());
		assertEquals(Set.of(CollaboratorRole.CARRIER, CollaboratorRole.RECEIVER), mutable.getRoles());
		assertThrows(IllegalStateException.class, () -> mutable.getCollaborator(carrier.getId()));
		mutable.removeCollaborator(CollaboratorRole.RECEIVER, receiverRole.getId());
		assertEquals(1, mutable.size());
		mutable.updateCollaborators(Set.of(receiverRole));
		assertEquals(Set.of(receiverRole), mutable.getCollaboratorsSet());
		assertThrows(UnsupportedOperationException.class, () -> mutable.getCollaboratorsByKey().clear());
	}

	@Test
	void coalitionsCoverEmptyRoleFiltersAttributesAndAllMutationOperations() {
		var carrier = FreightCollaborationTestFixtures.carrierCollaborator("carrier");
		var receiver = FreightCollaborationTestFixtures.receiverCollaborator("receiver");

		assertAll(
			() -> assertThrows(IllegalArgumentException.class, () -> new GrandFreightCoalition(null)),
			() -> assertThrows(IllegalArgumentException.class,
				() -> new GrandFreightCoalition(java.util.Arrays.asList(carrier, null))),
			() -> assertThrows(NullPointerException.class,
				() -> new MutableFreightCoalition(null))
		);

		GrandFreightCoalition grand = new GrandFreightCoalition(Set.of(carrier, receiver));
		assertAll(
			() -> assertFalse(grand.isEmpty()),
			() -> assertTrue(grand.contains(carrier)),
			() -> assertFalse(grand.contains(FreightCollaborationTestFixtures.lspCollaborator("missing"))),
			() -> assertSame(carrier, grand.getCollaborator(carrier.getId())),
			() -> assertNull(grand.getCollaborator(Id.create("missing", Object.class))),
			() -> assertEquals(Set.of(carrier), grand.getCollaboratorsSetByRole(CollaboratorRole.CARRIER)),
			() -> assertEquals(Map.of(carrier.getId(), carrier),
				grand.getCollaboratorsMapByRole(CollaboratorRole.CARRIER))
		);
		grand.getAttributes().putAttribute("name", "grand");
		assertEquals("grand", grand.getAttributes().getAttribute("name"));

		MutableFreightCoalition mutable =
			new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		assertTrue(mutable.isEmpty());
		mutable.addCollaborator(carrier);
		mutable.addCollaborator(carrier);
		mutable.addCollaborator(receiver);
		assertAll(
			() -> assertTrue(mutable.contains(carrier)),
			() -> assertSame(receiver, mutable.getCollaborator(receiver.getId())),
			() -> assertEquals(Map.of(receiver.getId(), receiver),
				mutable.getCollaboratorsMapByRole(CollaboratorRole.RECEIVER)),
			() -> assertEquals(Set.of(receiver),
				mutable.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)),
			() -> assertThrows(NullPointerException.class, () -> mutable.addCollaborator(null))
		);
		mutable.removeCollaborators(Set.of(receiver));
		mutable.removeCollaborator(Id.create("missing", Object.class));
		mutable.removeCollaborator(carrier.getId());
		assertTrue(mutable.isEmpty());
		mutable.addCollaborators(Set.of(receiver));
		mutable.updateCollaborationType(CollaborationTypes.MIXED);
		assertEquals(CollaborationTypes.MIXED, mutable.getCollaborationType());
		assertThrows(NullPointerException.class, () -> mutable.updateCollaborationType(null));
		mutable.getAttributes().putAttribute("name", "mutable");
		assertEquals("mutable", mutable.getAttributes().getAttribute("name"));
	}
}
