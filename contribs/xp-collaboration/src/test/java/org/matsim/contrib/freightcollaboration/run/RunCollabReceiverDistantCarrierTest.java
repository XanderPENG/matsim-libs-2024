package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.freight.receiver.Receivers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCollabReceiverDistantCarrierTest {

	@Test
	void receiverGeneratorsAreDeterministicUniqueAndStayInsideConfiguredArea() {
		int networkSize = 20;
		Network network = CreateFreightChessboardNetwork.createNetwork(networkSize);

		for (RunCollabReceiverDistantCarrier.ReceiverAreaPolicy policy
			: RunCollabReceiverDistantCarrier.ReceiverAreaPolicy.values()) {
			for (RunCollabReceiverDistantCarrier.CustomerDistributionScenario distribution
				: RunCollabReceiverDistantCarrier.CustomerDistributionScenario.values()) {
				Set<Id<Link>> first = receiverLinks(network, distribution, policy, networkSize, 9);
				Set<Id<Link>> second = receiverLinks(network, distribution, policy, networkSize, 9);

				assertEquals(10, first.size(), distribution + " / " + policy);
				assertEquals(first, second, distribution + " / " + policy);
				for (Id<Link> linkId : first) {
					assertWithinConfiguredArea(network.getLinks().get(linkId), networkSize, policy);
				}
			}
		}
	}

	@Test
	void generatedReceiversHaveStableUniqueIds() {
		Network network = CreateFreightChessboardNetwork.createNetwork(20);
		Receivers receivers = RunCollabReceiverDistantCarrier.generateReceivers(network, 10,
			RunCollabReceiverDistantCarrier.CustomerDistributionScenario.FULLY_RANDOM, 3, 20,
			RunCollabReceiverDistantCarrier.ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA);

		assertEquals(10, receivers.getReceivers().size());
		for (int i = 0; i < 10; i++) {
			assertTrue(receivers.getReceivers().containsKey(
				Id.create("receiver_%02d".formatted(i), org.matsim.freight.receiver.Receiver.class)));
		}
	}

	@Test
	void parserAppliesOverridesAndRejectsInvalidInput(@TempDir Path tempDir) {
		RunCollabReceiverDistantCarrier.ExperimentOptions defaults =
			new RunCollabReceiverDistantCarrier.ExperimentOptions(20, 10, 3, null, tempDir,
				RunCollabReceiverDistantCarrier.ReceiverAreaPolicy.SCALED_TO_NETWORK, true);

		RunCollabReceiverDistantCarrier.ExperimentOptions parsed =
			RunCollabReceiverDistantCarrier.parseOptions(new String[]{
				"--network-size=25",
				"--carrier-scenarios=4",
				"--instances=2",
				"--receiver-area=centered",
				"--reuse-network-file"
			}, defaults);

		assertEquals(25, parsed.networkSize());
		assertEquals(4, parsed.carrierScenarioCount());
		assertEquals(2, parsed.instanceCount());
		assertEquals(CreateFreightChessboardNetwork.defaultNetworkPath(25), parsed.networkFile());
		assertEquals(RunCollabReceiverDistantCarrier.ReceiverAreaPolicy.CENTERED_CHESSBOARD_EXAMPLE_AREA,
			parsed.receiverAreaPolicy());
		assertFalse(parsed.regenerateNetworkFile());

		assertThrows(IllegalArgumentException.class,
			() -> RunCollabReceiverDistantCarrier.parseOptions(new String[]{"--instances=0"}, defaults));
		assertThrows(IllegalArgumentException.class,
			() -> RunCollabReceiverDistantCarrier.parseOptions(new String[]{"--receiver-area=outside"}, defaults));
		assertThrows(IllegalArgumentException.class,
			() -> RunCollabReceiverDistantCarrier.parseOptions(new String[]{"--mystery=1"}, defaults));
	}

	@Test
	void experimentIsSkippedOnlyAfterExplicitCompletionMarker(@TempDir Path tempDir) throws IOException {
		assertFalse(RunCollabReceiverDistantCarrier.isExperimentComplete(tempDir));
		Files.createFile(tempDir.resolve("output_config.xml"));
		Files.createDirectories(tempDir.resolve("ITERS/it.30"));
		Files.createFile(tempDir.resolve("ITERS/it.30/30.receivers.xml"));
		assertFalse(RunCollabReceiverDistantCarrier.isExperimentComplete(tempDir),
			"An existing or partly written output directory must be rerun");

		Files.createFile(tempDir.resolve(RunCollabReceiverDistantCarrier.EXPERIMENT_COMPLETE_MARKER));
		assertTrue(RunCollabReceiverDistantCarrier.isExperimentComplete(tempDir));
	}

	@Test
	void dispersedGeneratorRequiresTheDefinedHierarchySize() {
		Network network = CreateFreightChessboardNetwork.createNetwork(20);
		assertThrows(IllegalStateException.class,
			() -> RunCollabReceiverDistantCarrier.generateHierarchyDispersedReceiversWithinArea(
				network, 9, 0, 20));
	}

	private static Set<Id<Link>> receiverLinks(Network network,
											  RunCollabReceiverDistantCarrier.CustomerDistributionScenario distribution,
											  RunCollabReceiverDistantCarrier.ReceiverAreaPolicy policy,
											  int networkSize, int seed) {
		return switch (distribution) {
			case FULLY_RANDOM -> RunCollabReceiverDistantCarrier.generateFullyRandomReceiversWithinArea(
				network, 10, seed, networkSize, policy);
			case CLUSTERED -> RunCollabReceiverDistantCarrier.generateClusteredReceiversWithinArea(
				network, 10, seed, networkSize, policy);
			case DISPERSED -> RunCollabReceiverDistantCarrier.generateHierarchyDispersedReceiversWithinArea(
				network, 10, seed, networkSize, policy);
		};
	}

	private static void assertWithinConfiguredArea(Link link, int networkSize,
													RunCollabReceiverDistantCarrier.ReceiverAreaPolicy policy) {
		double extent = networkSize * CreateFreightChessboardNetwork.LINK_LENGTH;
		double min;
		double max;
		switch (policy) {
			case SCALED_TO_NETWORK -> {
				min = extent * 2.0 / 9.0;
				max = extent * 7.0 / 9.0;
			}
			case CHESSBOARD_EXAMPLE_AREA -> {
				min = 2000.0;
				max = 7000.0;
			}
			case CENTERED_CHESSBOARD_EXAMPLE_AREA -> {
				min = (extent - 5000.0) / 2.0;
				max = min + 5000.0;
			}
			default -> throw new IllegalStateException("Unexpected policy " + policy);
		}

		Coord from = link.getFromNode().getCoord();
		Coord to = link.getToNode().getCoord();
		double x = (from.getX() + to.getX()) / 2.0;
		double y = (from.getY() + to.getY()) / 2.0;
		assertTrue(x >= min - 1e-6 && x <= max + 1e-6, link.getId() + " x=" + x);
		assertTrue(y >= min - 1e-6 && y <= max + 1e-6, link.getId() + " y=" + y);
	}
}
