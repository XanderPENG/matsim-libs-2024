package org.matsim.contrib.freightcollaboration.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Creates MATSim chessboard networks using the same one-way link naming pattern as the
 * freight-chessboard-9x9 example.
 */
public final class CreateFreightChessboardNetwork {
	private static final Logger logger = LogManager.getLogger(CreateFreightChessboardNetwork.class);

	static final int DEFAULT_GRID_SIZE = 20;
	static final double LINK_LENGTH = 1000.0;
	static final double FREE_SPEED = 7.5;
	static final double CAPACITY = 10.0;
	static final double LANES = 1.0;

	private static final Set<String> CAR_MODE = Set.of(TransportMode.car);

	private CreateFreightChessboardNetwork() {
	}

	public static void main(String[] args) {
		Options options = parseOptions(args);
		writeNetwork(options.gridSize(), options.outputFile());
	}

	public static Network createNetwork(int gridSize) {
		validateGridSize(gridSize);

		Network network = NetworkUtils.createNetwork();
		for (int x = 0; x <= gridSize; x++) {
			for (int y = 0; y <= gridSize; y++) {
				NetworkUtils.createAndAddNode(network, Id.createNodeId(nodeId(x, y)),
					new Coord(x * LINK_LENGTH, y * LINK_LENGTH));
			}
		}
		NetworkUtils.createAndAddNode(network, Id.createNodeId("dummy1"), new Coord(-LINK_LENGTH, -LINK_LENGTH));
		NetworkUtils.createAndAddNode(network, Id.createNodeId("dummy2"),
			new Coord((gridSize + 1) * LINK_LENGTH, (gridSize + 1) * LINK_LENGTH));

		for (int x = 1; x <= gridSize; x++) {
			for (int y = 0; y <= gridSize; y++) {
				addHorizontalLink(network, x, y);
			}
		}
		for (int x = 0; x <= gridSize; x++) {
			for (int y = 1; y <= gridSize; y++) {
				addVerticalLink(network, x, y);
			}
		}

		return network;
	}

	public static void writeNetwork(int gridSize, Path outputFile) {
		try {
			Path parent = outputFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Network network = createNetwork(gridSize);
			new NetworkWriter(network).write(outputFile.toString());
			logger.info("Wrote {}x{} chessboard network to {}", gridSize, gridSize, outputFile);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create output directory for " + outputFile, e);
		}
	}

	public static Path defaultNetworkPath(int gridSize) {
		validateGridSize(gridSize);
		return Path.of("data", "freightChessboardRC", "generatedNetworks",
			"grid%dx%d.xml".formatted(gridSize, gridSize));
	}

	public static String horizontalLinkId(int x, int y) {
		return "i(%d,%d)%s".formatted(x, y, y % 2 == 0 ? "" : "R");
	}

	public static String verticalLinkId(int x, int y) {
		return "j(%d,%d)%s".formatted(x, y, x % 2 == 0 ? "R" : "");
	}

	private static void addHorizontalLink(Network network, int x, int y) {
		boolean reverse = y % 2 != 0;
		Node fromNode = node(network, reverse ? x : x - 1, y);
		Node toNode = node(network, reverse ? x - 1 : x, y);
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(horizontalLinkId(x, y)), fromNode, toNode,
			LINK_LENGTH, FREE_SPEED, CAPACITY, LANES);
		link.setAllowedModes(CAR_MODE);
	}

	private static void addVerticalLink(Network network, int x, int y) {
		boolean reverse = x % 2 == 0;
		Node fromNode = node(network, x, reverse ? y : y - 1);
		Node toNode = node(network, x, reverse ? y - 1 : y);
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(verticalLinkId(x, y)), fromNode, toNode,
			LINK_LENGTH, FREE_SPEED, CAPACITY, LANES);
		link.setAllowedModes(CAR_MODE);
	}

	private static Node node(Network network, int x, int y) {
		return network.getNodes().get(Id.createNodeId(nodeId(x, y)));
	}

	private static String nodeId(int x, int y) {
		return "(%d,%d)".formatted(x, y);
	}

	private static void validateGridSize(int gridSize) {
		if (gridSize < 1) {
			throw new IllegalArgumentException("gridSize must be at least 1, got " + gridSize);
		}
	}

	private static Options parseOptions(String[] args) {
		int gridSize = DEFAULT_GRID_SIZE;
		Path outputFile = null;
		int positional = 0;

		for (String arg : args) {
			if (arg.equals("--help") || arg.equals("-h")) {
				printUsageAndExit();
			} else if (arg.startsWith("--grid-size=")) {
				gridSize = parsePositiveInt(arg.substring("--grid-size=".length()), "--grid-size");
			} else if (arg.startsWith("--size=")) {
				gridSize = parsePositiveInt(arg.substring("--size=".length()), "--size");
			} else if (arg.startsWith("--output=")) {
				outputFile = Path.of(arg.substring("--output=".length()));
			} else if (arg.startsWith("--")) {
				throw new IllegalArgumentException("Unknown option: " + arg);
			} else if (positional == 0) {
				gridSize = parsePositiveInt(arg, "gridSize");
				positional++;
			} else if (positional == 1) {
				outputFile = Path.of(arg);
				positional++;
			} else {
				throw new IllegalArgumentException("Unexpected positional argument: " + arg);
			}
		}

		if (outputFile == null) {
			outputFile = defaultNetworkPath(gridSize);
		}
		return new Options(gridSize, outputFile);
	}

	private static int parsePositiveInt(String value, String optionName) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 1) {
				throw new IllegalArgumentException(optionName + " must be at least 1, got " + parsed);
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(optionName + " must be an integer, got " + value, e);
		}
	}

	private static void printUsageAndExit() {
		System.out.println("""
			Usage:
			  CreateFreightChessboardNetwork [gridSize] [outputFile]
			  CreateFreightChessboardNetwork --grid-size=20 --output=data/freightChessboardRC/generatedNetworks/grid20x20.xml
			""");
		System.exit(0);
	}

	private record Options(int gridSize, Path outputFile) {
	}
}
