package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndLsp;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.logistics.*;
import org.matsim.freight.logistics.io.LSPPlanXmlWriter;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.ReceiversWriter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.LSP_RECEIVER;

/**
 * This is a generic listener for re-routing followers when player's plan changed.
 * It should be collaboration type-based
 * Currently, only the LSP-receiver type will be implemented.
 */
public class ReRouteListener implements BeforeMobsimListener {

	@Inject
	Scenario scenario;

	@Inject
	Config config;

	@Inject
	FreightCollaborators freightCollaborators;

	@Inject
	OutputDirectoryHierarchy controlerIO;

	@Override
	public double priority() {
		return BeforeMobsimListener.super.priority();
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {

		// Skip the iteration 0
		if (event.getIteration() == 0){
			return;
		}

		FreightCollaborationConfigGroup fccg = (FreightCollaborationConfigGroup) config.getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		// For each collaboration type defined in the config, call its specific pre-reroute
		fccg.getCollaborationParamSets().forEach(paramSet -> {
			switch (paramSet.getCollaborationType()) {
				case LSP_RECEIVER:
					Map<Id<Receiver>, FreightCollaborator<Receiver>> receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
					Map<Id<LSP>, FreightCollaborator<LSP>> lspCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.LSP);

					// For each LSP, find its linked receivers and replan in-place to avoid swapping scenario elements
					for (FreightCollaborator<LSP> lspCollaborator : lspCollaborators.values()) {
						Set<FreightCollaborator<Receiver>> linkedReceivers = new HashSet<>();
						receiverCollaborators.values().forEach(rc -> {
							var originConnectedLspIds = rc.getOriginalConnectedStakeholders().get(CollaboratorRole.LSP);
							if (originConnectedLspIds.isEmpty()){
								throw new RuntimeException("Original connected LSPs for receiver " + rc.getDelegate().getId() + " is empty.");
							}
							if (originConnectedLspIds.contains(lspCollaborator.getId())){
								linkedReceivers.add(rc);
							}
						});

						// Build a replanned LSP offline, then transplant its plan onto the already-registered LSP instance.
						LSP replannedLsp = LinkReceiverAndLsp.receiversTriggerLspReplan(lspCollaborator, linkedReceivers, null, null, 100, scenario );
						applyReplanInPlace(lspCollaborator.getDelegate(), replannedLsp);
					}

					break;
			default:
				throw new IllegalStateException("Unexpected value: " + paramSet.getCollaborationType());
			}
		});

		writeCollaboratorPlans(event.getIteration());
	}

	void applyReplanInPlace(LSP originalLsp, LSP replannedLsp) {
		// Map existing resources by id – the scheduler inside the LSP keeps a reference to these objects
		Map<Id<LSPResource>, LSPResource> originalResources = originalLsp.getResources().stream()
			.collect(Collectors.toMap(LSPResource::getId, Function.identity()));

		// Remove stale chain links from resources so newly built elements attach cleanly
		originalLsp.getResources().forEach(res -> res.getClientElements().clear());

		LSPPlan prototypePlan = replannedLsp.getSelectedPlan();
		LSPPlan newPlan = LSPUtils.createLSPPlan();
		newPlan.setInitialShipmentAssigner(prototypePlan.getInitialShipmentAssigner());
		newPlan.setType(prototypePlan.getType());
		newPlan.setScore(prototypePlan.getScore());

		// Rebuild logistic chains so they point to the original resources but have fresh state
		for (LogisticChain chain : prototypePlan.getLogisticChains()) {
			Map<Id<LogisticChainElement>, LogisticChainElement> newElements = new HashMap<>();
			for (LogisticChainElement element : chain.getLogisticChainElements()) {
				LSPResource resource = originalResources.get(element.getResource().getId());
				if (resource == null) {
					throw new RuntimeException("Resource " + element.getResource().getId() + " is missing on original LSP " + originalLsp.getId());
				}
				LogisticChainElement newElement = LSPUtils.LogisticChainElementBuilder
					.newInstance(element.getId())
					.setResource(resource)
					.build();
				newElements.put(element.getId(), newElement);
			}

			for (LogisticChainElement element : chain.getLogisticChainElements()) {
				if (element.getNextElement() != null) {
					LogisticChainElement next = newElements.get(element.getNextElement().getId());
					newElements.get(element.getId()).connectWithNextElement(next);
				}
			}

			LogisticChainElement start = findFirstElement(chain);
			var chainBuilder = LSPUtils.LogisticChainBuilder.newInstance(chain.getId());
			LogisticChainElement cursor = start;
			while (cursor != null) {
				chainBuilder.addLogisticChainElement(newElements.get(cursor.getId()));
				cursor = cursor.getNextElement();
			}
			newPlan.addLogisticChain(chainBuilder.build());
		}

		// Swap plans on the existing LSP
		originalLsp.getPlans().clear();
		originalLsp.addPlan(newPlan);
		originalLsp.setSelectedPlan(newPlan);

		// Replace shipments and let the assigner wire them into the fresh plan
		originalLsp.getLspShipments().clear();
		for (LspShipment shipment : replannedLsp.getLspShipments()) {
			originalLsp.assignShipmentToLSP(shipment);
		}

		// Start from clean shipment plans to avoid accumulation across iterations
		originalLsp.getSelectedPlan().getShipmentPlans().clear();
		originalLsp.scheduleLogisticChains();
	}

	LogisticChainElement findFirstElement(LogisticChain chain){
		for (LogisticChainElement el : chain.getLogisticChainElements()){
			if (el.getPreviousElement() == null){
				return el;
			}
		}
		return null;
	}

	private void writeCollaboratorPlans(int iteration) {
		/*
		TODO: Check whether the default LSP's writing sequence is later than this listener;
		    If so, we might need to adjust the priority of this listener to make sure
		    the updated LSP plans are written correctly.
		 */

//		String outputdirectory = config.controller().getOutputDirectory();
//		outputdirectory += outputdirectory.endsWith("/") ? "" : "/";
//		new CarrierPlanWriter(CarriersUtils.getCarriers(scenario)).write(outputdirectory +"./receivers.xml.gz" );
//		new ReceiversWriter( ReceiverUtils.getReceivers(scenario) ).write(outputdirectory + "./carriers.xml.gz");
//		new LSPPlanXmlWriter(LSPUtils.getLSPs(scenario)).write(outputdirectory + "./lsps.xml.gz");
		String carrierFilename = controlerIO.getIterationFilename(iteration, "carriers.xml.gz");
		new CarrierPlanWriter(CarriersUtils.getCarriers(scenario)).write(carrierFilename);

		String receiverFilename = controlerIO.getIterationFilename(iteration, "receivers.xml.gz");
		new ReceiversWriter( ReceiverUtils.getReceivers(scenario) ).write(receiverFilename);

		String lspFilename = controlerIO.getIterationFilename(iteration, "lsps.xml.gz");
		new LSPPlanXmlWriter(LSPUtils.getLSPs(scenario)).write(lspFilename);

	}
}
