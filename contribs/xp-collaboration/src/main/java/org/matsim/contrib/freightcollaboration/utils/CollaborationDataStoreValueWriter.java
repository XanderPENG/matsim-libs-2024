package org.matsim.contrib.freightcollaboration.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.core.utils.io.MatsimXmlWriter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A utility class for writing two key values in the CollaborationDataStore: simulatedCoalitionScores and allocatedValues
 * for each iteration.
 *
 * Usage example:
 * <pre>
 * CollaborationDataStoreValueWriter writer = new CollaborationDataStoreValueWriter(dataStore, iteration);
 * writer.write(outputPath + "/ITERS/it." + iteration + "/collaboration_data_it" + iteration + ".xml");
 * </pre>
 *
 * @author xp
 */
public class CollaborationDataStoreValueWriter extends MatsimXmlWriter {

	private static final Logger log = LogManager.getLogger(CollaborationDataStoreValueWriter.class);

	private final CollaborationDataStore dataStore;
	private final int iteration;

	/**
	 * Constructor for writing collaboration data for a specific iteration.
	 *
	 * @param dataStore The collaboration data store containing coalition scores and allocated values
	 * @param iteration The iteration number
	 */
	public CollaborationDataStoreValueWriter(CollaborationDataStore dataStore, int iteration) {
		this.dataStore = dataStore;
		this.iteration = iteration;
	}

	public void write(String filename) {
		log.info("Writing collaboration data for iteration " + iteration + " to file: " + filename);
		try {
			openFile(filename);
			writeXmlHead();
			writeCollaborationData();
			close();
			log.info("Collaboration data written successfully.");
		} catch (IOException e) {
			log.error("Error writing collaboration data to file: " + filename, e);
			throw new RuntimeException(e);
		}
	}

	private void writeCollaborationData() throws IOException {
		// Root element
		writer.write("<collaborationData");
		writer.write(" iteration=\"" + iteration + "\"");
		writer.write(">\n\n");

		// Write coalition scores
		writeCoalitionScores();

		// Write allocated values
		writeAllocatedValues();

		// Close root element
		writer.write("</collaborationData>\n");
	}

	private void writeCoalitionScores() throws IOException {
		Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> coalitionScores =
			dataStore.getSimulatedCoalitionScores();

		if (coalitionScores == null || coalitionScores.isEmpty()) {
			writer.write("\t<!-- No coalition scores available for this iteration -->\n\n");
			return;
		}

		writer.write("\t<coalitionScores>\n");

		int coalitionIndex = 1;
		for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : coalitionScores.entrySet()) {
			MutableFreightCoalition coalition = entry.getKey();
			Map<Set<Id<?>>, Double> subCoalitionScores = entry.getValue();

			writer.write("\t\t<coalition");
			writer.write(" id=\"" + coalitionIndex + "\"");
			writer.write(" type=\"" + coalition.getCollaborationType() + "\"");
			writer.write(" size=\"" + coalition.size() + "\"");
			writer.write(">\n");

			// Write coalition members
			writer.write("\t\t\t<members>\n");
			for (var collaborator : coalition.getCollaboratorsSet()) {
				writer.write("\t\t\t\t<member");
				writer.write(" id=\"" + collaborator.getId() + "\"");
				writer.write(" role=\"" + collaborator.getRole() + "\"");
				writer.write("/>\n");
			}
			writer.write("\t\t\t</members>\n");

			// Write sub-coalition scores
			writer.write("\t\t\t<subCoalitionScores>\n");
			for (Map.Entry<Set<Id<?>>, Double> scoreEntry : subCoalitionScores.entrySet()) {
				Set<Id<?>> subCoalition = scoreEntry.getKey();
				Double score = scoreEntry.getValue();

				writer.write("\t\t\t\t<subCoalition");
				writer.write(" size=\"" + subCoalition.size() + "\"");
				writer.write(" score=\"" + score + "\"");

				if (subCoalition.isEmpty()) {
					writer.write(" type=\"baseline\"");
					writer.write("/>\n");
				} else {
					writer.write(">\n");
					// Write member IDs
					for (Id<?> memberId : subCoalition) {
						writer.write("\t\t\t\t\t<memberId>" + memberId.toString() + "</memberId>\n");
					}
					writer.write("\t\t\t\t</subCoalition>\n");
				}
			}
			writer.write("\t\t\t</subCoalitionScores>\n");

			writer.write("\t\t</coalition>\n\n");
			coalitionIndex++;
		}

		writer.write("\t</coalitionScores>\n\n");
	}

	private void writeAllocatedValues() throws IOException {
		Map<Id<?>, Double> allocatedValues = dataStore.getAllocatedValues();

		if (allocatedValues == null || allocatedValues.isEmpty()) {
			writer.write("\t<!-- No allocated values available for this iteration -->\n\n");
			return;
		}

		writer.write("\t<allocatedValues>\n");

		// Calculate total allocated value
		double totalAllocated = allocatedValues.values().stream()
			.mapToDouble(Double::doubleValue)
			.sum();

		writer.write("\t\t<!-- Total allocated value: " + totalAllocated + " -->\n\n");

		for (Map.Entry<Id<?>, Double> entry : allocatedValues.entrySet()) {
			writer.write("\t\t<allocation");
			writer.write(" collaboratorId=\"" + entry.getKey() + "\"");
			writer.write(" value=\"" + entry.getValue() + "\"");
			writer.write("/>\n");
		}

		writer.write("\t</allocatedValues>\n\n");
	}

	/**
	 * Static utility method to write coalition scores and allocated values to a file.
	 *
	 * @param dataStore The collaboration data store
	 * @param filename The output filename (with full path)
	 * @param iteration The iteration number
	 */
	public static void writeCoalitionScoresAndAllocatedValues(CollaborationDataStore dataStore,
	                                                           String filename,
	                                                           int iteration) {
		CollaborationDataStoreValueWriter writer = new CollaborationDataStoreValueWriter(dataStore, iteration);
		writer.write(filename);
	}

	/**
	 * Write only coalition scores to a file.
	 *
	 * @param dataStore The collaboration data store
	 * @param filename The output filename
	 * @param iteration The iteration number
	 */
	public static void writeCoalitionScores(CollaborationDataStore dataStore,
	                                        String filename,
	                                        int iteration) {
		log.info("Writing coalition scores for iteration " + iteration + " to file: " + filename);
		try {
			BufferedWriter writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filename);
			writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			writer.write("<coalitionScores iteration=\"" + iteration + "\">\n");

			Map<MutableFreightCoalition, Map<Set<Id<?>>, Double>> coalitionScores =
				dataStore.getSimulatedCoalitionScores();

			if (coalitionScores != null && !coalitionScores.isEmpty()) {
				int coalitionIndex = 1;
				for (Map.Entry<MutableFreightCoalition, Map<Set<Id<?>>, Double>> entry : coalitionScores.entrySet()) {
					writer.write("\t<coalition id=\"" + coalitionIndex + "\" " +
						"type=\"" + entry.getKey().getCollaborationType() + "\">\n");

					for (Map.Entry<Set<Id<?>>, Double> scoreEntry : entry.getValue().entrySet()) {
						String memberIds = scoreEntry.getKey().stream()
							.map(Object::toString)
							.collect(Collectors.joining(","));
						writer.write("\t\t<score members=\"" + memberIds + "\" value=\"" +
							scoreEntry.getValue() + "\"/>\n");
					}
					writer.write("\t</coalition>\n");
					coalitionIndex++;
				}
			}

			writer.write("</coalitionScores>\n");
			writer.close();
			log.info("Coalition scores written successfully.");
		} catch (IOException e) {
			log.error("Error writing coalition scores", e);
			throw new RuntimeException(e);
		}
	}

	/**
	 * Write only allocated values to a file.
	 *
	 * @param dataStore The collaboration data store
	 * @param filename The output filename
	 * @param iteration The iteration number
	 */
	public static void writeAllocatedValues(CollaborationDataStore dataStore,
	                                        String filename,
	                                        int iteration) {
		log.info("Writing allocated values for iteration " + iteration + " to file: " + filename);
		try {
			BufferedWriter writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filename);
			writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			writer.write("<allocatedValues iteration=\"" + iteration + "\">\n");

			Map<Id<?>, Double> allocatedValues = dataStore.getAllocatedValues();

			if (allocatedValues != null && !allocatedValues.isEmpty()) {
				double totalAllocated = allocatedValues.values().stream()
					.mapToDouble(Double::doubleValue)
					.sum();

				writer.write("\t<!-- Total: " + totalAllocated + " -->\n");

				for (Map.Entry<Id<?>, Double> entry : allocatedValues.entrySet()) {
					writer.write("\t<allocation collaboratorId=\"" + entry.getKey() +
						"\" value=\"" + entry.getValue() + "\"/>\n");
				}
			}

			writer.write("</allocatedValues>\n");
			writer.close();
			log.info("Allocated values written successfully.");
		} catch (IOException e) {
			log.error("Error writing allocated values", e);
			throw new RuntimeException(e);
		}
	}
}
