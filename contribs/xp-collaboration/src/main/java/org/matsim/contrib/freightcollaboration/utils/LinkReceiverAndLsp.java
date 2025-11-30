package org.matsim.contrib.freightcollaboration.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPResource;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.logistics.LogisticChain;
import org.matsim.freight.logistics.LogisticChainElement;
import org.matsim.freight.logistics.LogisticChainScheduler;
import org.matsim.freight.logistics.InitialShipmentAssigner;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils.CARRIER_TYPE;
import org.matsim.freight.logistics.shipment.LspShipment;
import org.matsim.freight.logistics.shipment.LspShipmentUtils;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.Order;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Objects;

public class LinkReceiverAndLsp {
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

		// 5) rebuild shipments from the (current) receiver plans and assign them to the cloned LSP
		List<LspShipment> newShipments = buildShipmentsFromReceivers(receiverCollaborators);
		newShipments.forEach(newLsp::assignShipmentToLSP);

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
							.setMainRunCarrierScheduler(ResourceImplementationUtils.createDefaultMainRunCarrierScheduler(scenario));
					return builder.build();
				}
				default -> throw new IllegalArgumentException("Unsupported carrier type for resource " + resource.getId());
			}
		}

		throw new IllegalArgumentException("Unknown LSPResource type for " + resource.getId());
	}

	private static Carrier copyCarrierBasics(Carrier original){
		Carrier copy = org.matsim.freight.carriers.CarriersUtils.createCarrier(original.getId());
		CarrierCapabilities caps = original.getCarrierCapabilities();
		copy.setCarrierCapabilities(caps);
		// copy simple attributes
		original.getAttributes().getAsMap().forEach(copy.getAttributes()::putAttribute);
		return copy;
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

	private static List<LspShipment> buildShipmentsFromReceivers(Set<FreightCollaborator<Receiver>> receiverCollaborators){
		List<LspShipment> shipments = new ArrayList<>();
		int counter = 0;
		for (FreightCollaborator<Receiver> receiverCollab : receiverCollaborators){
			Receiver receiver = receiverCollab.getDelegate();
			ReceiverPlan plan = receiver.getSelectedPlan();
			if (plan == null) continue;
			TimeWindow deliveryTw = plan.getTimeWindows().isEmpty() ? TimeWindow.newInstance(0, 24*3600) : plan.getTimeWindows().getFirst();
			for (ReceiverOrder order : plan.getReceiverOrders()){
				int capacity = 0;
				double serviceTime = 0;
				for (Order productOrder : order.getReceiverProductOrders()){
					capacity += (int) Math.max(1, Math.round(productOrder.getDailyOrderQuantity()*productOrder.getProduct().getProductType().getRequiredCapacity()));
					serviceTime += productOrder.getServiceDuration();
				}
				var builder = LspShipmentUtils.LspShipmentBuilder.newInstance(Id.create("lspShipment_" + counter++, LspShipment.class));
				builder.setFromLinkId(order.getReceiverProductOrders().iterator().next().getProduct().getProductType().getOriginLinkId());
				builder.setToLinkId(receiver.getLinkId());
				builder.setCapacityDemand(capacity);
				builder.setDeliveryServiceTime(serviceTime);
				builder.setPickupServiceTime(0.0);
				builder.setStartTimeWindow(TimeWindow.newInstance(0, deliveryTw.getStart()));
				builder.setEndTimeWindow(deliveryTw);
				shipments.add(builder.build());
			}
		}
		return shipments;
	}
}
