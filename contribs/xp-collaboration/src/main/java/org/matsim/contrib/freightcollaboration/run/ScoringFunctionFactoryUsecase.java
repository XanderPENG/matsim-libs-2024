package org.matsim.contrib.freightcollaboration.run;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.usecases.chessboard.CarrierScoringFunctionFactoryImpl;
import org.matsim.freight.receiver.*;

import java.util.List;
import java.util.Set;

public class ScoringFunctionFactoryUsecase {

	public static class CarrierScoringFunctionFactoryUsecase implements CarrierScoringFunctionFactory {
		@Inject
		private Network network;

		@Inject
		FreightCollaborators freightCollaborators;

		@Inject
		CollaborationDataStore dataStore;

		@Override
		public ScoringFunction createScoringFunction(Carrier carrier) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
			sf.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversActivityScoring());
			sf.addScoringFunction(new SimpleChargingReceiverScoring(carrier, freightCollaborators));
			sf.addScoringFunction(new retainCostSaving(carrier, dataStore));
			return sf;
		}

		public static class SimpleChargingReceiverScoring implements SumScoringFunction.BasicScoring {

			private Carrier carrier;

			FreightCollaborators freightCollaborators;

			public SimpleChargingReceiverScoring(Carrier carrier, FreightCollaborators freightCollaborators) {
				super();
				this.carrier = carrier;
				this.freightCollaborators = freightCollaborators;
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
				double feePerReceiver = 200.0; //
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

		@Inject
		public ReceiverScoringFunctionFactoryUsecase(CollaborationDataStore collaborationDataStore) {
			this.collaborationDataStore = collaborationDataStore;
		}

		@Override
		public ScoringFunction createScoringFunction(Receiver receiver) {
			SumScoringFunction sf = new SumScoringFunction();
			sf.addScoringFunction(new CarrierToReceiverCostAllocation());
			sf.addScoringFunction(new ReceiverRelaxationPenalty(receiver, (double) 0.001, collaborationDataStore));
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

}
