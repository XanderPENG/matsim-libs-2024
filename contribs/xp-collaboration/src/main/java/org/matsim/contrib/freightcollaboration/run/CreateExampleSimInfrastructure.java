package org.matsim.contrib.freightcollaboration.run;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.net.URL;

public class CreateExampleSimInfrastructure {

	public static Network createExampleNetwork(boolean writeNetwork, String outputDir) {
		// Create an example octagonal MATSim network, with 8 nodes and one central node, each node connected as a ring and connected to the central node.
		Network network = NetworkUtils.createNetwork();

		// Create central node
		Node centralNode = NetworkUtils.createAndAddNode(network, Id.createNodeId("center"), new Coord(0.0, 0.0));

		// Parameters for octagon
		int numNodes = 8;
		double radius = 1000.0; // meters from center
		double freespeed = 13.89; // 50 km/h in m/s
		double capacity = 1000.0; // vehicles per hour
		int numLanes = 1;

		// Create octagonal nodes and store them
		Node[] octagonNodes = new Node[numNodes];
		for (int i = 0; i < numNodes; i++) {
			double angle = 2 * Math.PI * i / numNodes;
			double x = radius * Math.cos(angle);
			double y = radius * Math.sin(angle);
			octagonNodes[i] = NetworkUtils.createAndAddNode(network,
				Id.createNodeId("node_" + i),
				new Coord(x, y));
		}

		// Create ring connections (each node to the next)
		for (int i = 0; i < numNodes; i++) {
			int nextIndex = (i + 1) % numNodes;

			// Clockwise link
			Link link1 = NetworkUtils.createAndAddLink(network,
				Id.createLinkId(i + "_to_" + nextIndex),
				octagonNodes[i],
				octagonNodes[nextIndex],
				NetworkUtils.getEuclideanDistance(octagonNodes[i].getCoord(), octagonNodes[nextIndex].getCoord()),
				freespeed,
				capacity,
				numLanes);

			// Counter-clockwise link
			Link link2 = NetworkUtils.createAndAddLink(network,
				Id.createLinkId(nextIndex + "_to_" + i),
				octagonNodes[nextIndex],
				octagonNodes[i],
				NetworkUtils.getEuclideanDistance(octagonNodes[i].getCoord(), octagonNodes[nextIndex].getCoord()),
				freespeed,
				capacity,
				numLanes);
		}

		// Create radial connections (each octagon node to center and back)
		for (int i = 0; i < numNodes; i++) {
			// Link from octagon node to center
			Link linkToCenter = NetworkUtils.createAndAddLink(network,
				Id.createLinkId(i + "_to_center"),
				octagonNodes[i],
				centralNode,
				radius,
				freespeed,
				capacity,
				numLanes);

			// Link from center to octagon node
			Link linkFromCenter = NetworkUtils.createAndAddLink(network,
				Id.createLinkId("center_to_" + i),
				centralNode,
				octagonNodes[i],
				radius,
				freespeed,
				capacity,
				numLanes);
		}

		if (writeNetwork) {
			try {
				NetworkUtils.writeNetwork(network, outputDir + "/example_octagonal_network.xml");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return network;
	}

}
