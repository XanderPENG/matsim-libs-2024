package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;
import org.matsim.freight.receiver.ReceiversWriter;
import org.xml.sax.InputSource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReceiverOutputLifecycleIT {

	@Test
	void rootPreMobsimSnapshotCanDifferFromIterationEndScore(@TempDir Path output) throws Exception {
		Receiver receiver = FreightCollaborationTestFixtures.receiver("receiver");
		Receivers receivers = ReceiverUtils.createReceivers();
		receivers.addReceiver(receiver);
		Path rootSnapshot = output.resolve("receivers.xml.gz");
		Path iterationDir = output.resolve("ITERS/it.30");
		Files.createDirectories(iterationDir);
		Path iterationEnd = iterationDir.resolve("30.receivers.xml");

		receiver.getSelectedPlan().setScore(1.0);
		new ReceiversWriter(receivers).write(rootSnapshot.toString());
		receiver.getSelectedPlan().setScore(7.5);
		new ReceiversWriter(receivers).write(iterationEnd.toString());

		double rootScore = selectedPlanScore(parseGzip(rootSnapshot));
		double finalIterationScore = selectedPlanScore(parse(iterationEnd));
		assertEquals(1.0, rootScore);
		assertEquals(7.5, finalIterationScore);
		assertNotEquals(rootScore, finalIterationScore,
			"The root file is a pre-Mobsim snapshot; the last iteration file contains the post-scoring state");
	}

	private static double selectedPlanScore(Document document) {
		return Double.parseDouble(document.getElementsByTagName("plan").item(0)
			.getAttributes().getNamedItem("score").getNodeValue());
	}

	private static Document parse(Path file) throws Exception {
		try (InputStream input = Files.newInputStream(file)) {
			return offlineDocumentBuilder().parse(input);
		}
	}

	private static Document parseGzip(Path file) throws Exception {
		try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
			return offlineDocumentBuilder().parse(input);
		}
	}

	private static DocumentBuilder offlineDocumentBuilder() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		DocumentBuilder builder = factory.newDocumentBuilder();
		builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
		return builder;
	}
}
