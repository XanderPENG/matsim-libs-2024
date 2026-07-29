package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.receiver.Order;
import org.matsim.freight.receiver.ProductType;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.freight.receiver.ReceiverProduct;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.Receivers;

import java.util.List;
import java.util.Map;

public final class FreightCollaborationTestFixtures {
	private FreightCollaborationTestFixtures() {
	}

	public static Carrier carrier(String id) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create(id, Carrier.class));
		CarrierPlan plan = new CarrierPlan(carrier, List.of());
		carrier.addPlan(plan);
		carrier.setSelectedPlan(plan);
		return carrier;
	}

	public static Receiver receiver(String id) {
		Receiver receiver = ReceiverUtils.newInstance(Id.create(id, Receiver.class));
		ReceiverPlan plan = ReceiverPlan.Builder.newInstance(receiver, true).build();
		receiver.addPlan(plan);
		receiver.setSelectedPlan(plan);
		return receiver;
	}

	public static Receiver receiverWithOrder(String receiverId, String carrierId,
			double serviceTime, TimeWindow timeWindow) {
		Receiver receiver = ReceiverUtils.newInstance(Id.create(receiverId, Receiver.class));
		receiver.setLinkId(Id.createLinkId("receiver-link"));
		ReceiverPlan plan = receiverPlan(receiver, carrierId, serviceTime, timeWindow);
		receiver.addPlan(plan);
		receiver.setSelectedPlan(plan);
		return receiver;
	}

	public static ReceiverPlan receiverPlan(Receiver receiver, String carrierId,
			double serviceTime, TimeWindow timeWindow) {
		Id<ProductType> productTypeId = Id.create("product-" + carrierId, ProductType.class);
		ReceiverProduct product = receiver.getProduct(productTypeId);
		if (product == null) {
			Receivers productTypes = ReceiverUtils.createReceivers();
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
				Id.create("order-" + carrierId, Order.class), receiver, product)
			.setServiceTime(serviceTime)
			.buildWithCalculatedOrderQuantity();
		ReceiverOrder receiverOrder = new ReceiverOrder(receiver.getId(), List.of(order),
			Id.create(carrierId, Carrier.class));
		return ReceiverPlan.Builder.newInstance(receiver, true)
			.addTimeWindow(timeWindow)
			.addReceiverOrder(receiverOrder)
			.build();
	}

	public static LSP lsp(String id) {
		LSPPlan plan = LSPUtils.createLSPPlan();
		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create(id, LSP.class))
			.setLogisticChainScheduler(LSPUtils.createForwardLogisticChainScheduler())
			.setInitialPlan(plan)
			.build();
		plan.setLSP(lsp);
		return lsp;
	}

	public static FreightCollaborator<Carrier> carrierCollaborator(String id) {
		return FreightCollaboratorFactory.createCollaborator(carrier(id));
	}

	public static FreightCollaborator<Receiver> receiverCollaborator(String id) {
		return FreightCollaboratorFactory.createCollaborator(receiver(id));
	}

	public static FreightCollaborator<LSP> lspCollaborator(String id) {
		return FreightCollaboratorFactory.createCollaborator(lsp(id));
	}

	public static MutableFreightCoalition carrierReceiverCoalition(String carrierId, String... receiverIds) {
		MutableFreightCoalition coalition = new MutableFreightCoalition(CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(carrierCollaborator(carrierId));
		for (String receiverId : receiverIds) {
			coalition.addCollaborator(receiverCollaborator(receiverId));
		}
		return coalition;
	}

	public static CollaborationDataStore emptyDataStore() {
		return new CollaborationDataStore(Map.of());
	}
}
