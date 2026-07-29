package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

/**
 * This listener is intended to perform actions at the end of each iteration,
 * specifically related to converting computed allocation results into scores for freight collaboration.
 * @deprecated as we can implement it in the scoring functions directly now.
 * @author Xander Peng
 */
@Deprecated
public class AllocationToScoreListener implements IterationEndsListener {
	private static final Logger LOGGER = LogManager.getLogger(AllocationToScoreListener.class);

	@Inject
	CollaborationDataStore collaborationDataStore;

	@Inject
	Scenario scenario;

	@Inject
	FreightCollaborators freightCollaborators;

	public AllocationToScoreListener() {
	}

	AllocationToScoreListener(CollaborationDataStore collaborationDataStore, Scenario scenario,
			FreightCollaborators freightCollaborators) {
		this.collaborationDataStore = collaborationDataStore;
		this.scenario = scenario;
		this.freightCollaborators = freightCollaborators;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		// Also, skip if this is the first iteration
		if (event.getIteration() == scenario.getConfig().controller().getFirstIteration()) {
			LOGGER.info("First iteration - skipping allocation to score conversion.");
			return;
		}

		convertAllocationToScores();
	}

	/**
	 * Converts allocated values from the CollaborationDataStore into scores for each freight collaborator's selected plan.
	 * This is a temporary solution until a more integrated approach is implemented within the scoring functions.
	 */
	private void convertAllocationToScores(){
		if (collaborationDataStore.getAllocatedValues() == null || collaborationDataStore.getAllocatedValues().isEmpty()){
			LOGGER.warn("No allocated values found. Skipping allocation to score conversion.");
			return;
		}
		// If there are allocated values, convert them to scores
		for (var entry : collaborationDataStore.getAllocatedValues().entrySet()) {
			var collaboratorKey = entry.getKey();
			double allocatedValue = entry.getValue();
			var freightCollaborator = freightCollaborators.getFreightCollaborator(
				collaboratorKey.role(), collaboratorKey.id());
			if (freightCollaborator == null) {
				throw new IllegalStateException("No collaborator registered for allocation key " + collaboratorKey);
			}
			var selPlan = freightCollaborator.getTypedSelectedPlan();
			double selPlanScore = selPlan.getScore() == null ? 0.0 : selPlan.getScore();
			selPlan.setScore(selPlanScore + allocatedValue);
		}
	}

}
