package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.config.MutableAllocationFactorConfigGroup;
import org.matsim.contrib.freightcollaboration.run.ScoringFunctionFactoryUsecase;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactorPlanStrategy;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.receiver.Receiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MutableAllocationFactorLifecycleIT {

	@Test
	void mutationChangesNextAllocationAndBothCarrierAndReceiverScores() {
		FreightCollaborationConfigGroup freightConfig = new FreightCollaborationConfigGroup();
		freightConfig.setAllocationModelString(AllocationModels.SHAPLEY.name());
		freightConfig.setAllocationStrategyString(AllocationValueTypes.COST_SAVINGS.name());
		freightConfig.setParallelism(1);
		MutableAllocationFactorConfigGroup mutableConfig = new MutableAllocationFactorConfigGroup();
		mutableConfig.setInitialAllocationFactor(0.0);
		Config config = ConfigUtils.createConfig(freightConfig, mutableConfig);
		Scenario scenario = ScenarioUtils.createScenario(config);
		MutableFreightCoalition coalition =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		Carrier carrier = (Carrier) coalition.getCollaboratorsSetByRole(CollaboratorRole.CARRIER)
			.iterator().next().getDelegate();
		Receiver receiver = (Receiver) coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER)
			.iterator().next().getDelegate();
		carrier.getSelectedPlan().setScore(10.0);
		CarrierAllocationFactor.set(carrier.getSelectedPlan(), 0.0, mutableConfig);
		Map<Id<?>, ? extends BasicPlan> originalReceiverPlans =
			Map.of(receiver.getId(), receiver.getSelectedPlan().createCopy());
		CollaborationDataStore store = new CollaborationDataStore(
			Map.of(CollaboratorRole.RECEIVER, originalReceiverPlans));
		FreightCollaborators collaborators = new FreightCollaborators(coalition.getCollaboratorsSet());

		runEngine(config, scenario, coalition, collaborators, store);
		assertEquals(0.0, receiverScore(receiver, store, freightConfig), 1e-12);
		assertEquals(0.0, carrierTransferScore(carrier, store), 1e-12);

		new CarrierAllocationFactorPlanStrategy(owner -> owner.getSelectedPlan(), mutableConfig).run(carrier);
		assertEquals(0.1, CarrierAllocationFactor.require(carrier.getSelectedPlan(), mutableConfig));
		assertNull(carrier.getSelectedPlan().getScore());

		store.reset();
		runEngine(config, scenario, coalition, collaborators, store);
		assertEquals(1.0, receiverScore(receiver, store, freightConfig), 1e-12);
		assertEquals(-1.0, carrierTransferScore(carrier, store), 1e-12);
		assertEquals(0.1, store.getAppliedAllocationFactors().get(coalition), 1e-12);
	}

	private static void runEngine(Config config, Scenario scenario, MutableFreightCoalition coalition,
			FreightCollaborators collaborators, CollaborationDataStore store) {
		new FreightCollaborationEngine(config, scenario, collaborators, store, List.of(coalition), null, null,
			() -> new FreightPseudoSimulator((distributors, players, subset) -> subset.size() * 10.0),
			ignored -> Executors.newSingleThreadExecutor()).runCollaboration();
	}

	private static double receiverScore(Receiver receiver, CollaborationDataStore store,
			FreightCollaborationConfigGroup freightConfig) {
		return new ScoringFunctionFactoryUsecase.ReceiverScoringFunctionFactoryUsecase(store, freightConfig)
			.createScoringFunction(receiver).getScore();
	}

	private static double carrierTransferScore(Carrier carrier, CollaborationDataStore store) {
		return new MutableAfCarrierScoringFunctionFactory.SignedDistributorTransferScoring(carrier, store)
			.getScore();
	}
}
