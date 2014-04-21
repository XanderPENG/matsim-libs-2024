/* *********************************************************************** *
 * project: org.matsim.*
 * TollXMLCreator.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2013 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package playground.telaviv.roadpricing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.misc.Counter;
import org.matsim.roadpricing.RoadPricingSchemeImpl;
import org.matsim.roadpricing.RoadPricingWriterXMLv1;

import playground.telaviv.config.XMLParameterParser;

/**
 * <p>
 * This class creates an xml file containing information about the tolled links
 * in the MATSim network. 
 * </p>
 * <p>
 * Note that two networks are needed as input:<br>
 * - The network used for the simulation runs. Its structure has been altered to 
 * take turning restrictions into account.<br>
 * - A network containing the original structure. It contains all nodes and links
 * to which the input file with the toll information refers to.
 * </p>
 * <p>
 * The value for the charged toll has to be converted.<br>
 * By default, performing an activity in MATSim results in an 6 monetary units per
 * hour, i.e. 0.1 units per minute.<br>
 * MATSim expects its distance toll information to be provided in monetary units / m.<br>
 * The toll which was provided was 0.44 minutes/km which equals 0.44 * 0.1 = 0.044 
 * monetary units / km = 0.000044 monetary units / m.<br>
 * </p>
 * <p>
 * So far, tolls are charged all over the day. Tolls could also be limited to certain
 * periods.
 * </p>
 * @author cdobler
 */
public class TollXMLCreator {

	private static final Logger log = Logger.getLogger(TollXMLCreator.class);

	private static String basePath = "";
	private static String networkFile = "";
	private static String originalNetworkFile = "";
	private static String tolledLinksFile = "";
	private static String tollFile = "";
	
	private static double toll = 0.0;
	private static String separator = ",";
	
	public static void main(String[] args) {
		try {
			if (args.length > 0) {
				String file = args[0];
				Map<String, String> parameterMap = new XMLParameterParser().parseFile(file);
				String value;
				
				value = parameterMap.remove("basePath");
				if (value != null) basePath = value;

				value = parameterMap.remove("networkFile");
				if (value != null) networkFile = value;

				value = parameterMap.remove("originalNetworkFile");
				if (value != null) originalNetworkFile = value;

				value = parameterMap.remove("tolledLinksFile");
				if (value != null) tolledLinksFile = value;
				
				value = parameterMap.remove("tollFile");
				if (value != null) tollFile = value;
								
				value = parameterMap.remove("separator");
				if (value != null) separator = value;

				value = parameterMap.remove("toll");
				if (value != null) toll = Double.parseDouble(value);

				for (String key : parameterMap.keySet()) log.warn("Found parameter " + key + " which is not handled!");
			} else {
				log.error("No input config file was given. Therefore cannont proceed. Aborting!");
				return;
			}
			
			Config config = ConfigUtils.createConfig(); 
			config.network().setInputFile(basePath + networkFile);
			Scenario scenario = ScenarioUtils.loadScenario(config);
			Network network = scenario.getNetwork();
			
			Config originalConfig = ConfigUtils.createConfig(); 
			originalConfig.network().setInputFile(basePath + originalNetworkFile);
			Scenario originalScenario = ScenarioUtils.loadScenario(originalConfig);
			Network originalNetwork = originalScenario.getNetwork();
			
			List<Tuple<Id, Id>> tuples = new TolledLinksFileParser(separator).readFile(basePath + tolledLinksFile);
			
			QuadTree<Node> quadTree = buildNodesQuadTree(scenario.getNetwork());
			
			List<Link> tolledLinks = new ArrayList<Link>();
			Counter counter = new Counter("Tolled links mapped to MATSim network: #");
			for (Tuple<Id, Id> tuple : tuples) {
				Id fromNodeId = tuple.getFirst();
				Id toNodeId = tuple.getSecond();

				Node fromNode = network.getNodes().get(fromNodeId);
				Node toNode = network.getNodes().get(toNodeId);

				Link link = null;
				if (fromNode == null || toNode == null) {
					// try searching using original nodes
					link = searchUsingOriginalNodes(fromNodeId, toNodeId, network, originalNetwork, quadTree);
				} else link = searchLink(fromNode, toNode);

				if (link != null) {
					tolledLinks.add(link);
					counter.incCounter();
				} else log.warn("Link from node " + fromNodeId + " to node " + toNodeId + " could not be found!");
			}
			counter.printCounter();		
			
			RoadPricingSchemeImpl scheme = new RoadPricingSchemeImpl();
	    	scheme.setType("distance");
	    	scheme.setName("Tel Aviv");
	    	scheme.setDescription("Highway Number 6");

	    	for (Link link : tolledLinks) {
	    		scheme.addLink(link.getId());
	    	}
	    	
	    	/*
	    	 * From the package info file:
	    	 * 
	    	 * In the case of a distance toll, the amount agents have to pay for the toll is linear to the distance they
	    	 * travel in the tolled area. The roadpricing-type must be set to "distance" in the roadpricing file (see
	    	 * below), and all the links that should be tolled must be listed. The costs are per "link length unit": If an
	    	 * agents travels along a link with length set to "100" (which usually means 100 metres for us), the agent
	    	 * will have to pay 100 times the amount specified in the roadpricing file. The time the agent enters a link
	    	 * is the determining time to define the costs.
	    	 * 
	    	 * Add costs to scheme. According to package-info file this should be
	    	 * [monetary unit] / [link length unit].
	    	 * 
	    	 * According to the Tel Aviv input file, the costs are 0.44 * link length [min].
	    	 * Link length is defined in [km]!
	    	 * 
	    	 * Activity performing is by default 6 monetary units per hour, i.e. 0.1 units per min.
	    	 * 
	    	 * Toll is  0.44 min/km = 0.44 * 0.1 = 0.044 monetary units / km = 0.000044 monetary units / m. 
	    	 */
//	    	toll = 0.000044;
	    	scheme.addCost(0.0, 86400, toll);

	    	new RoadPricingWriterXMLv1(scheme).writeFile(basePath + tollFile);	
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private static Link searchUsingOriginalNodes(Id fromNodeId, Id toNodeId, Network network, Network originalNetwork, QuadTree<Node> quadTree) {
		
		Collection<Node> potentialFromNodes = new ArrayList<Node>();
		Collection<Node> potentialToNodes = new ArrayList<Node>();
		
		Node fromNode = network.getNodes().get(fromNodeId);
		Node toNode = network.getNodes().get(toNodeId);
		
		if (fromNode == null) {
			fromNode = originalNetwork.getNodes().get(fromNodeId);
			if (fromNode == null) {
				log.warn("fromNode is not contained in the original network!");
				return null;
			}
			Coord fromCoord = fromNode.getCoord();
			
			potentialFromNodes = quadTree.get(fromCoord.getX(), fromCoord.getY(), 10.0);			
			
		} else potentialFromNodes.add(fromNode);
		
		if (toNode == null) {
			toNode = originalNetwork.getNodes().get(toNodeId);
			if (toNode == null) {
				log.warn("toNode is not contained in the original network!");
				return null;
			}
			Coord toCoord = toNode.getCoord();
			
			potentialToNodes = quadTree.get(toCoord.getX(), toCoord.getY(), 10.0);
			
		} else potentialToNodes.add(toNode);
		
		for (Node potentialFromNode : potentialFromNodes) {
			if (!potentialFromNode.getId().toString().contains(fromNodeId.toString())) continue;
				
			for (Node potentialToNode : potentialToNodes) {
				if (!potentialToNode.getId().toString().contains(toNodeId.toString())) continue;
				
				for (Link potentialLink : potentialFromNode.getOutLinks().values()) {
					if (potentialLink.getToNode().equals(potentialToNode)) {
						return potentialLink;
					}
				}
			}
		}
		
		log.warn("Link from Node " + fromNodeId + " to Node " + toNodeId + " could not be found using original node data!");
		return null;
	}
	
	private static Link searchLink(Node fromNode, Node toNode) {
		for (Link link : fromNode.getOutLinks().values()) {
			if (link.getToNode().getId().equals(toNode.getId())) {
				return link;
			}
		}
		return null;
	}
	
	private static QuadTree<Node> buildNodesQuadTree(Network network) {

		double startTime = System.currentTimeMillis();
		
		double minx = Double.POSITIVE_INFINITY;
		double miny = Double.POSITIVE_INFINITY;
		double maxx = Double.NEGATIVE_INFINITY;
		double maxy = Double.NEGATIVE_INFINITY;
		
		for (Node n : network.getNodes().values()) {
			if (n.getCoord().getX() < minx) { minx = n.getCoord().getX(); }
			if (n.getCoord().getY() < miny) { miny = n.getCoord().getY(); }
			if (n.getCoord().getX() > maxx) { maxx = n.getCoord().getX(); }
			if (n.getCoord().getY() > maxy) { maxy = n.getCoord().getY(); }
		}
		minx -= 1.0;
		miny -= 1.0;
		maxx += 1.0;
		maxy += 1.0;
		
		log.info("building QuadTree for nodes: xrange(" + minx + "," + maxx + "); yrange(" + miny + "," + maxy + ")");
		QuadTree<Node> quadTree = new QuadTree<Node>(minx, miny, maxx, maxy);
		for (Node n : network.getNodes().values()) {
			quadTree.put(n.getCoord().getX(), n.getCoord().getY(), n);
		}
		/* assign the quadTree at the very end, when it is complete.
		 * otherwise, other threads may already start working on an incomplete quadtree
		 */
		log.info("Building QuadTree took " + ((System.currentTimeMillis() - startTime) / 1000.0) + " seconds.");
		
		return quadTree;
	}
}
