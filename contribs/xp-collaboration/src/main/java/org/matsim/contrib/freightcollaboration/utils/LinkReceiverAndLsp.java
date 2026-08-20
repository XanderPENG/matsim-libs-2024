package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.logistics.*;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils.CARRIER_TYPE;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.logistics.shipment.LspShipmentUtils;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.Order;

import java.util.*;

public class LinkReceiverAndLsp {

	/**
	 * Creates an isolated structural copy of an LSP, including fresh resources, affiliated carriers,
	 * plans and shipments.
	 */
	public static LSP copyLsp(LSP originalLsp, Scenario scenario) {
		Objects.requireNonNull(originalLsp, "originalLsp");
		Objects.requireNonNull(scenario, "scenario");
		LSPPlan originalPlan = Objects.requireNonNull(originalLsp.getSelectedPlan(),
			"originalLsp selected plan");
		Map<Id<LSPResource>, LSPResource> resourceMap = cloneResources(originalLsp.getResources(), scenario);
		List<LogisticChain> clonedChains = cloneLogisticChains(originalPlan.getLogisticChains(), resourceMap);
		LSPPlan copiedPlan = LSPUtils.createLSPPlan();
		clonedChains.forEach(copiedPlan::addLogisticChain);
		copiedPlan.setInitialShipmentAssigner(originalPlan.getInitialShipmentAssigner());
		copiedPlan.setType(originalPlan.getType());
		copiedPlan.setScore(originalPlan.getScore());
		LogisticChainScheduler scheduler =
			ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(
				deriveOrderedResources(clonedChains));
		LSP copy = LSPUtils.LSPBuilder.getInstance(originalLsp.getId())
			.setLogisticChainScheduler(scheduler)
			.setInitialPlan(copiedPlan)
			.build();
		copiedPlan.setLSP(copy);
		copyAttributes(originalLsp, copy);
		rebuildShipments(originalLsp, Set.of()).forEach(copy::assignShipmentToLspPlan);
		return copy;
	}
	/**
	 * This method is used to replan the LSP since the receivers' collaboration that changed the TW/services
	 * would likely make the current LSP plan infeasible or suboptimal.
	 *
	 * The aim is to use the LSP's inner function: lsp.scheduleLogisticChains(),
	 * so that its new plan as well as the affiliated carriers' plans will be created.
	 *
	 * So, the basic idea is to create a new LSP, who has similar (but newly created) logistic elements/resources/chains
	 * as the original LSP, but modified based on the receivers orders requirements and then call lsp.scheduleLogisticChains() on the new LSP.
	 *
	 */
	public static LSP receiversTriggerLspReplan(FreightCollaborator<LSP> lspCollaborator,
									Set<FreightCollaborator<Receiver>> receiverCollaborators,
									Network network, TravelTime tt, int maxIterations,
									Scenario scenario){

		LSP originalLsp = lspCollaborator.getDelegate();
		LSPPlan originalPlan = originalLsp.getSelectedPlan();

		// 1) clone resources (carriers & hubs) so scheduling cannot touch the originals
		Map<Id<LSPResource>, LSPResource> resourceMap = cloneResources(originalLsp.getResources(), scenario);

		// 2) clone logistic chains with fresh elements wired to the cloned resources
		List<LogisticChain> clonedChains = cloneLogisticChains(originalPlan.getLogisticChains(), resourceMap);

		// 3) create a new LSP plan
		LSPPlan newPlan = LSPUtils.createLSPPlan();
		clonedChains.forEach(newPlan::addLogisticChain);
		InitialShipmentAssigner assigner = originalPlan.getInitialShipmentAssigner();
		newPlan.setInitialShipmentAssigner(assigner);
		newPlan.setType(originalPlan.getType());
		newPlan.setScore(originalPlan.getScore());

		// 4) create a fresh scheduler. Prefer a simple forward scheduler with explicit resource order
		var orderedResources = deriveOrderedResources(clonedChains);
		LogisticChainScheduler scheduler = ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(orderedResources);
		LSP newLsp = LSPUtils.LSPBuilder.getInstance(originalLsp.getId())
			.setLogisticChainScheduler(scheduler)
			.setInitialPlan(newPlan)
			.build();
		newPlan.setLSP(newLsp);
		copyAttributes(originalLsp, newLsp);

		// 5) rebuild shipments by cloning originals and applying receiver updates
		List<LspShipment> newShipments = rebuildShipments(originalLsp, receiverCollaborators);
		newShipments.forEach(newLsp::assignShipmentToLspPlan);

		// 6) schedule – this will populate carrier plans on the cloned resources only
		newLsp.scheduleLogisticChains();

		return newLsp;
	}

	private static Map<Id<LSPResource>, LSPResource> cloneResources(Collection<LSPResource> originals, Scenario scenario){
		Map<Id<LSPResource>, LSPResource> result = new HashMap<>();
		for (LSPResource res : originals) {
			LSPResource cloned = cloneResource(res, scenario);
			result.put(res.getId(), cloned);
		}
		return result;
	}

	private static List<LSPResource> deriveOrderedResources(List<LogisticChain> chains){
		List<LSPResource> ordered = new ArrayList<>();
		for (LogisticChain chain : chains){
			LogisticChainElement start = findFirstElement(chain);
			LogisticChainElement cursor = start;
			while (cursor != null){
				LSPResource res = cursor.getResource();
				if (!ordered.contains(res)) ordered.add(res);
				cursor = cursor.getNextElement();
			}
		}
		return ordered;
	}

	private static LSPResource cloneResource(LSPResource resource, Scenario scenario){
		if (resource instanceof org.matsim.freight.logistics.resourceImplementations.TransshipmentHubResource hub){
			var hubSchedulerBuilder = ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance()
					.setCapacityNeedFixed(hub.getCapacityNeedFixed())
					.setCapacityNeedLinear(hub.getCapacityNeedLinear());
			var hubBuilder = ResourceImplementationUtils.TransshipmentHubBuilder.newInstance(
					hub.getId(), hub.getStartLinkId(), scenario)
					.setTransshipmentHubScheduler(hubSchedulerBuilder.build());
			return hubBuilder.build();
		}

		if (resource instanceof org.matsim.freight.logistics.LSPCarrierResource carrierRes){
			Carrier originalCarrier = carrierRes.getCarrier();
			Carrier copiedCarrier = copyCarrierBasics(originalCarrier);
			CARRIER_TYPE type = ResourceImplementationUtils.getCarrierType(originalCarrier);
			switch (type){
				case distributionCarrier -> {
					var builder = ResourceImplementationUtils.DistributionCarrierResourceBuilder
							.newInstance(copiedCarrier)
							.setLocationLinkId(resource.getStartLinkId())
							.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler(scenario));
					return builder.build();
				}
				case collectionCarrier -> {
					var builder = ResourceImplementationUtils.CollectionCarrierResourceBuilder
							.newInstance(copiedCarrier)
							.setLocationLinkId(resource.getStartLinkId())
							.setCollectionScheduler(ResourceImplementationUtils.createDefaultCollectionCarrierScheduler(scenario));
					return builder.build();
				}
				case mainRunCarrier -> {
					var builder = ResourceImplementationUtils.MainRunCarrierResourceBuilder
							.newInstance(copiedCarrier)
							.setFromLinkId(resource.getStartLinkId())
							.setToLinkId(resource.getEndLinkId())
							// Cannot read original return behaviour; assume return to from-link
							// TODO: maybe can be set as attribute in the config
							.setVehicleReturn(ResourceImplementationUtils.VehicleReturn.returnToFromLink)
							.setMainRunCarrierScheduler(ResourceImplementationUtils.createDefaultMainRunCarrierScheduler(scenario));

					return builder.build();
				}
				default -> throw new IllegalArgumentException("Unsupported carrier type for resource " + resource.getId());
			}
		}

		throw new IllegalArgumentException("Unknown LSPResource type for " + resource.getId());
	}

	private static Carrier copyCarrierBasics(Carrier original){
		return AllocationUtils.copyCarrier(original);
	}

	private static List<LogisticChain> cloneLogisticChains(Collection<LogisticChain> originals,
										Map<Id<LSPResource>, LSPResource> resourceMap){
		List<LogisticChain> result = new ArrayList<>();
		for (LogisticChain oldChain : originals){
			Map<Id<org.matsim.freight.logistics.LogisticChainElement>, LogisticChainElement> elementMap = new HashMap<>();
			// first pass: build all elements with cloned resources
			for (LogisticChainElement oldEl : oldChain.getLogisticChainElements()){
				var builder = LSPUtils.LogisticChainElementBuilder.newInstance(oldEl.getId())
						.setResource(resourceMap.get(oldEl.getResource().getId()));
				LogisticChainElement newEl = builder.build();
				elementMap.put(oldEl.getId(), newEl);
			}
			// second pass: wire next/previous according to original chain
			for (LogisticChainElement oldEl : oldChain.getLogisticChainElements()){
				LogisticChainElement newEl = elementMap.get(oldEl.getId());
				LogisticChainElement oldNext = oldEl.getNextElement();
				if (oldNext != null){
					LogisticChainElement newNext = elementMap.get(oldNext.getId());
					newEl.connectWithNextElement(newNext);
				}
			}
			// build chain
			var chainBuilder = LSPUtils.LogisticChainBuilder.newInstance(oldChain.getId());
			// attempt to preserve forward order starting from first element
			LogisticChainElement start = findFirstElement(oldChain);
			if (start != null){
				LogisticChainElement cursor = start;
				while (cursor != null){
					chainBuilder.addLogisticChainElement(elementMap.get(cursor.getId()));
					cursor = cursor.getNextElement();
				}
			}else{
				// fallback: arbitrary order
				elementMap.values().forEach(chainBuilder::addLogisticChainElement);
			}
			LogisticChain newChain = chainBuilder.build();
			result.add(newChain);
		}
		return result;
	}

	private static LogisticChainElement findFirstElement(LogisticChain chain){
		for (LogisticChainElement el : chain.getLogisticChainElements()){
			if (el.getPreviousElement() == null){
				return el;
			}
		}
		return null;
	}

	private static void copyAttributes(LSP from, LSP to){
		from.getAttributes().getAsMap().forEach(to.getAttributes()::putAttribute);
	}

	private static List<LspShipment> rebuildShipments(LSP originalLsp,
										Set<FreightCollaborator<Receiver>> receiverCollaborators){
		// Map by receiver link for deterministic lookup
		Map<Id<Link>, ReceiverPlan> receiverPlansByLink = new HashMap<>();
		for (FreightCollaborator<Receiver> rc : receiverCollaborators){
			ReceiverPlan plan = rc.getDelegate().getSelectedPlan();
			if (plan != null && rc.getDelegate().getLinkId() != null){
				receiverPlansByLink.put(rc.getDelegate().getLinkId(), plan);
			}
		}

		List<LspShipment> cloned = new ArrayList<>();
		for (LspShipment orig : originalLsp.getLspShipments()){
			ReceiverPlan plan = receiverPlansByLink.get(orig.getTo());
			TimeWindow deliveryTw = plan == null || plan.getTimeWindows().isEmpty()
					? orig.getDeliveryTimeWindow()
					: plan.getTimeWindows().getFirst();

			double serviceTime = orig.getDeliveryServiceTime();
			int capacity = orig.getSize();
			if (plan != null){
				for (ReceiverOrder ro : plan.getReceiverOrders()){
					serviceTime = ro.getReceiverProductOrders().stream().mapToDouble(Order::getServiceDuration).sum();
					capacity = (int) Math.max(1, Math.round(ro.getReceiverProductOrders().stream()
							.mapToDouble(o -> o.getDailyOrderQuantity()*o.getProduct().getProductType().getRequiredCapacity()).sum()));
					break; // first matching order for this receiver
				}
			}

			var b = LspShipmentUtils.LspShipmentBuilder.newInstance(orig.getId());
			b.setFromLinkId(orig.getFrom());
			b.setToLinkId(orig.getTo());
			b.setStartTimeWindow(orig.getPickupTimeWindow()); // preserve original pickup TW
			b.setEndTimeWindow(deliveryTw);                  // apply updated delivery TW if changed
			b.setCapacityDemand(capacity);
			b.setDeliveryServiceTime(serviceTime);
			b.setPickupServiceTime(orig.getPickupServiceTime());
			cloned.add(b.build());
		}
		return cloned;
	}


	/**
	 * Find the collaborated receivers that are linked with the given LSP via its affiliated carriers.
	 * @param lspFreightCollaborator
	 * @param collaboratedReceiversMap: the all identified collaborated receivers in the scenario
	 * @return
	 */
	public static Map<Id<Receiver>, FreightCollaborator<Receiver>> findLinkedCollaboratedReceiversWithLsp(FreightCollaborator<LSP> lspFreightCollaborator,
														 Map<Id<Receiver>, FreightCollaborator<Receiver>> collaboratedReceiversMap){
		LSP lsp = lspFreightCollaborator.getDelegate();
		// Get the affiliated Carrier Ids of this LSP
		Set<Id<Carrier>> affiliatedCarrierIds = new HashSet<>();
		for (LSPResource resource: lsp.getResources()){
			if (resource instanceof LSPCarrierResource carrierResource){
				affiliatedCarrierIds.add(carrierResource.getCarrier().getId());
			}
		}
		// for-loop all the scenario collaborated receivers to find the linked ones
		Map<Id<Receiver>, FreightCollaborator<Receiver>> linkedReceiversMap = new HashMap<>();
		for (FreightCollaborator<Receiver> receiverCollaborator: collaboratedReceiversMap.values()) {
			var collaboratedCarrierIds = receiverCollaborator.getCollaborationPartners();
			collaboratedCarrierIds.forEach(id -> {
				if (affiliatedCarrierIds.contains(id)){
					linkedReceiversMap.put(receiverCollaborator.getDelegate().getId(), receiverCollaborator);
				}
			});
		}
		return linkedReceiversMap;
	}
}
