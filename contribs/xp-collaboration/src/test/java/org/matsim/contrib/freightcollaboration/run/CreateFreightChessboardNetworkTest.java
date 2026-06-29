package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.algorithms.NetworkCleaner;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateFreightChessboardNetworkTest {

	@Test
	void generatedEvenGridIsStronglyConnected() {
		Network network = CreateFreightChessboardNetwork.createNetwork(20);
		int nodeCount = network.getNodes().size();
		int linkCount = network.getLinks().size();

		new NetworkCleaner().run(network);

		assertEquals(nodeCount, network.getNodes().size());
		assertEquals(linkCount, network.getLinks().size());
	}
}
