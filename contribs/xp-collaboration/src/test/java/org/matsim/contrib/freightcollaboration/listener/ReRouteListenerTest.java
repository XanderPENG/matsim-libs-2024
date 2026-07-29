package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPPlan;
import org.matsim.freight.logistics.LSPResource;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.logistics.LogisticChain;
import org.matsim.freight.logistics.LogisticChainElement;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReRouteListenerTest {

	@Test
	void appliesPrototypePlanToOriginalResourcesWithoutReplacingLsp() {
		Scenario scenario = scenario();
		LSP original = hubLsp(scenario, "hub", "element-original");
		LSP replanned = hubLsp(scenario, "hub", "element-new");
		LSPPlan oldPlan = original.getSelectedPlan();
		LSPResource originalResource = original.getResources().iterator().next();
		ReRouteListener listener = new ReRouteListener();

		listener.applyReplanInPlace(original, replanned);

		assertNotSame(oldPlan, original.getSelectedPlan());
		assertNotSame(replanned.getSelectedPlan(), original.getSelectedPlan());
		assertEquals(1, original.getPlans().size());
		LogisticChainElement transplanted = original.getSelectedPlan().getLogisticChains()
			.iterator().next().getLogisticChainElements().iterator().next();
		assertSame(originalResource, transplanted.getResource());
		assertSame(transplanted, listener.findFirstElement(
			original.getSelectedPlan().getLogisticChains().iterator().next()));
	}

	@Test
	void missingOriginalResourceAndUnsupportedConfiguredTypeFailClearly() {
		Scenario scenario = scenario();
		LSP original = hubLsp(scenario, "hub-a", "element-a");
		LSP replanned = hubLsp(scenario, "hub-b", "element-b");
		ReRouteListener listener = new ReRouteListener();
		RuntimeException missing = assertThrows(RuntimeException.class,
			() -> listener.applyReplanInPlace(original, replanned));
		assertTrue(missing.getMessage().contains("missing on original LSP"));

		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		group.addParameterSet(new CollaborationParamSet(
			CollaborationTypes.CARRIER_RECEIVER, Set.of()));
		Config config = ConfigUtils.createConfig(group);
		listener.config = config;
		listener.scenario = ScenarioUtils.createScenario(config);
		listener.freightCollaborators = new FreightCollaborators();
		assertThrows(IllegalStateException.class,
			() -> listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false)));
		assertDoesNotThrow(
			() -> listener.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false)));
		assertEquals(0.0, listener.priority());
	}

	private static Scenario scenario() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		var n1 = NetworkUtils.createAndAddNode(scenario.getNetwork(),
			Id.createNodeId("n1"), new org.matsim.api.core.v01.Coord(0, 0));
		var n2 = NetworkUtils.createAndAddNode(scenario.getNetwork(),
			Id.createNodeId("n2"), new org.matsim.api.core.v01.Coord(100, 0));
		NetworkUtils.createAndAddLink(scenario.getNetwork(), Id.createLinkId("link"),
			n1, n2, 100, 10, 1000, 1);
		return scenario;
	}

	private static LSP hubLsp(Scenario scenario, String hubId, String elementId) {
		var scheduler = ResourceImplementationUtils.TranshipmentHubSchedulerBuilder.newInstance()
			.setCapacityNeedFixed(0)
			.setCapacityNeedLinear(0)
			.build();
		var hub = ResourceImplementationUtils.TransshipmentHubBuilder.newInstance(
				Id.create(hubId, LSPResource.class), Id.createLinkId("link"), scenario)
			.setTransshipmentHubScheduler(scheduler)
			.build();
		var element = LSPUtils.LogisticChainElementBuilder.newInstance(
				Id.create(elementId, LogisticChainElement.class))
			.setResource(hub)
			.build();
		var chain = LSPUtils.LogisticChainBuilder.newInstance(
				Id.create("chain-" + hubId, LogisticChain.class))
			.addLogisticChainElement(element)
			.build();
		LSPPlan plan = LSPUtils.createLSPPlan();
		plan.addLogisticChain(chain);
		plan.setInitialShipmentAssigner(
			ResourceImplementationUtils.createSingleLogisticChainShipmentAssigner());
		var chainScheduler =
			ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(
				List.of(hub));
		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create("lsp-" + hubId, LSP.class))
			.setLogisticChainScheduler(chainScheduler)
			.setInitialPlan(plan)
			.build();
		plan.setLSP(lsp);
		return lsp;
	}
}
