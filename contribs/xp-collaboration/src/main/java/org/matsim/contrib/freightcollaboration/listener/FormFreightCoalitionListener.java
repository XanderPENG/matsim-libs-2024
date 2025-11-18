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
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.FreightCarriersConfigGroup;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.*;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_CARRIER;
import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER;

/**
 * Functions that need to be executed at the start of each iteration.
 * The functions are listed below:
 * 	1. Form coalitions
 */
public class FormFreightCoalitionListener implements IterationStartsListener {

	private static final Logger LOGGER = LogManager.getLogger(FormFreightCoalitionListener.class);

	@Inject
	private Scenario scenario;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private FreightCoalitionManager freightCoalitionManager;

	@Inject
	CollaborationDataStore collaborationDataStore;

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
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
		List<MutableFreightCoalition> mutableCoalitions = new ArrayList<>();
		for (CollaborationType collaborationType : collaborationTypes){
			switch (collaborationType) {
				// Form carrier-receiver mutable coalitions
				case CARRIER_RECEIVER -> {
					// get all carriers from the freight collaborators
					Map<Id<Carrier>, FreightCollaborator<Carrier>> carrierCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.CARRIER);
					// for-loop through all carrier collaborators
					for (FreightCollaborator<Carrier> carrierCollaborator : carrierCollaborators.values()){
						// find all linked collaborating receivers for this carrier
						Set<FreightCollaborator<Receiver>> linkedCollaboratingReceivers = findCollaboratingReceiversForCarrier(carrierCollaborator);
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

	private Set<FreightCollaborator<Receiver>> findCollaboratingReceiversForCarrier(FreightCollaborator<Carrier> carrierCollaborator){;
		Set<FreightCollaborator<Receiver>> collaboratingReceivers = new HashSet<>();
		// Get all receiver collaborators
		Map<Id<Receiver>, FreightCollaborator<Receiver>> receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
		// Get the linked receivers for this carrier by comparing the receiver plan against its origin plan
		for (FreightCollaborator<Receiver> receiverCollaborator : receiverCollaborators.values()){
			// Check if the receiver is linked to the carrier
			ReceiverPlan receiverPlan = receiverCollaborator.getTypedSelectedPlan();
			for (ReceiverOrder order: receiverPlan.getReceiverOrders()){
				// if any of the orders is assigned to this carrier, then judge if it is different from the original plan
				if (order.getCarrierId() == carrierCollaborator.getId()){
					// Get the TWs and service durations of this current plan
					List<TimeWindow> thisTWs = receiverPlan.getTimeWindows();
					List<Double> thisServiceDurations = new ArrayList<>();
					order.getReceiverProductOrders().forEach(productOrder -> {
						thisServiceDurations.add(productOrder.getServiceDuration());
					});

					// Get the TWs and service durations of the original plan
					ReceiverPlan originalPlan = (ReceiverPlan) collaborationDataStore.getOriginalPlans().get(CollaboratorRole.RECEIVER).get(receiverCollaborator.getId());
					List<TimeWindow> originalTWs = originalPlan.getTimeWindows();
					List<Double> originalServiceDurations = new ArrayList<>();
					Objects.requireNonNull(originalPlan.getReceiverOrders().stream()
							.filter(o -> o.getCarrierId() == carrierCollaborator.getId())
							.findFirst()
							.orElse(null))
							.getReceiverProductOrders()
							.forEach(productOrder -> {
								originalServiceDurations.add(productOrder.getServiceDuration());
							});
					// case 1: if any of the TW has been extended
					for (int i = 0; i < thisTWs.size(); i++) {
						TimeWindow thisTW = thisTWs.get(i);
						TimeWindow originalTW = originalTWs.get(i);
						if (thisTW.getStart() < originalTW.getStart() ||
								thisTW.getEnd() > originalTW.getEnd()) {
							collaboratingReceivers.add(receiverCollaborator);
						}
					}

					// case 2: if the service duration has been contracted
					for (int i = 0; i < thisServiceDurations.size(); i++) {
						double thisServiceDuration = thisServiceDurations.get(i);
						double originalServiceDuration = originalServiceDurations.get(i);
						if (thisServiceDuration < originalServiceDuration) {
							collaboratingReceivers.add(receiverCollaborator);
						}
					}
				}
			}
		}
		return collaboratingReceivers;
	}


}
