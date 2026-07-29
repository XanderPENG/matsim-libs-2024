package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.*;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.utils.CoalitionUtils;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndLsp;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER;
import static org.matsim.contrib.freightcollaboration.CollaborationTypes.LSP_RECEIVER;

/**
 * Functions that need to be executed at the start of each iteration.
 * The functions are listed below:
 * 	1. Form coalitions
 */
public class FormFreightCoalitionListener implements BeforeMobsimListener {

	private static final Logger LOGGER = LogManager.getLogger(FormFreightCoalitionListener.class);

	@Inject
	private Scenario scenario;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private FreightCoalitionManager freightCoalitionManager;

	@Inject
	CollaborationDataStore collaborationDataStore;

	public FormFreightCoalitionListener() {
	}

	FormFreightCoalitionListener(Scenario scenario, FreightCollaborators freightCollaborators,
			FreightCoalitionManager freightCoalitionManager,
			CollaborationDataStore collaborationDataStore) {
		this.scenario = Objects.requireNonNull(scenario, "scenario");
		this.freightCollaborators = Objects.requireNonNull(freightCollaborators, "freightCollaborators");
		this.freightCoalitionManager = Objects.requireNonNull(freightCoalitionManager,
			"freightCoalitionManager");
		this.collaborationDataStore = Objects.requireNonNull(collaborationDataStore,
			"collaborationDataStore");
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		// Form grand coalition at the first iteration
		if (event.getIteration() == scenario.getConfig().controller().getFirstIteration()){
			GrandFreightCoalition grandCoalition = formFreightGrandCoalition();
			freightCoalitionManager.setGrandFreightCoalition(grandCoalition);
			informGrandCoalitionInfo(grandCoalition);
		} else {
			// Form mutable coalitions at each iteration (except the first iteration)
			List<MutableFreightCoalition> mutableFreightCoalitions = formMutableCoalitions();
			informMutableCoalitionsInfo(mutableFreightCoalitions);
			freightCoalitionManager.setMutableFreightCoalitions(mutableFreightCoalitions);
		}

	}

	private GrandFreightCoalition formFreightGrandCoalition(){
		Set<CollaboratorRole> allRoles = freightCoalitionManager.getAllCollaboratorRoles();
		GrandFreightCoalition grandCoalition;
		Set<FreightCollaborator<?>> grandCollaborators = new HashSet<>();
		for (CollaboratorRole role : allRoles) {
			// Get freight collaborators with the role of 'role'
			switch (role){
				case CollaboratorRole.CARRIER -> {
					Map<Id<Carrier>, FreightCollaborator<Carrier>> carrierCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.CARRIER);
					for (FreightCollaborator<Carrier> collaborator : carrierCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				case CollaboratorRole.LSP -> {
					Map<Id<LSP>, FreightCollaborator<LSP>> lspCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.LSP);
					for (FreightCollaborator<LSP> collaborator : lspCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				case CollaboratorRole.RECEIVER -> {
					Map<Id<Receiver>, FreightCollaborator<Receiver>> receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
					for (FreightCollaborator<Receiver> collaborator : receiverCollaborators.values()) {
						if (collaborator.getCollaborationStatus()){
							grandCollaborators.add(collaborator);
						}
					}
				}
				default -> throw new IllegalStateException("Unexpected value: " + role);
			}
		}
		grandCoalition = new GrandFreightCoalition(grandCollaborators);
		return grandCoalition;
	}

	// Form mutable coalitions based on the current iteration context
	private List<MutableFreightCoalition> formMutableCoalitions(){
		FreightCollaborationConfigGroup freightCollaborationConfigGroup = (FreightCollaborationConfigGroup) scenario.getConfig().getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		Set<CollaborationType> collaborationTypes = new HashSet<>();
		freightCollaborationConfigGroup.getCollaborationParamSets().forEach( collaborationParamSet -> {
			collaborationTypes.add(collaborationParamSet.getCollaborationType());
		});
		// Precompute per-receiver deltas once per iteration to avoid repeated scans
		Map<Id<Receiver>, CoalitionUtils.ReceiverDelta> receiverDeltas = precomputeReceiverDeltas();
		List<MutableFreightCoalition> mutableCoalitions = new ArrayList<>();
		for (CollaborationType collaborationType : collaborationTypes){
			switch (collaborationType) {
				// Form carrier-receiver mutable coalitions
				case CARRIER_RECEIVER -> {
					// get all carriers from the freight collaborators
					Map<Id<Carrier>, FreightCollaborator<Carrier>> carrierCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.CARRIER);
					// for-loop through all carrier collaborators
					for (FreightCollaborator<Carrier> carrierCollaborator : carrierCollaborators.values()){
						// find all linked collaborating receivers for this carrier using cached deltas
						Set<FreightCollaborator<Receiver>> linkedCollaboratingReceivers = findCollaboratingReceiversForCarrier(carrierCollaborator, receiverDeltas);
						// if there is any linked collaborating receiver, form a mutable coalition
						if (!linkedCollaboratingReceivers.isEmpty()){
							MutableFreightCoalition mutableCoalition = new MutableFreightCoalition(CARRIER_RECEIVER);
							// add the carrier collaborator
							mutableCoalition.addCollaborator(carrierCollaborator);
							// add all linked collaborating receivers
							for (FreightCollaborator<Receiver> receiverCollaborator : linkedCollaboratingReceivers){
								mutableCoalition.addCollaborator(receiverCollaborator);
							}
							// add the formed mutable coalition to the list
							mutableCoalitions.add(mutableCoalition);
						}
					}
				}
				case LSP_RECEIVER -> {
					// Mark collaborated receivers / update their collaboration status
					CoalitionUtils.markCollaboratedReceiver(freightCollaborators, collaborationDataStore);

					Map<Id<LSP>, FreightCollaborator<LSP>> lspCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.LSP);
					if (lspCollaborators.isEmpty()) {
						throw new IllegalStateException("No LSP found");
					}

					// Firstly, find all collaborating receivers in the scenario
					Map<Id<Receiver>, FreightCollaborator<Receiver>> allCollaboratingReceivers = CoalitionUtils.getCollaboratedReceivers(freightCollaborators);

					// for-loop to find linked collaborating receivers for each LSP
					for (FreightCollaborator<LSP> lspCollaborator : lspCollaborators.values()) {
						Map<Id<Receiver>, FreightCollaborator<Receiver>>  linkedCollaboratingReceivers  = LinkReceiverAndLsp.findLinkedCollaboratedReceiversWithLsp(lspCollaborator, allCollaboratingReceivers);
						// if there is any linked collaborating receiver, form a mutable coalition
						if (!linkedCollaboratingReceivers.isEmpty()) {
							MutableFreightCoalition mutableCoalition = new MutableFreightCoalition(LSP_RECEIVER);
							// add the LSP collaborator
							mutableCoalition.addCollaborator(lspCollaborator);
							// add all linked collaborating receivers
							for (FreightCollaborator<Receiver> receiverCollaborator : linkedCollaboratingReceivers.values()) {
								mutableCoalition.addCollaborator(receiverCollaborator);
							}
							// add the formed mutable coalition to the list
							mutableCoalitions.add(mutableCoalition);
						}
					}

				}

				default -> throw new IllegalStateException("Unexpected value: " + collaborationType);
			}
		}
		return mutableCoalitions;
	}


	private void informGrandCoalitionInfo(GrandFreightCoalition grandCoalition){
		LOGGER.info("Grand Coalition formed with {} collaborators.", grandCoalition.size());
		for (CollaboratorRole role : grandCoalition.getRoles()) {
			long count = grandCoalition.getCollaboratorsSet().stream()
					.filter(collaborator -> collaborator.getRole() == role)
					.count();
			LOGGER.info(" - Role: {}, Number of Collaborators: {}", role, count);
		}
	}

	private void informMutableCoalitionsInfo(List<MutableFreightCoalition> mutableCoalitions){
		LOGGER.info("Formed {} mutable coalitions at this iteration.", mutableCoalitions.size());
		int coalitionIndex = 1;
		for (MutableFreightCoalition mutableCoalition : mutableCoalitions){
			LOGGER.info(" - Coalition {}: Type: {}, Number of Collaborators: {}", coalitionIndex, mutableCoalition.getCollaborationType(), mutableCoalition.size());
			for (CollaboratorRole role : mutableCoalition.getRoles()) {
				long count = mutableCoalition.getCollaboratorsSet().stream()
						.filter(collaborator -> collaborator.getRole() == role)
						.count();
				LOGGER.info("    - Role: {}, Number of Collaborators: {}", role, count);
			}
			coalitionIndex++;
		}
	}

    private Set<FreightCollaborator<Receiver>> findCollaboratingReceiversForCarrier(FreightCollaborator<Carrier> carrierCollaborator,
                                                                                    Map<Id<Receiver>, CoalitionUtils.ReceiverDelta> receiverDeltas){
        Set<FreightCollaborator<Receiver>> collaboratingReceivers = new HashSet<>();
        Map<Id<Receiver>, FreightCollaborator<Receiver>> receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);

        for (Map.Entry<Id<Receiver>, CoalitionUtils.ReceiverDelta> entry : receiverDeltas.entrySet()) {
            CoalitionUtils.ReceiverDelta delta = entry.getValue();
            if (!delta.hasChange()) continue;
            if (!delta.carriers().contains(carrierCollaborator.getId())) continue;
            FreightCollaborator<Receiver> rc = receiverCollaborators.get(entry.getKey());
            if (rc != null) {
                collaboratingReceivers.add(rc);
            }
        }
        return collaboratingReceivers;
    }

    /**
     * Precompute deltas for all receivers once per iteration to avoid O(C×R) repeated work.
     */
    private Map<Id<Receiver>, CoalitionUtils.ReceiverDelta> precomputeReceiverDeltas() {
        Map<Id<Receiver>, CoalitionUtils.ReceiverDelta> deltas = new HashMap<>();
        Map<Id<Receiver>, FreightCollaborator<Receiver>> receivers = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
        var originalReceiverPlans = collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER);
		if (originalReceiverPlans == null || originalReceiverPlans.isEmpty()) {
			return deltas;
		}

        for (Map.Entry<Id<Receiver>, FreightCollaborator<Receiver>> entry : receivers.entrySet()) {
            ReceiverPlan original = (ReceiverPlan) originalReceiverPlans.get(entry.getKey());
            if (original == null) continue;
            ReceiverPlan current = entry.getValue().getTypedSelectedPlan();
            CoalitionUtils.ReceiverDelta delta = CoalitionUtils.computeReceiverDelta(original, current);
            deltas.put(entry.getKey(), delta);
        }
        return deltas;
    }

	/**
	 * Since this listener need to be called after rerouting, it should be put at later order.
	 */
	@Override
	public double priority() {
		return -10;
	}

}
