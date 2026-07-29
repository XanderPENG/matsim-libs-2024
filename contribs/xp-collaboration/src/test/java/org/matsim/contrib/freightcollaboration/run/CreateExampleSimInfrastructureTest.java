package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.algorithms.NetworkCleaner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateExampleSimInfrastructureTest {
	@Test
	void createsExpectedStronglyConnectedOctagonalNetworkWithoutWorkspaceOutput() {
		Network network = CreateExampleSimInfrastructure.createExampleNetwork(false, null);
		assertEquals(9, network.getNodes().size());
		assertEquals(32, network.getLinks().size());
		assertTrue(network.getNodes().containsKey(Id.createNodeId("center")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("0_to_center")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("center_to_0")));

		new NetworkCleaner().run(network);
		assertEquals(9, network.getNodes().size());
		assertEquals(32, network.getLinks().size());
	}

	@Test
	void writesIntoTemporaryDirectoryAndRejectsMissingTarget(@TempDir Path tempDir) {
		Path nested = tempDir.resolve("nested/output");
		CreateExampleSimInfrastructure.createExampleNetwork(true, nested.toString());
		assertTrue(Files.isRegularFile(nested.resolve("example_octagonal_network.xml")));
		assertThrows(NullPointerException.class,
			() -> CreateExampleSimInfrastructure.createExampleNetwork(true, null));
	}
}
