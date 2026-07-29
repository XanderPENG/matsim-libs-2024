package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.utils.CollaborationDataStoreValueWriter;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

/**
 * This listener writes collaboration data (coalition scores and allocated values) to XML files
 * at the end of each iteration (after the first iteration).
 *
 * The output files are written to: output/ITERS/it.X/collaboration_data_itX.xml
 *
 * @author xp
 */
public class WriteCollaborationDataListener implements IterationEndsListener {
	private static final Logger LOGGER = LogManager.getLogger(WriteCollaborationDataListener.class);

	@Inject
	CollaborationDataStore collaborationDataStore;

	@Inject
	Scenario scenario;

	@Inject
	OutputDirectoryHierarchy controlerIO;

	public WriteCollaborationDataListener() {
	}

	WriteCollaborationDataListener(CollaborationDataStore collaborationDataStore, Scenario scenario,
			OutputDirectoryHierarchy controlerIO) {
		this.collaborationDataStore = collaborationDataStore;
		this.scenario = scenario;
		this.controlerIO = controlerIO;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		int iteration = event.getIteration();

		// Skip first iteration as no collaboration data exists yet
		if (iteration == scenario.getConfig().controller().getFirstIteration()) {
			LOGGER.info("First iteration - skipping collaboration data writing.");
			return;
		}

		// Check if there's data to write
		if (collaborationDataStore.getSimulatedCoalitionScores() == null &&
			collaborationDataStore.getAllocatedValues() == null) {
			LOGGER.warn("No collaboration data available for iteration " + iteration + " - skipping write.");
			return;
		}

		// Write collaboration data to iteration folder
		writeCollaborationData(iteration);
	}

	private void writeCollaborationData(int iteration) {
		try {
			// Create filename for this iteration
			String filename = controlerIO.getIterationFilename(iteration, "collaboration_data.xml");

			// Write the data using the CollaborationDataStoreValueWriter
			CollaborationDataStoreValueWriter.writeCoalitionScoresAndAllocatedValues(
				collaborationDataStore,
				filename,
				iteration
			);

			LOGGER.info("Successfully wrote collaboration data for iteration " + iteration + " to: " + filename);
		} catch (Exception e) {
			LOGGER.error("Failed to write collaboration data for iteration " + iteration, e);
		}
	}
}
