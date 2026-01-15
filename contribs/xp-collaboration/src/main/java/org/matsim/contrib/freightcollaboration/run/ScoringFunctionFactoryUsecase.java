package org.matsim.contrib.freightcollaboration.run;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.FreightActivity;
import org.matsim.freight.carriers.usecases.chessboard.CarrierScoringFunctionFactoryImpl;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPCarrierResource;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPResource;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.shipment.LspShipmentPlan;
import org.matsim.freight.receiver.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScoringFunctionFactoryUsecase {

	public static class CarrierScoringFunctionFactoryUsecase implements CarrierScoringFunctionFactory {

		private static final Logger logger = LogManager.getLogger(CarrierScoringFunctionFactoryUsecase.class);

		@Inject
		private Network network;

		@Inject
		FreightCollaborators freightCollaborators;

		@Inject
		CollaborationDataStore dataStore;

		@Inject
		FreightCollaborationConfigGroup freightConfig;

		@Override
		public ScoringFunction createScoringFunction(Carrier carrier) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
			// TODO: consider using a more advanced activity scoring that considering non-linear penalty for missed time windows
			sf.addScoringFunction(new SimpleDriversActivityScoring());
			double feePerReceiver = freightConfig != null ? freightConfig.CARRIER_CHARGED_FEE : 200.0;
			sf.addScoringFunction(new SimpleChargingReceiverScoring(carrier, freightCollaborators, feePerReceiver));
			sf.addScoringFunction(new retainCostSaving(carrier, dataStore));
			return sf;
		}

		public ScoringFunction createBasicCostScoringFunction(Carrier carrier) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
			sf.addScoringFunction(new SimpleDriversActivityScoring());
			return sf;
		}

		public static class SimpleDriversActivityScoring implements SumScoringFunction.BasicScoring, SumScoringFunction.ActivityScoring {

			private double score;
			private final double timeParameter = 0.008;
			private final double missedTimeWindowPenalty = 0.0278;  // 100euro per hour

			public SimpleDriversActivityScoring() {
				super();
			}

			@Override
			public void finish() {
			}

			@Override
			public double getScore() {
				return score;
			}

			@Override
			public void handleFirstActivity(Activity act) {
				handleActivity(act);
			}

			@Override
			public void handleActivity(Activity act) {
				if(act instanceof FreightActivity) {
					double actStartTime = act.getStartTime().seconds();

					TimeWindow tw = ((FreightActivity) act).getTimeWindow();
					if(actStartTime > tw.getEnd()){
						double penalty_score = (-1)*(actStartTime - tw.getEnd())*missedTimeWindowPenalty;
						if (!(penalty_score <= 0.0)) throw new AssertionError("penalty score must be negative");
						score += penalty_score;

					}
					double actTimeCosts = (act.getEndTime().seconds() -actStartTime)*timeParameter;
					if (!(actTimeCosts >= 0.0)) throw new AssertionError("actTimeCosts must be positive");
					score += actTimeCosts*(-1);
				}
			}

			@Override
			public void handleLastActivity(Activity act) {
				handleActivity(act);
			}

		}

		public static class SimpleChargingReceiverScoring implements SumScoringFunction.BasicScoring {

			private Carrier carrier;

			FreightCollaborators freightCollaborators;
			private final double feePerReceiver;

			public SimpleChargingReceiverScoring(Carrier carrier, FreightCollaborators freightCollaborators, double feePerReceiver) {
				super();
				this.carrier = carrier;
				this.freightCollaborators = freightCollaborators;
				this.feePerReceiver = feePerReceiver;
			}

			private double score = 0.0;

			@Override
			public void finish() {
				// Nothing to do here
			}

			@Override
			public double getScore() {
				// Get linked receivers for this carrier
				Set<FreightCollaborator<Receiver>> linkedReceivers = LinkReceiverAndCarrier.findLinkedReceivers(carrier, freightCollaborators);
				// Charge a fixed fee for each linked receiver
				score = linkedReceivers.size() * feePerReceiver;
				return score;
			}
		}

		static class retainCostSaving implements SumScoringFunction.BasicScoring {

			private Carrier carrier;
			private  CollaborationDataStore dataStore;

			public retainCostSaving(Carrier carrier, CollaborationDataStore dataStore) {
				super();
				this.carrier = carrier;
				this.dataStore = dataStore;
			}

			@Override
			public void finish() {

			}

			@Override
			public double getScore() {
				if (dataStore.getAllocatedValues() == null || dataStore.getAllocatedValues().isEmpty()) {
					return 0.0;
				}
				return dataStore.getAllocatedValues().getOrDefault(carrier.getId(), 0.0);
			}
		}

	}

	public static class ReceiverScoringFunctionFactoryUsecase implements ReceiverScoringFunctionFactory {

		private CollaborationDataStore collaborationDataStore;
		private final FreightCollaborationConfigGroup freightConfig;

		@Inject
		public ReceiverScoringFunctionFactoryUsecase(CollaborationDataStore collaborationDataStore, FreightCollaborationConfigGroup freightConfig) {
			this.collaborationDataStore = collaborationDataStore;
			this.freightConfig = freightConfig;
		}

		@Override
		public ScoringFunction createScoringFunction(Receiver receiver) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierToReceiverCostAllocation());
			double penaltyParam = freightConfig != null ? freightConfig.RECEIVER_RELAXATION_PENALTY : 0.01;
			sf.addScoringFunction(new ReceiverRelaxationPenalty(receiver, penaltyParam, collaborationDataStore));
			sf.addScoringFunction(new AllocationFromDistributor(receiver, collaborationDataStore));
			return sf;
		}

		static class CarrierToReceiverCostAllocation implements SumScoringFunction.MoneyScoring {

			private double moneyBalance = 0.0;

			@Override
			public void finish() {
			}

			@Override
			public double getScore() {
				return this.moneyBalance;
			}

			@Override
			public void addMoney(double amount) {
				this.moneyBalance += amount;
			}
		}

		static class ReceiverRelaxationPenalty implements SumScoringFunction.BasicScoring {

			Receiver receiver;
			CollaborationDataStore collaborationDataStore;
			double penalty;

			ReceiverRelaxationPenalty(Receiver receiver, double penaltyParam, CollaborationDataStore collaborationDataStore) {
				this.collaborationDataStore = collaborationDataStore;
				this.receiver = receiver;
				this.penalty = penaltyParam;
			}

			@Override
			public void finish() {

			}

			@Override
			public double getScore() {
				return -this.penalty * findChangedOrders();
			}

			private double findChangedOrders() {
				// Get the current receiver plans TW and order service durations
				List<TimeWindow> thisTWs = receiver.getSelectedPlan().getTimeWindows();
				List<Double> thisServiceDurations = receiver.getSelectedPlan().getReceiverOrders().stream()
						.flatMap(ro -> ro.getReceiverProductOrders().stream())
						.map(Order::getServiceDuration)
						.toList();
				// Get the original receiver plans TW and order service durations from the data store
				ReceiverPlan originalReceiverPlan = (ReceiverPlan) collaborationDataStore.getOriginalPlans()
					.get(CollaboratorRole.RECEIVER)
					.get(receiver.getId());
				List<TimeWindow> originalTWs = originalReceiverPlan.getTimeWindows();
				List<Double> originalServiceDurations = originalReceiverPlan.getReceiverOrders().stream()
						.flatMap(ro -> ro.getReceiverProductOrders().stream())
						.map(Order::getServiceDuration)
						.toList();
				// Compare the two plans and identify changes
				// changed TWs
				double relaxationAmountOfTW = 0.0;
				for (int i = 0; i < thisTWs.size(); i++) {
					if (!thisTWs.get(i).equals(originalTWs.get(i))) {
						// the time window has been changed, then calculate how much it has changed (UNIT:SECOND)
						double startRelaxation = originalTWs.get(i).getStart() > thisTWs.get(i).getStart()
							? originalTWs.get(i).getStart() - thisTWs.get(i).getStart()
							: 0.0;
						double endRelaxation = thisTWs.get(i).getEnd() > originalTWs.get(i).getEnd()
							? thisTWs.get(i).getEnd() - originalTWs.get(i).getEnd()
							: 0.0;
						relaxationAmountOfTW += startRelaxation + endRelaxation;
					}
				}
				// changed service durations
				double relaxationAmountOfServiceDuration = 0.0;
				for (int i = 0; i < thisServiceDurations.size(); i++) {
					if (!thisServiceDurations.get(i).equals(originalServiceDurations.get(i))) {
						// the service duration has been changed, then calculate how much it has changed (UNIT:SECOND)
						double durationRelaxation = thisServiceDurations.get(i) < originalServiceDurations.get(i)?
							originalServiceDurations.get(i) - thisServiceDurations.get(i)
							: 0.0;
						relaxationAmountOfServiceDuration += durationRelaxation;
					}
				}
				return relaxationAmountOfTW + relaxationAmountOfServiceDuration;
			}

		}

		static class AllocationFromDistributor implements SumScoringFunction.BasicScoring {

			Receiver receiver;
			CollaborationDataStore collaborationDataStore;

			AllocationFromDistributor(Receiver receiver, CollaborationDataStore collaborationDataStore) {
				super();
				this.collaborationDataStore = collaborationDataStore;
				this.receiver = receiver;
			}

			@Override
			public void finish() {

			}

			@Override
			public double getScore() {
				if (collaborationDataStore.getAllocatedValues() == null || collaborationDataStore.getAllocatedValues().isEmpty()) {
					return 0.0;
				}
				return collaborationDataStore.getAllocatedValues().getOrDefault(receiver.getId(), 0.0);
			}
		}

	}

	public static class LSPScoringFunctionFactory{

		private static final Logger logger = LogManager.getLogger(LSPScoringFunctionFactory.class);

		public static double scoreNonDeliveredShipments(LSP lsp) {
			double score = 0.0;

			int undeliveredShipmentCount = 0;
			// Get all carrier types for this LSP
			Set<String> lspCarrierTypes = new HashSet<>();
			lsp.getResources().forEach(resource -> {
				if (resource instanceof LSPCarrierResource carrierResource) {
					ResourceImplementationUtils.CARRIER_TYPE type = (ResourceImplementationUtils.CARRIER_TYPE) carrierResource.getCarrier().getAttributes().getAttribute("carrierType");
					lspCarrierTypes.add(type.name());

				}
			});

			LSPPlan lspPlan = lsp.getSelectedPlan();
			var lspShipmentPlans = lspPlan.getShipmentPlans();
			for (LspShipmentPlan lspShipmentPlan : lspShipmentPlans) {
				// Get all resource ids used in this shipment plan
				Set<String> shipmentPlanResourceNames= new HashSet<>();
				lspShipmentPlan.getPlanElements().values().forEach(planElement -> {
					shipmentPlanResourceNames.add(planElement.getResourceId().toString());
				});
				// Check if all carrier types are covered
				if (!shipmentPlanResourceNames.containsAll(lspCarrierTypes)){
					// This indicates that this shipment was not fully delivered/handled
					undeliveredShipmentCount++;
				}
			}

			if (undeliveredShipmentCount > 0) {
				logger.error(
					"LspPlan contains undelivered shipments, "
						+ "probably due to time window violations.");
				score -= 500 * undeliveredShipmentCount;
			}
			return score;
		}
	}

	public static class CarrierScoringFunctionFactoryForLspReceiverCollab implements CarrierScoringFunctionFactory {

		private static final Logger logger = LogManager.getLogger(CarrierScoringFunctionFactoryForLspReceiverCollab.class);

		@Inject
		private Network network;

		@Inject
		FreightCollaborators freightCollaborators;

		@Inject
		CollaborationDataStore dataStore;

		@Inject
		FreightCollaborationConfigGroup freightConfig;

		@Override
		public ScoringFunction createScoringFunction(Carrier carrier) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversActivityScoring());
			double feePerReceiver = freightConfig != null ? freightConfig.CARRIER_CHARGED_FEE : 200.0;
			sf.addScoringFunction(new SimpleChargingReceiverScoring(carrier, freightCollaborators, feePerReceiver));
			sf.addScoringFunction(new missingDeliveryPenalty(carrier, 500.0));
			return sf;
		}

		public ScoringFunction createBasicCostScoringFunction(Carrier carrier) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversActivityScoring());
			return sf;
		}

		public static class SimpleChargingReceiverScoring implements SumScoringFunction.BasicScoring {

			private Carrier carrier;

			FreightCollaborators freightCollaborators;
			private final double feePerReceiver;

			public SimpleChargingReceiverScoring(Carrier carrier, FreightCollaborators freightCollaborators, double feePerReceiver) {
				super();
				this.carrier = carrier;
				this.freightCollaborators = freightCollaborators;
				this.feePerReceiver = feePerReceiver;
			}

			private double score = 0.0;

			@Override
			public void finish() {
				// Nothing to do here
			}

			@Override
			public double getScore() {
				// Get linked receivers for this carrier
				Set<FreightCollaborator<Receiver>> linkedReceivers = LinkReceiverAndCarrier.findLinkedReceivers(carrier, freightCollaborators);
				// Charge a fixed fee for each linked receiver
				score = linkedReceivers.size() * feePerReceiver;
				return score;
			}
		}

		public static class missingDeliveryPenalty implements SumScoringFunction.BasicScoring {

			private Carrier carrier;
			private final double penaltyPerMissingDelivery;

			public missingDeliveryPenalty(Carrier carrier, double penaltyPerMissingDelivery) {
				super();
				this.carrier = carrier;
				this.penaltyPerMissingDelivery = penaltyPerMissingDelivery;
			}

			@Override
			public void finish() {
				// Nothing to do here
			}

			@Override
			public double getScore() {
				// Here you would implement the logic to determine the number of missing deliveries
				int numberOfMissingDeliveries = 0; // Placeholder for actual logic
				// Get all the shipment IDs for this carrier
				Set<Id<CarrierShipment>> shipmentIds = carrier.getShipments().keySet();
				// Check the selected plan and each tour to see which shipments were delivered
				CarrierPlan selectedPlan = carrier.getSelectedPlan();
				Set<Id<CarrierShipment>> deliveredShipmentIds = new HashSet<>();
				// For-loop through all tours and tour elements to find delivered shipments
				selectedPlan.getScheduledTours().forEach(tour -> {
					tour.getTour().getTourElements().forEach(tourElement -> {
						if (tourElement instanceof Tour.TourActivity){
							Tour.TourActivity activity = (Tour.TourActivity) tourElement;
							if (activity.getActivityType() == CarrierConstants.DELIVERY){
								Tour.Delivery deliveryActivity = (Tour.Delivery) activity;
								Id<CarrierShipment> deliveredShipmentId = deliveryActivity.getShipment().getId();
								deliveredShipmentIds.add(deliveredShipmentId);
							}
						}
					});
				});
				// Determine the number of missing deliveries
				for (Id<CarrierShipment> shipmentId : shipmentIds) {
					if (!deliveredShipmentIds.contains(shipmentId)) {
						numberOfMissingDeliveries++;
					}
				}
				return -numberOfMissingDeliveries * penaltyPerMissingDelivery;
			}
		}
	}

}
