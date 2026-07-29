package org.matsim.contrib.freightcollaboration.run;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RunCarrierReceiverShapleyChessboardExampleTest {

	@Test
	void generateFullyRandomReceiversWithinAreaTest() {
		Network network = loadTestNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverCollabChessboardExample.generateFullyRandomReceiversWithinArea(network, 10, 491);
		assertEquals(10, candidateLinks.size());
		assertEquals(candidateLinks,
			RunCarrierReceiverCollabChessboardExample.generateFullyRandomReceiversWithinArea(network, 10, 491));
		for (Id<Link> link : candidateLinks) {
			assertTrue(isWithinArea(midpoint(network.getLinks().get(link))), "Link " + link + " is outside the area");
		}
	}

	@Test
	void generateClusteredReceiversWithinAreaTest() {
		Network network = loadTestNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverCollabChessboardExample.generateClusteredReceiversWithinArea(network, 10, 251);
		assertEquals(10, candidateLinks.size());
		assertEquals(candidateLinks,
			RunCarrierReceiverCollabChessboardExample.generateClusteredReceiversWithinArea(network, 10, 251));
		/* Validate that each link should be within a certain 3000 * 3000 area, meaning:
		 * for any two links, the x and y difference of their from/to nodes should be <= 3000
		 */
		for (Id<Link> link1 : candidateLinks) {
			Coord midpoint1 = midpoint(network.getLinks().get(link1));
			for (Id<Link> link2 : candidateLinks) {
				Coord midpoint2 = midpoint(network.getLinks().get(link2));
				assertTrue(Math.abs(midpoint1.getX() - midpoint2.getX()) <= 3000);
				assertTrue(Math.abs(midpoint1.getY() - midpoint2.getY()) <= 3000);
			}
		}
	}

	@Test
	void generateHierarchyDispersedReceiversWithinAreaTest() {
		Network network = loadTestNetwork();
		Set<Id<Link>> candidateLinks = RunCarrierReceiverCollabChessboardExample.generateHierarchyDispersedReceiversWithinArea(network, 10,
			13);
		assertEquals(10, candidateLinks.size());
		assertEquals(candidateLinks,
			RunCarrierReceiverCollabChessboardExample.generateHierarchyDispersedReceiversWithinArea(network, 10, 13));
		for (Id<Link> link : candidateLinks) {
			assertTrue(isWithinArea(midpoint(network.getLinks().get(link))), "Link " + link + " is outside the area");
		}
	}

	Network loadTestNetwork() {
		return CreateFreightChessboardNetwork.createNetwork(9);
	}

	boolean isWithinArea(Coord coord) {
		double x = coord.getX();
		double y = coord.getY();

		return x >= 2000 && x <= 7000
			&& y >= 2000 && y <= 7000;
	}

	private Coord midpoint(Link link) {
		Coord from = link.getFromNode().getCoord();
		Coord to = link.getToNode().getCoord();
		return new Coord((from.getX() + to.getX()) / 2, (from.getY() + to.getY()) / 2);
	}
}
