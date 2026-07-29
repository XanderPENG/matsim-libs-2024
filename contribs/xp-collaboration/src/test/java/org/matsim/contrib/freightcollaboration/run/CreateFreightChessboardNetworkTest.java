package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.algorithms.NetworkCleaner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateFreightChessboardNetworkTest {

	@Test
	void generatedGridHasExpectedTopologyIdsAndIsStronglyConnected() {
		int size = 20;
		Network network = CreateFreightChessboardNetwork.createNetwork(size);
		int nodeCount = network.getNodes().size();
		int linkCount = network.getLinks().size();

		assertEquals((size + 1) * (size + 1), nodeCount);
		assertEquals(2 * size * (size + 1), linkCount);
		assertTrue(network.getNodes().containsKey(Id.createNodeId("(0,0)")));
		assertTrue(network.getNodes().containsKey(Id.createNodeId("(20,20)")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("i(1,0)")));
		assertTrue(network.getLinks().containsKey(Id.createLinkId("j(0,1)R")));

		new NetworkCleaner().run(network);

		assertEquals(nodeCount, network.getNodes().size());
		assertEquals(linkCount, network.getLinks().size());
	}

	@Test
	void parserSupportsDefaultsNamedAndPositionalArguments() {
		CreateFreightChessboardNetwork.Options defaults =
			CreateFreightChessboardNetwork.parseOptions(new String[0]);
		assertEquals(CreateFreightChessboardNetwork.DEFAULT_GRID_SIZE, defaults.gridSize());
		assertEquals(CreateFreightChessboardNetwork.defaultNetworkPath(defaults.gridSize()), defaults.outputFile());

		CreateFreightChessboardNetwork.Options named =
			CreateFreightChessboardNetwork.parseOptions(new String[]{"--grid-size=7", "--output=target/grid.xml"});
		assertEquals(7, named.gridSize());
		assertEquals(Path.of("target/grid.xml"), named.outputFile());

		CreateFreightChessboardNetwork.Options positional =
			CreateFreightChessboardNetwork.parseOptions(new String[]{"5", "target/positional.xml"});
		assertEquals(5, positional.gridSize());
		assertEquals(Path.of("target/positional.xml"), positional.outputFile());
	}

	@Test
	void rejectsInvalidGridAndCliOptions() {
		assertThrows(IllegalArgumentException.class, () -> CreateFreightChessboardNetwork.createNetwork(0));
		assertThrows(IllegalArgumentException.class,
			() -> CreateFreightChessboardNetwork.parseOptions(new String[]{"--grid-size=nope"}));
		assertThrows(IllegalArgumentException.class,
			() -> CreateFreightChessboardNetwork.parseOptions(new String[]{"--unknown=true"}));
		assertThrows(IllegalArgumentException.class,
			() -> CreateFreightChessboardNetwork.parseOptions(new String[]{"1", "out.xml", "extra"}));
	}
}
