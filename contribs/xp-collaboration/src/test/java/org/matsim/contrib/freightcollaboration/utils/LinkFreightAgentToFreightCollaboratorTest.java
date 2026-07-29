package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.core.population.PopulationUtils;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.collaboration.CollaborationUtils;

import static org.junit.jupiter.api.Assertions.*;

class LinkFreightAgentToFreightCollaboratorTest {

	@Test
	void mapsEverySupportedAgentAndHonorsStatusSources() {
		Receiver defaultReceiver = FreightCollaborationTestFixtures.receiver("default");
		var defaultCollaborator =
			LinkFreightAgentToFreightCollaborator.map(defaultReceiver, false);
		assertTrue(defaultCollaborator.getCollaborationStatus());
		assertEquals(Boolean.TRUE, defaultReceiver.getAttributes()
			.getAttribute(CollaborationUtils.ATTR_COLLABORATION_STATUS));

		Receiver closedReceiver = FreightCollaborationTestFixtures.receiver("closed");
		closedReceiver.getAttributes().putAttribute(
			CollaborationUtils.ATTR_COLLABORATION_STATUS, false);
		assertFalse(LinkFreightAgentToFreightCollaborator.map(
			closedReceiver, true).getCollaborationStatus());
		closedReceiver.getAttributes().putAttribute(
			CollaborationUtils.ATTR_COLLABORATION_STATUS, true);
		assertTrue(LinkFreightAgentToFreightCollaborator.map(
			closedReceiver, false).getCollaborationStatus());

		assertTrue(LinkFreightAgentToFreightCollaborator.map(
			FreightCollaborationTestFixtures.carrier("carrier"), true)
			.getCollaborationStatus());
		assertFalse(LinkFreightAgentToFreightCollaborator.map(
			FreightCollaborationTestFixtures.carrier("carrier-off"), false)
			.getCollaborationStatus());
		assertTrue(LinkFreightAgentToFreightCollaborator.map(
			FreightCollaborationTestFixtures.lsp("lsp"), true)
			.getCollaborationStatus());
		assertFalse(LinkFreightAgentToFreightCollaborator.map(
			FreightCollaborationTestFixtures.lsp("lsp-off"), false)
			.getCollaborationStatus());
	}

	@Test
	void rejectsUnsupportedAgentTypes() {
		var person = PopulationUtils.getFactory().createPerson(Id.createPersonId("person"));
		assertThrows(IllegalArgumentException.class,
			() -> LinkFreightAgentToFreightCollaborator.map(person, true));
		assertThrows(NullPointerException.class,
			() -> LinkFreightAgentToFreightCollaborator.map(null, true));
	}
}
