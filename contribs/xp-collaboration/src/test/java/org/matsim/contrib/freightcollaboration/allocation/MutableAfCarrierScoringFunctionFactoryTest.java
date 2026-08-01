package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaboratorFactory;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.receiver.Receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutableAfCarrierScoringFunctionFactoryTest {

	@Test
	void normalScoringChargesFeesAndSignedTransferWhilePsimExcludesTheTransfer() {
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		Receiver receiver = FreightCollaborationTestFixtures.receiverWithOrder("receiver", "carrier", 600.0,
			TimeWindow.newInstance(8 * 3600.0, 10 * 3600.0));
		FreightCollaborators collaborators = new FreightCollaborators();
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(carrier));
		collaborators.addFreightCollaborator(FreightCollaboratorFactory.createCollaborator(receiver));
		CollaborationDataStore store = FreightCollaborationTestFixtures.emptyDataStore();
		store.recordDistributorPlayerTransfer(carrierKey(carrier), 3.0);
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		config.CARRIER_CHARGED_FEE = 50.0;
		MutableAfCarrierScoringFunctionFactory factory = new MutableAfCarrierScoringFunctionFactory(
			NetworkUtils.createNetwork(), collaborators, store, config);

		assertEquals(47.0, score(factory.createScoringFunction(carrier)), 1e-12);
		assertEquals(0.0, score(factory.createPsimScoringFunction(carrier,
			FreightCollaborationConfigGroup.PsimScoringMode.BASIC_COST)), 1e-12);
		assertEquals(50.0, score(factory.createPsimScoringFunction(carrier,
			FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES)), 1e-12);
	}

	private static CollaboratorKey carrierKey(Carrier carrier) {
		return new CollaboratorKey(CollaboratorRole.CARRIER, carrier.getId());
	}

	private static double score(ScoringFunction scoring) {
		scoring.finish();
		return scoring.getScore();
	}
}
