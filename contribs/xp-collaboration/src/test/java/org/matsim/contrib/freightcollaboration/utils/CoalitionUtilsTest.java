package org.matsim.contrib.freightcollaboration.utils;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Order;
import org.matsim.freight.receiver.ProductType;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverProduct;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalitionUtilsTest {

	@Test
	void detectsTimeWindowRelaxationButNotContraction() {
		ReceiverPlan original = plan(Map.of("carrier", 10.0), TimeWindow.newInstance(100, 200));
		CoalitionUtils.ReceiverDelta extended = CoalitionUtils.computeReceiverDelta(original,
			plan(Map.of("carrier", 10.0), TimeWindow.newInstance(90, 220)));
		CoalitionUtils.ReceiverDelta contracted = CoalitionUtils.computeReceiverDelta(original,
			plan(Map.of("carrier", 10.0), TimeWindow.newInstance(110, 190)));

		assertTrue(extended.twExtended());
		assertTrue(extended.hasChange());
		assertEquals(Set.of(Id.create("carrier", Carrier.class)), extended.carriers());
		assertFalse(contracted.twExtended());
		assertFalse(contracted.hasChange());
	}

	@Test
	void detectsServiceContractionAndIdentifiesOnlyAffectedCarrier() {
		ReceiverPlan original = plan(Map.of("a", 10.0, "b", 20.0), TimeWindow.newInstance(100, 200));
		CoalitionUtils.ReceiverDelta contracted = CoalitionUtils.computeReceiverDelta(original,
			plan(Map.of("a", 10.0, "b", 15.0), TimeWindow.newInstance(100, 200)));
		CoalitionUtils.ReceiverDelta increased = CoalitionUtils.computeReceiverDelta(original,
			plan(Map.of("a", 11.0, "b", 21.0), TimeWindow.newInstance(100, 200)));

		assertTrue(contracted.serviceContracted());
		assertEquals(Set.of(Id.create("b", Carrier.class)), contracted.carriers());
		assertFalse(increased.serviceContracted());
		assertFalse(increased.hasChange());
	}

	@Test
	void detectsAddedAndDeletedCarrierOrders() {
		ReceiverPlan onlyA = plan(Map.of("a", 10.0), TimeWindow.newInstance(100, 200));
		ReceiverPlan aAndB = plan(Map.of("a", 10.0, "b", 10.0), TimeWindow.newInstance(100, 200));

		CoalitionUtils.ReceiverDelta added = CoalitionUtils.computeReceiverDelta(onlyA, aAndB);
		CoalitionUtils.ReceiverDelta deleted = CoalitionUtils.computeReceiverDelta(aAndB, onlyA);

		assertTrue(added.ordersChanged());
		assertEquals(Set.of(Id.create("b", Carrier.class)), added.carriers());
		assertTrue(deleted.ordersChanged());
		assertEquals(Set.of(Id.create("b", Carrier.class)), deleted.carriers());
	}

	@Test
	void marksReceiverStatusAndPartnersFromDelta() {
		Receiver receiver = ReceiverUtils.newInstance(Id.create("receiver", Receiver.class));
		ReceiverPlan original = planForReceiver(receiver, Map.of("carrier", 10.0),
			TimeWindow.newInstance(100, 200));
		ReceiverPlan relaxed = planForReceiver(receiver, Map.of("carrier", 5.0),
			TimeWindow.newInstance(100, 200));
		receiver.setSelectedPlan(relaxed);
		FreightCollaborator<Receiver> collaborator = FreightCollaboratorFactory.createCollaborator(receiver);
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(collaborator);
		Map<Id<?>, BasicPlan> plans = Map.of(receiver.getId(), original);
		CollaborationDataStore store =
			new CollaborationDataStore(Map.of(CollaboratorRole.RECEIVER, plans));

		CoalitionUtils.markCollaboratedReceiver(collaborators, store);
		assertTrue(collaborator.getCollaborationStatus());
		assertEquals(Set.of(Id.create("carrier", Carrier.class)), collaborator.getCollaborationPartners());
		assertEquals(Map.of(receiver.getId(), collaborator), CoalitionUtils.getCollaboratedReceivers(collaborators));

		receiver.setSelectedPlan(original.createCopy());
		CoalitionUtils.markCollaboratedReceiver(collaborators, store);
		assertFalse(collaborator.getCollaborationStatus());
		assertTrue(collaborator.getCollaborationPartners().isEmpty());
	}

	@Test
	void rejectsNullPlansAndMissingOriginalPlanStore() {
		ReceiverPlan plan = plan(Map.of("carrier", 10.0), TimeWindow.newInstance(100, 200));
		assertThrows(NullPointerException.class, () -> CoalitionUtils.computeReceiverDelta(null, plan));
		assertThrows(NullPointerException.class, () -> CoalitionUtils.computeReceiverDelta(plan, null));
		assertThrows(IllegalStateException.class,
			() -> CoalitionUtils.markCollaboratedReceiver(new FreightCollaborators(),
				new CollaborationDataStore(Map.of())));
	}

	private static ReceiverPlan plan(Map<String, Double> carrierServiceTimes, TimeWindow timeWindow) {
		return planForReceiver(ReceiverUtils.newInstance(Id.create("receiver", Receiver.class)),
			carrierServiceTimes, timeWindow);
	}

	private static ReceiverPlan planForReceiver(Receiver receiver, Map<String, Double> carrierServiceTimes,
												TimeWindow timeWindow) {
		List<ReceiverOrder> receiverOrders = new ArrayList<>();
		Receivers productTypes = ReceiverUtils.createReceivers();
		for (Map.Entry<String, Double> entry : new LinkedHashMap<>(carrierServiceTimes).entrySet()) {
			Id<ProductType> productTypeId = Id.create("product-" + entry.getKey(), ProductType.class);
			ReceiverProduct product = receiver.getProduct(productTypeId);
			if (product == null) {
				ProductType productType = ReceiverUtils.createAndGetProductType(
					productTypes, productTypeId, Id.createLinkId("origin"));
				productType.setRequiredCapacity(1);
				product = ReceiverProduct.Builder.newInstance()
					.setProductType(productType)
					.setReorderingPolicy(ReceiverUtils.createSSReorderPolicy(1, 1))
					.build();
				receiver.addProduct(product);
			}
			Order order = Order.Builder.newInstance(
					Id.create("order-" + entry.getKey(), Order.class), receiver, product)
				.setServiceTime(entry.getValue())
				.buildWithCalculatedOrderQuantity();
			receiverOrders.add(new ReceiverOrder(receiver.getId(), List.of(order),
				Id.create(entry.getKey(), Carrier.class)));
		}

		ReceiverPlan.Builder builder = ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(timeWindow);
		receiverOrders.forEach(builder::addReceiverOrder);
		return builder.build();
	}
}
