package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationDataStoreValueWriterTest {

	@Test
	void writesEmptyStoreAsParseableXml(@TempDir Path tempDir) throws Exception {
		Path output = tempDir.resolve("empty.xml");
		new CollaborationDataStoreValueWriter(FreightCollaborationTestFixtures.emptyDataStore(), 4)
			.write(output.toString());

		Document document = parse(output);
		assertEquals("collaborationData", document.getDocumentElement().getTagName());
		assertEquals("4", document.getDocumentElement().getAttribute("iteration"));
		assertEquals(0, document.getElementsByTagName("allocation").getLength());
		assertEquals(0, document.getElementsByTagName("coalition").getLength());
	}

	@Test
	void writesRoleAwareEscapedStableAndGzippedData(@TempDir Path tempDir) throws Exception {
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier&\"<", "receiver&\"<");
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		Map<Set<Id<?>>, Double> scores = new LinkedHashMap<>();
		scores.put(Set.of(coalition.getCollaboratorsSet().stream()
			.filter(c -> c.getRole() == CollaboratorRole.RECEIVER).findFirst().orElseThrow().getId()), -3.0);
		scores.put(Set.of(), 2.0);
		store.addSimulatedCoalitionScores(coalition, scores);
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.RECEIVER, Id.create("same&\"<", Object.class)), -1.25,
			new CollaboratorKey(CollaboratorRole.CARRIER, Id.create("same&\"<", Object.class)), 4.25));

		Path xml = tempDir.resolve("collaboration.xml");
		Path second = tempDir.resolve("collaboration-second.xml");
		Path gzip = tempDir.resolve("collaboration.xml.gz");
		new CollaborationDataStoreValueWriter(store, 7).write(xml.toString());
		new CollaborationDataStoreValueWriter(store, 7).write(second.toString());
		new CollaborationDataStoreValueWriter(store, 7).write(gzip.toString());

		assertEquals(Files.readString(xml), Files.readString(second), "Writer output must be deterministic");
		Document document = parse(xml);
		assertEquals(2, document.getElementsByTagName("allocation").getLength());
		assertEquals("CARRIER",
			document.getElementsByTagName("allocation").item(0).getAttributes().getNamedItem("role").getNodeValue());
		assertEquals("same&\"<", document.getElementsByTagName("allocation").item(0).getAttributes()
			.getNamedItem("collaboratorId").getNodeValue());
		assertEquals(2, document.getElementsByTagName("member").getLength());
		assertNotNull(parseGzip(gzip));
		assertTrue(Files.size(gzip) > 0);
	}

	@Test
	void rejectsNullsAndSurfacesWriteFailures(@TempDir Path tempDir) {
		assertThrows(NullPointerException.class, () -> new CollaborationDataStoreValueWriter(null, 0));
		CollaborationDataStoreValueWriter writer =
			new CollaborationDataStoreValueWriter(FreightCollaborationTestFixtures.emptyDataStore(), 0);
		assertThrows(NullPointerException.class, () -> writer.write(null));
		assertThrows(RuntimeException.class,
			() -> writer.write(tempDir.resolve("missing/parent/out.xml").toString()));
	}

	@Test
	void specializedWritersHandleFullAndEmptyStoresAndSurfaceFailures(
			@TempDir Path tempDir) throws Exception {
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "r&<");
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		Id<?> receiver = coalition.getCollaboratorsSet().stream()
			.filter(c -> c.getRole() == CollaboratorRole.RECEIVER)
			.findFirst().orElseThrow().getId();
		store.addSimulatedCoalitionScores(coalition, Map.of(
			Set.of(), 1.0, Set.of(receiver), 3.0));
		store.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.RECEIVER,
				Id.create("r&<", Object.class)), 2.0));
		Path coalitionFile = tempDir.resolve("coalitions.xml");
		Path allocationFile = tempDir.resolve("allocations.xml");

		CollaborationDataStoreValueWriter.writeCoalitionScores(
			store, coalitionFile.toString(), 2);
		CollaborationDataStoreValueWriter.writeAllocatedValues(
			store, allocationFile.toString(), 2);
		assertEquals(1, parse(coalitionFile).getElementsByTagName("coalition").getLength());
		assertEquals("r&<", parse(allocationFile).getElementsByTagName("allocation")
			.item(0).getAttributes().getNamedItem("collaboratorId").getNodeValue());

		CollaborationDataStore empty = FreightCollaborationTestFixtures.emptyDataStore();
		Path emptyCoalitions = tempDir.resolve("empty-coalitions.xml");
		Path emptyAllocations = tempDir.resolve("empty-allocations.xml");
		CollaborationDataStoreValueWriter.writeCoalitionScores(
			empty, emptyCoalitions.toString(), 3);
		CollaborationDataStoreValueWriter.writeAllocatedValues(
			empty, emptyAllocations.toString(), 3);
		assertEquals(0, parse(emptyCoalitions).getElementsByTagName("coalition").getLength());
		assertEquals(0, parse(emptyAllocations).getElementsByTagName("allocation").getLength());

		assertThrows(RuntimeException.class,
			() -> CollaborationDataStoreValueWriter.writeCoalitionScores(store,
				tempDir.resolve("missing-a/out.xml").toString(), 1));
		assertThrows(RuntimeException.class,
			() -> CollaborationDataStoreValueWriter.writeAllocatedValues(store,
				tempDir.resolve("missing-b/out.xml").toString(), 1));
	}

	private static Document parse(Path file) throws Exception {
		try (InputStream input = Files.newInputStream(file)) {
			return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
		}
	}

	private static Document parseGzip(Path file) throws Exception {
		try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
			return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
		}
	}
}
