package org.matsim.contrib.freightcollaboration.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.GrandFreightCoalition;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.replanning.GenericStrategyManager;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scenario.ScenarioByInstanceModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationAssemblyTest {

	@TempDir
	Path tempDirectory;

	@Test
	void managerMergesAllowedRolesAndDefensivelyStoresCoalitions() {
		Scenario scenario = scenarioWith(CollaborationTypes.CARRIER_RECEIVER,
			CollaborationTypes.LSP_RECEIVER);
		FreightCoalitionManager manager = new FreightCoalitionManager(scenario);

		assertEquals(Set.of(CollaboratorRole.CARRIER, CollaboratorRole.RECEIVER,
			CollaboratorRole.LSP), manager.getAllCollaboratorRoles());
		assertThrows(UnsupportedOperationException.class,
			() -> manager.getAllCollaboratorRoles().clear());

		GrandFreightCoalition grand = new GrandFreightCoalition(Set.of(
			FreightCollaborationTestFixtures.carrierCollaborator("carrier")));
		manager.setGrandFreightCoalition(grand);
		assertSame(grand, manager.getGrandFreightCoalition());

		MutableFreightCoalition mutable =
			FreightCollaborationTestFixtures.carrierReceiverCoalition("carrier", "receiver");
		java.util.ArrayList<MutableFreightCoalition> input = new java.util.ArrayList<>(List.of(mutable));
		manager.setMutableFreightCoalitions(input);
		input.clear();
		assertEquals(List.of(mutable), manager.getMutableFreightCoalitions());
		assertThrows(UnsupportedOperationException.class,
			() -> manager.getMutableFreightCoalitions().clear());
		manager.setMutableFreightCoalitions(null);
		assertNull(manager.getMutableFreightCoalitions());

		Config missing = ConfigUtils.createConfig();
		FreightCoalitionManager invalid = new FreightCoalitionManager(
			ScenarioUtils.createScenario(missing));
		assertThrows(IllegalStateException.class, invalid::getAllCollaboratorRoles);
	}

	@Test
	void moduleSnapshotsEverySupportedSelectedPlanWithoutSharingIt() {
		Scenario scenario = scenarioWith(CollaborationTypes.CARRIER_RECEIVER);
		FreightCollaborators collaborators = new FreightCollaborators();
		FreightCollaborator<?> carrier =
			FreightCollaborationTestFixtures.carrierCollaborator("carrier");
		FreightCollaborator<?> receiver =
			FreightCollaborationTestFixtures.receiverCollaborator("receiver");
		FreightCollaborator<?> lsp = FreightCollaborationTestFixtures.lspCollaborator("lsp");
		collaborators.addFreightCollaborator(carrier);
		collaborators.addFreightCollaborator(receiver);
		collaborators.addFreightCollaborator(lsp);
		CollaborationModule module = new CollaborationModule(
			new CollaboratorModules(), collaborators, scenario);

		CollaborationDataStore store = module.initCollaborationDataStore();

		assertNull(store.getScenario(),
			"Scenario assignment happens during module installation, not snapshot construction");
		for (FreightCollaborator<?> collaborator : List.of(carrier, receiver, lsp)) {
			Map<?, ? extends BasicPlan> rolePlans =
				store.getOriginalPlans().get(collaborator.getRole());
			assertNotNull(rolePlans);
			BasicPlan snapshot = rolePlans.get(collaborator.getId());
			assertNotNull(snapshot);
			assertNotSame(collaborator.getTypedSelectedPlan(), snapshot);
		}
	}

	@Test
	void collaboratorModulesAndStrategyManagersRejectNullsAndExposeDefensiveViews() {
		AbstractModule carrierModule = new AbstractModule() {
			@Override
			public void install() {
			}
		};
		CollaboratorModules modules =
			new CollaboratorModules(Map.of(CollaboratorRole.CARRIER, carrierModule));
		assertSame(carrierModule, modules.getModule(CollaboratorRole.CARRIER));
		assertNull(modules.getModule(CollaboratorRole.RECEIVER));
		assertThrows(UnsupportedOperationException.class, () -> modules.asMap().clear());
		assertThrows(NullPointerException.class, () -> modules.addModule(null, carrierModule));
		assertThrows(NullPointerException.class,
			() -> modules.addModule(CollaboratorRole.RECEIVER, null));

		@SuppressWarnings("unchecked")
		GenericStrategyManager<?, ?> strategyManager = (GenericStrategyManager<?, ?>)
			Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{GenericStrategyManager.class}, (proxy, method, args) -> {
					if (method.getReturnType() == List.class) {
						return List.of();
					}
					return null;
				});
		var managers = new org.matsim.contrib.freightcollaboration.strategy
			.CollaboratorStrategyManagers.Builder()
			.addStrategyManager(CollaboratorRole.CARRIER, strategyManager)
			.build();
		assertSame(strategyManager,
			managers.getStrategyManager(CollaboratorRole.CARRIER));
		assertNull(managers.getStrategyManager(CollaboratorRole.LSP));
		assertThrows(NullPointerException.class,
			() -> new org.matsim.contrib.freightcollaboration.strategy
				.CollaboratorStrategyManagers.Builder()
				.addStrategyManager(null, strategyManager));
	}

	@Test
	void collaborationModuleInstallsCoreBindingsAndLspListenerBranch() {
		Scenario scenario = scenarioWith(CollaborationTypes.CARRIER_RECEIVER,
			CollaborationTypes.LSP_RECEIVER);
		scenario.getConfig().controller().setOutputDirectory(tempDirectory.resolve("output").toString());
		scenario.getConfig().controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		FreightCollaborators collaborators = new FreightCollaborators(Set.of(
			FreightCollaborationTestFixtures.carrierCollaborator("carrier"),
			FreightCollaborationTestFixtures.receiverCollaborator("receiver")));
		CollaborationModule module =
			new CollaborationModule(new CollaboratorModules(), collaborators, scenario);

		com.google.inject.Injector injector = Injector.createInjector(
			scenario.getConfig(), new ScenarioByInstanceModule(scenario), new AbstractModule() {
				@Override
				public void install() {
					bind(org.matsim.core.api.experimental.events.EventsManager.class)
						.toInstance(EventsUtils.createEventsManager());
					bind(CarrierScoringFunctionFactory.class)
						.toInstance(carrier -> new SumScoringFunction());
					bind(OutputDirectoryHierarchy.class)
						.toInstance(new OutputDirectoryHierarchy(scenario.getConfig()));
				}
			}, module);

		assertAll(
			() -> assertSame(collaborators, injector.getInstance(FreightCollaborators.class)),
			() -> assertSame(scenario,
				injector.getInstance(CollaborationDataStore.class).getScenario()),
			() -> assertNotNull(injector.getInstance(FreightCoalitionManager.class))
		);
	}

	@Test
	void collaborationModuleRejectsUnsupportedMixedTypeDuringInstallation() {
		Scenario scenario = scenarioWith(CollaborationTypes.MIXED);
		CollaborationModule module = new CollaborationModule(
			new CollaboratorModules(), new FreightCollaborators(), scenario);

		RuntimeException failure = assertThrows(RuntimeException.class,
			() -> Injector.createInjector(scenario.getConfig(),
				new ScenarioByInstanceModule(scenario), module));
		assertTrue(failure.getMessage().contains("Unexpected value")
			|| failure.getCause() != null);
	}

	private static Scenario scenarioWith(CollaborationTypes... types) {
		FreightCollaborationConfigGroup group = new FreightCollaborationConfigGroup();
		for (CollaborationTypes type : types) {
			group.addParameterSet(new CollaborationParamSet(type, Set.of()));
		}
		Config config = ConfigUtils.createConfig(group);
		return ScenarioUtils.createScenario(config);
	}
}
