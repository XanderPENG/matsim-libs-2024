package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;

import java.net.URL;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunCarrierReceiverShapleyChessboardExampleTest {

	@Test
	void generateFullyRandomReceiversWithinAreaTest() {
		Scenario scenario = loadTestScenario();
		Network network = scenario.getNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverShapleyChessboardExample.generateFullyRandomReceiversWithinArea(network, 10, 491);
		// validate that each link should be within the area
		for (Id<Link> link : candidateLinks) {
			System.out.println(link);
			Coord toCoord = network.getLinks().get(link).getToNode().getCoord();
			Coord fromCoord = network.getLinks().get(link).getFromNode().getCoord();
			assertTrue(isWithinArea(fromCoord), "Link " + link.toString() + " is outside the area: " + fromCoord.toString());
			assertTrue(isWithinArea(toCoord), "Link " + link.toString() + " is outside the area: " + toCoord.toString());
		}
	}

	@Test
	void generateClusteredReceiversWithinAreaTest() {
		Scenario scenario = loadTestScenario();
		Network network = scenario.getNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverShapleyChessboardExample.generateClusteredReceiversWithinArea(network, 10, 251);
		/* Validate that each link should be within a certain 3000 * 3000 area, meaning:
		 * for any two links, the x and y difference of their from/to nodes should be <= 3000
		 */
		for (Id<Link> link1 : candidateLinks) {
			System.out.println(link1);
			Coord toCoord1 = network.getLinks().get(link1).getToNode().getCoord();
			Coord fromCoord1 = network.getLinks().get(link1).getFromNode().getCoord();
			for (Id<Link> link2 : candidateLinks) {
				Coord toCoord2 = network.getLinks().get(link2).getToNode().getCoord();
				Coord fromCoord2 = network.getLinks().get(link2).getFromNode().getCoord();
				assertTrue(Math.abs(toCoord1.getX() - toCoord2.getX()) <= 3000, "Links " + link1.toString() + " and " + link2.toString() + " are too far apart in x direction: " + Math.abs(toCoord1.getX() - toCoord2.getX()));
				assertTrue(Math.abs(toCoord1.getY() - toCoord2.getY()) <= 3000, "Links " + link1.toString() + " and " + link2.toString() + " are too far apart in y direction: " + Math.abs(toCoord1.getY() - toCoord2.getY()));
				assertTrue(Math.abs(fromCoord1.getX() - fromCoord2.getX()) <= 3000, "Links " + link1.toString() + " and " + link2.toString() + " are too far apart in x direction: " + Math.abs(fromCoord1.getX() - fromCoord2.getX()));
				assertTrue(Math.abs(fromCoord1.getY() - fromCoord2.getY()) <= 3000, "Links " + link1.toString() + " and " + link2.toString() + " are too far apart in y direction: " + Math.abs(fromCoord1.getY() - fromCoord2.getY()));
			}
		}
	}

	@Test
	void generateHierarchyDispersedReceiversWithinAreaTest() {
		Scenario scenario = loadTestScenario();
		Network network = scenario.getNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverShapleyChessboardExample.generateHierarchyDispersedReceiversWithinArea(network, 10,
			13);
		// validate that each link should be within the area
		for (Id<Link> link : candidateLinks) {
			System.out.println(link);
			Coord toCoord = network.getLinks().get(link).getToNode().getCoord();
			Coord fromCoord = network.getLinks().get(link).getFromNode().getCoord();
			assertTrue(isWithinArea(fromCoord), "Link " + link.toString() + " is outside the area: " + fromCoord.toString());
			assertTrue(isWithinArea(toCoord), "Link " + link.toString() + " is outside the area: " + toCoord.toString());
		}
	}

	Scenario loadTestScenario() {
		URL context = ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9");
		Config config = ConfigUtils.createConfig();
		config.setContext(context);
		config.network().setInputFile("grid9x9.xml");

		Scenario scenario = ScenarioUtils.loadScenario(config);
		return scenario;
	}

	boolean isWithinArea(Coord coord) {
		double x = coord.getX();
		double y = coord.getY();

		return x >= 2000 && x <= 7000
			&& y >= 2000 && y <= 7000;
	}
}
