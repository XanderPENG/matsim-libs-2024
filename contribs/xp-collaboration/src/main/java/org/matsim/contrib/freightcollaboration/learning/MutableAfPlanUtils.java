package org.matsim.contrib.freightcollaboration.learning;

import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;
import org.matsim.freight.receiver.ReceiverOrder;
import org.matsim.freight.receiver.ReceiverPlan;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deep-copy and behavioural-signature helpers shared by the mutable-AF components. */
public final class MutableAfPlanUtils {

	public static final String RECEIVER_FACTOR_INDEX = "mutableAf.gridIndex";
	public static final String RECEIVER_CONTEXT_GENERATION = "mutableAf.contextGeneration";
	public static final String RECEIVER_OUTSIDE_OPTION = "mutableAf.outsideOption";
	public static final String RECEIVER_PENDING_EVALUATION = "mutableAf.pendingEvaluation";
	public static final String RECEIVER_SOURCE_FACTOR_INDEX = "mutableAf.sourceFactorIndex";
	public static final String CARRIER_ROUTE_PROFILE = "mutableAf.routeProfile";

	private MutableAfPlanUtils() {
	}

	public static ReceiverPlan copyReceiverPlan(ReceiverPlan source, boolean copyScore) {
		Objects.requireNonNull(source, "source");
		ReceiverPlan copy = source.createCopy();
		AttributesUtils.copyTo(source.getAttributes(), copy.getAttributes());
		copy.setScore(copyScore ? source.getScore() : null);
		copy.setSelected(false);
		return copy;
	}

	public static CarrierPlan copyCarrierPlan(CarrierPlan source, boolean copyScore) {
		Objects.requireNonNull(source, "source");
		CarrierPlan copy = new CarrierPlan(source.getCarrier(), copyTours(source.getScheduledTours()));
		AttributesUtils.copyTo(source.getAttributes(), copy.getAttributes());
		copy.setScore(copyScore ? source.getScore() : null);
		return copy;
	}

	public static List<ScheduledTour> copyTours(Iterable<ScheduledTour> tours) {
		List<ScheduledTour> result = new ArrayList<>();
		for (ScheduledTour tour : tours) {
			result.add(ScheduledTour.newInstance(tour.getTour().duplicate(), tour.getVehicle(), tour.getDeparture()));
		}
		return result;
	}

	public static void replaceTours(CarrierPlan target, Iterable<ScheduledTour> source) {
		target.getScheduledTours().clear();
		target.getScheduledTours().addAll(copyTours(source));
	}

	public static String receiverPlanSignature(ReceiverPlan plan) {
		StringBuilder signature = new StringBuilder();
		plan.getTimeWindows().stream()
			.sorted(Comparator.comparingDouble(TimeWindow::getStart).thenComparingDouble(TimeWindow::getEnd))
			.forEach(tw -> signature.append(tw.getStart()).append('-').append(tw.getEnd()).append(';'));
		plan.getReceiverOrders().stream()
			.sorted(Comparator.comparing(order -> order.getCarrierId().toString()))
			.forEach(order -> appendOrder(signature, order));
		return signature.toString();
	}

	public static String selectedReceiverProfile(Carrier carrier, Iterable<Receiver> receivers) {
		List<String> entries = new ArrayList<>();
		for (Receiver receiver : receivers) {
			ReceiverPlan selected = receiver.getSelectedPlan();
			if (selected == null || !ordersFrom(selected, carrier)) {
				continue;
			}
			entries.add(receiver.getId() + "=" + receiverPlanSignature(selected));
		}
		entries.sort(String::compareTo);
		return String.join("|", entries);
	}

	public static boolean ordersFrom(ReceiverPlan plan, Carrier carrier) {
		return plan.getReceiverOrders().stream().anyMatch(order -> order.getCarrierId().equals(carrier.getId()));
	}

	public static boolean isPendingEvaluation(ReceiverPlan plan) {
		return Boolean.TRUE.equals(plan.getAttributes().getAttribute(RECEIVER_PENDING_EVALUATION));
	}

	public static void markPendingEvaluation(ReceiverPlan plan, int sourceFactorIndex) {
		Objects.requireNonNull(plan, "plan");
		plan.getAttributes().putAttribute(RECEIVER_PENDING_EVALUATION, true);
		plan.getAttributes().putAttribute(RECEIVER_SOURCE_FACTOR_INDEX, sourceFactorIndex);
	}

	public static void clearPendingEvaluation(ReceiverPlan plan) {
		Objects.requireNonNull(plan, "plan");
		plan.getAttributes().removeAttribute(RECEIVER_PENDING_EVALUATION);
		plan.getAttributes().removeAttribute(RECEIVER_SOURCE_FACTOR_INDEX);
	}

	private static void appendOrder(StringBuilder signature, ReceiverOrder receiverOrder) {
		signature.append(receiverOrder.getCarrierId()).append('[');
		receiverOrder.getReceiverProductOrders().stream()
			.sorted(Comparator.comparing(order -> order.getId().toString()))
			.forEach(order -> signature.append(order.getId()).append(':')
				.append(order.getDailyOrderQuantity()).append(':')
				.append(order.getServiceDuration()).append(','));
		signature.append(']');
	}
}
