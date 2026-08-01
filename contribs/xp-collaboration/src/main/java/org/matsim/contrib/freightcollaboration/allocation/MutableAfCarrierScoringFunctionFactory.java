package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.run.ScoringFunctionFactoryUsecase;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndCarrier;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.usecases.chessboard.CarrierScoringFunctionFactoryImpl;
import org.matsim.freight.receiver.Receiver;

import java.util.Set;

/** Carrier scoring used only by mutable-allocation-factor runs. */
public final class MutableAfCarrierScoringFunctionFactory implements CarrierPsimScoringFunctionFactory {

	@Inject
	private Network network;

	@Inject
	private FreightCollaborators freightCollaborators;

	@Inject
	private CollaborationDataStore dataStore;

	@Inject
	private FreightCollaborationConfigGroup freightConfig;

	public MutableAfCarrierScoringFunctionFactory() {
	}

	MutableAfCarrierScoringFunctionFactory(Network network, FreightCollaborators freightCollaborators,
			CollaborationDataStore dataStore, FreightCollaborationConfigGroup freightConfig) {
		this.network = network;
		this.freightCollaborators = freightCollaborators;
		this.dataStore = dataStore;
		this.freightConfig = freightConfig;
	}

	@Override
	public ScoringFunction createScoringFunction(Carrier carrier) {
		SumScoringFunction scoring = baseCostScoring(carrier);
		double feePerReceiver = freightConfig != null ? freightConfig.CARRIER_CHARGED_FEE : 200.0;
		scoring.addScoringFunction(new ChargingReceiverScoring(carrier, freightCollaborators, feePerReceiver));
		scoring.addScoringFunction(new SignedDistributorTransferScoring(carrier, dataStore));
		return scoring;
	}

	@Override
	public ScoringFunction createPsimScoringFunction(Carrier carrier,
			FreightCollaborationConfigGroup.PsimScoringMode mode) {
		SumScoringFunction scoring = baseCostScoring(carrier);
		if (mode == FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES) {
			double feePerReceiver = freightConfig != null ? freightConfig.CARRIER_CHARGED_FEE : 200.0;
			scoring.addScoringFunction(new ChargingReceiverScoring(carrier, freightCollaborators, feePerReceiver));
		}
		return scoring;
	}

	private record ChargingReceiverScoring(Carrier carrier, FreightCollaborators collaborators,
			double feePerReceiver) implements SumScoringFunction.BasicScoring {

		@Override
		public void finish() {
		}

		@Override
		public double getScore() {
			Set<FreightCollaborator<Receiver>> receivers =
				LinkReceiverAndCarrier.findLinkedReceivers(carrier, collaborators);
			return receivers.size() * feePerReceiver;
		}
	}

	private SumScoringFunction baseCostScoring(Carrier carrier) {
		SumScoringFunction scoring = new SumScoringFunction();
		scoring.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleDriversLegScoring(carrier, network));
		scoring.addScoringFunction(new CarrierScoringFunctionFactoryImpl.SimpleVehicleEmploymentScoring(carrier));
		scoring.addScoringFunction(
			new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.SimpleDriversActivityScoring());
		return scoring;
	}

	public static final class SignedDistributorTransferScoring implements SumScoringFunction.BasicScoring {
		private final Carrier carrier;
		private final CollaborationDataStore dataStore;

		public SignedDistributorTransferScoring(Carrier carrier, CollaborationDataStore dataStore) {
			this.carrier = carrier;
			this.dataStore = dataStore;
		}

		@Override
		public void finish() {
		}

		@Override
		public double getScore() {
			return -dataStore.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, carrier.getId());
		}
	}
}
