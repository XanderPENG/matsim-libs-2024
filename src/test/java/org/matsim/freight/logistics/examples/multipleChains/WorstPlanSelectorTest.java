/*
  *********************************************************************** *
  * project: org.matsim.*
  *                                                                         *
  * *********************************************************************** *
  *                                                                         *
  * copyright       :  (C) 2024 by the members listed in the COPYING,       *
  *                   LICENSE and WARRANTY file.                            *
  * email           : info at matsim dot org                                *
  *                                                                         *
  * *********************************************************************** *
  *                                                                         *
  *   This program is free software; you can redistribute it and/or modify  *
  *   it under the terms of the GNU General Public License as published by  *
  *   the Free Software Foundation; either version 2 of the License, or     *
  *   (at your option) any later version.                                   *
  *   See also COPYING, LICENSE and WARRANTY file                           *
  *                                                                         *
  * ***********************************************************************
 */

package org.matsim.freight.logistics.examples.multipleChains;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.replanning.GenericPlanStrategyImpl;
import org.matsim.core.replanning.selectors.BestPlanSelector;
import org.matsim.core.replanning.selectors.ExpBetaPlanSelector;
import org.matsim.core.replanning.selectors.GenericWorstPlanForRemovalSelector;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.freight.carriers.*;
import org.matsim.freight.carriers.controler.CarrierControlerUtils;
import org.matsim.freight.carriers.controler.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controler.CarrierStrategyManager;
import org.matsim.freight.logistics.*;
import org.matsim.freight.logistics.resourceImplementations.ResourceImplementationUtils;
import org.matsim.freight.logistics.shipment.LSPShipment;
import org.matsim.freight.logistics.shipment.ShipmentUtils;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.vehicles.VehicleType;

public class WorstPlanSelectorTest {

	private static final Id<Link> DEPOT_SOUTH_LINK_ID = Id.createLinkId("i(1,0)");
	private static final Id<Link> DEPOT_NORTH_LINK_ID = Id.createLinkId("i(1,8)");
	private static final VehicleType VEH_TYPE_CHEAP = CarrierVehicleType.Builder.newInstance(Id.create("cheap", VehicleType.class))
			.setCapacity(50)
			.setMaxVelocity(10)
			.setFixCost(1)
			.setCostPerDistanceUnit(0.001)
			.setCostPerTimeUnit(0.001)
			.build();
	private static final VehicleType VEH_TYPE_EXPENSIVE = CarrierVehicleType.Builder.newInstance(Id.create("expensive", VehicleType.class))
			.setCapacity(50)
			.setMaxVelocity(10)
			.setFixCost(100)
			.setCostPerDistanceUnit(0.01)
			.setCostPerTimeUnit(0.01)
			.build();
	@RegisterExtension
	public final MatsimTestUtils utils = new MatsimTestUtils();
	private LSP lsp;

	private static Scenario prepareScenario(Config config) {
		Scenario scenario = ScenarioUtils.loadScenario(config);

		for (Link link : scenario.getNetwork().getLinks().values()) {
			link.setFreespeed(30 / 3.6);
			link.setCapacity(1000);
		}

		LSPUtils.addLSPs(scenario, new LSPs(Collections.singletonList(createLSP(scenario))));

		return scenario;
	}

	private static LSP createLSP(Scenario scenario) {
		Network network = scenario.getNetwork();

		// A plan with one logistic chain, containing a single carrier is created
		LSPPlan lspPlan_singleChain;
		{
			Carrier singleCarrier = CarriersUtils.createCarrier(Id.create("singleCarrier", Carrier.class));
			singleCarrier.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);

			CarriersUtils.addCarrierVehicle(singleCarrier, CarrierVehicle.newInstance(Id.createVehicleId("directTruck"), DEPOT_SOUTH_LINK_ID, VEH_TYPE_EXPENSIVE));
			LSPResource singleCarrierResource = ResourceImplementationUtils.DistributionCarrierResourceBuilder.newInstance(singleCarrier, network)
					.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler())
					.build();

			LogisticChainElement singleCarrierElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("singleCarrierElement", LogisticChainElement.class))
					.setResource(singleCarrierResource)
					.build();

			LogisticChain singleChain = LSPUtils.LogisticChainBuilder.newInstance(Id.create("singleChain", LogisticChain.class))
					.addLogisticChainElement(singleCarrierElement)
					.build();

			final InitialShipmentAssigner singleSolutionShipmentAssigner = MultipleChainsUtils.createPrimaryLogisticChainShipmentAssigner();
			lspPlan_singleChain = LSPUtils.createLSPPlan()
					.addLogisticChain(singleChain)
					.setInitialShipmentAssigner(singleSolutionShipmentAssigner);

			lspPlan_singleChain.setType(MultipleChainsUtils.LspPlanTypes.SINGLE_ONE_ECHELON_CHAIN.toString());
		}

		// A plan with two different logistic chains in the south and north, with respective carriers is created
		LSPPlan lspPlan_twoChains;
		{
			LogisticChainElement southCarrierElement;
			{
				Carrier carrierSouth = CarriersUtils.createCarrier(Id.create("carrierSouth", Carrier.class));
				carrierSouth.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);

				CarriersUtils.addCarrierVehicle(carrierSouth, CarrierVehicle.newInstance(Id.createVehicleId("directTruck"), DEPOT_SOUTH_LINK_ID, VEH_TYPE_CHEAP));
				LSPResource carrierSouthResource = ResourceImplementationUtils.DistributionCarrierResourceBuilder.newInstance(carrierSouth, network)
						.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler())
						.build();

				southCarrierElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("southCarrierElement", LogisticChainElement.class))
						.setResource(carrierSouthResource)
						.build();
			}

			LogisticChainElement northCarrierElement;
			{
				Carrier carrierNorth = CarriersUtils.createCarrier(Id.create("CarrierNorth", Carrier.class));
				carrierNorth.getCarrierCapabilities().setFleetSize(CarrierCapabilities.FleetSize.INFINITE);

				CarriersUtils.addCarrierVehicle(carrierNorth, CarrierVehicle.newInstance(Id.createVehicleId("directTruck"), DEPOT_NORTH_LINK_ID, VEH_TYPE_CHEAP));
				LSPResource carrierNorthResource = ResourceImplementationUtils.DistributionCarrierResourceBuilder.newInstance(carrierNorth, network)
						.setDistributionScheduler(ResourceImplementationUtils.createDefaultDistributionCarrierScheduler())
						.build();

				northCarrierElement = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("northCarrierElement", LogisticChainElement.class))
						.setResource(carrierNorthResource)
						.build();
			}

			LogisticChain southChain = LSPUtils.LogisticChainBuilder.newInstance(Id.create("southChain", LogisticChain.class))
					.addLogisticChainElement(southCarrierElement)
					.build();

			LogisticChain northChain = LSPUtils.LogisticChainBuilder.newInstance(Id.create("northChain", LogisticChain.class))
					.addLogisticChainElement(northCarrierElement)
					.build();

			final InitialShipmentAssigner shipmentAssigner = MultipleChainsUtils.createPrimaryLogisticChainShipmentAssigner();
			lspPlan_twoChains = LSPUtils.createLSPPlan()
					.addLogisticChain(southChain)
					.addLogisticChain(northChain)
					.setInitialShipmentAssigner(shipmentAssigner);

			lspPlan_twoChains.setType(MultipleChainsUtils.LspPlanTypes.MULTIPLE_ONE_ECHELON_CHAINS.toString());
		}

		List<LSPPlan> lspPlans = new ArrayList<>();
		lspPlans.add(lspPlan_singleChain);
		lspPlans.add(lspPlan_twoChains);

		LSP lsp = LSPUtils.LSPBuilder.getInstance(Id.create("org/matsim/freight/logistics/lsp", LSP.class))
				.setInitialPlan(lspPlan_singleChain)
				.setLogisticChainScheduler(ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler(createResourcesListFromLSPPlans(lspPlans)))
				.build();
		lsp.addPlan(lspPlan_twoChains);

		for (LSPShipment shipment : createInitialLSPShipments(network)) {
			lsp.assignShipmentToLSP(shipment);
		}

		lsp.scheduleLogisticChains();

		return lsp;
	}

	private static Collection<LSPShipment> createInitialLSPShipments(Network network) {
		List<LSPShipment> shipmentList = new ArrayList<>();

		Random rand2 = MatsimRandom.getLocalInstance();


		List<String> zoneLinkList = Arrays.asList("i(4,4)", "i(5,4)", "i(6,4)", "i(4,6)", "i(5,6)", "i(6,6)",
				"j(3,5)", "j(3,6)", "j(3,7)", "j(5,5)", "j(5,6)", "j(5,7)",
				"i(4,5)R", "i(5,5)R", "i(6,5)R", "i(4,7)R", "i(5,7)R", "i(6,7)R",
				"j(4,5)R", "j(4,6)R", "j(4,7)R", "j(6,5)R", "j(6,6)R", "j(6,7)R");
		for (String linkIdString : zoneLinkList) {
			if (!network.getLinks().containsKey( Id.createLinkId(linkIdString))) {
				throw new RuntimeException("Link is not in Network!");
			}
		}

		for(int i = 1; i <= 10; i++) {
			Id<LSPShipment> id = Id.create("Shipment_" + i, LSPShipment.class);
			ShipmentUtils.LSPShipmentBuilder builder = ShipmentUtils.LSPShipmentBuilder.newInstance(id);

			int capacityDemand = 1;
			builder.setCapacityDemand(capacityDemand);

			builder.setFromLinkId(DEPOT_SOUTH_LINK_ID);
			final Id<Link> toLinkId = Id.createLinkId(zoneLinkList.get(rand2.nextInt(zoneLinkList.size()-1)));
			builder.setToLinkId(toLinkId);

			builder.setEndTimeWindow(TimeWindow.newInstance(0, (24 * 3600)));
			builder.setStartTimeWindow(TimeWindow.newInstance(0, (24 * 3600)));
			builder.setDeliveryServiceTime(capacityDemand * 60);

			shipmentList.add(builder.build());
		}
		return shipmentList;
	}

	private static List<LSPResource> createResourcesListFromLSPPlans(List<LSPPlan> lspPlans) {
		List<LSPResource> resourceList = new ArrayList<>();
		for (LSPPlan lspPlan : lspPlans) {
			for (LogisticChain solution : lspPlan.getLogisticChains()) {
				for (LogisticChainElement solutionElement : solution.getLogisticChainElements()) {
					resourceList.add(solutionElement.getResource());
				}
			}
		}
		return resourceList;
	}

	@BeforeEach
	public void initialize() {

		Config config = prepareConfig();

		Scenario scenario = prepareScenario(config);

		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				install(new LSPModule());
			}
		});

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				final EventBasedCarrierScorer4MultipleChains carrierScorer = new EventBasedCarrierScorer4MultipleChains();

				bind(CarrierScoringFunctionFactory.class).toInstance(carrierScorer);
				bind(LSPScorerFactory.class).toInstance( () -> new MyLSPScorer());
				bind(CarrierStrategyManager.class).toProvider(() -> {
					CarrierStrategyManager strategyManager = CarrierControlerUtils.createDefaultCarrierStrategyManager();
					strategyManager.addStrategy(new GenericPlanStrategyImpl<>(new BestPlanSelector<>()), null, 1);
					return strategyManager;
				});
				bind(LSPStrategyManager.class).toProvider(() -> {
					LSPStrategyManager strategyManager = new LSPStrategyManagerImpl();
					strategyManager.addStrategy(new GenericPlanStrategyImpl<>(new ExpBetaPlanSelector<>(new ScoringConfigGroup())), null, 1);
					strategyManager.addStrategy( RandomDistributionAllShipmentsStrategyFactory.createStrategy(), null, 1);
					strategyManager.setMaxPlansPerAgent(2);
					strategyManager.setPlanSelectorForRemoval(new GenericWorstPlanForRemovalSelector<>() );
					return strategyManager;
				});
			}
		});

		controler.getConfig().vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);
		controler.run();

		this.lsp = LSPUtils.getLSPs(controler.getScenario()).getLSPs().values().iterator().next();
	}

	private Config prepareConfig() {
		Config config = ConfigUtils.createConfig();

		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setLastIteration(10);

		config.network().setInputFile(String.valueOf(IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9"), "grid9x9.xml")));
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setWriteEventsInterval(1);

		FreightCarriersConfigGroup freightConfig = ConfigUtils.addOrGetModule(config, FreightCarriersConfigGroup.class);
		freightConfig.setTimeWindowHandling(FreightCarriersConfigGroup.TimeWindowHandling.ignore);

		return config;
	}

	@Test
	public void testPreserveLastPlanOfType() {
		Set<String> planTypes = new HashSet<>();

		for (LSPPlan lspPlan : lsp.getPlans()) {
			planTypes.add(lspPlan.getType());

			if (planTypes.size() > 1) {
				break;
			}
		}

		assertTrue(planTypes.size() > 1);
	}

}
