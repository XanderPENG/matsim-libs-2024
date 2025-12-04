package org.matsim.contrib.freightcollaboration.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborator;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.listener.*;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER;
import static org.matsim.contrib.freightcollaboration.CollaborationTypes.LSP_RECEIVER;

/**
 * This module should extend AbstractModule and configure bindings for the freight collaboration framework.
 * It should have control over all collaborator modules (e.g., ReceiverModule, CarrierModule, LSPModule).
 */
public class CollaborationModule extends AbstractModule {

	private final CollaboratorModules collaboratorModules;
	private final FreightCollaborators freightCollaborators;
	private final Scenario scenario;
	private static Logger logger = LogManager.getLogger(CollaborationModule.class);

	public CollaborationModule(CollaboratorModules modules, FreightCollaborators freightCollaborators, Scenario scenario) {
		this.collaboratorModules = modules;
		this.freightCollaborators = freightCollaborators;
		this.scenario = scenario;
	}

	@Override
	public void install() {
		this.addControlerListenerBinding().to(NotifyCoalitionInfoListener.class);
		this.addControlerListenerBinding().to(FormFreightCoalitionListener.class);
		this.addControlerListenerBinding().to(FreightCollaborationListener.class);
		// Implement the allocation in the scoring function directly, so this listener is not needed anymore
//		this.addControlerListenerBinding().to(AllocationToScoreListener.class);
		this.addControlerListenerBinding().to(WriteCollaborationDataListener.class);
		// Bind the FreightCoalitionManager as a singleton at the start of the simulation
		this.bind(FreightCoalitionManager.class).asEagerSingleton();
		this.bind(FreightCollaborators.class).toInstance(freightCollaborators);

		// Initialize and bind the CollaborationDataStore so it is available for injection
		CollaborationDataStore dataStore = initCollaborationDataStore();
		dataStore.setScenario(scenario);
		this.bind(CollaborationDataStore.class).toInstance(dataStore);
		// Install specific Collaboration type listeners
		installCollaborationTypeListeners();
	}

	public void installAllCollaboratorModules(Controler controler) {
		for (CollaboratorRole role : CollaboratorRole.values()) {
			AbstractModule module = collaboratorModules.getModule(role);
			if (module != null) {
				controler.addOverridingModule(module);
			}
		}
	}

	private CollaborationDataStore initCollaborationDataStore() {
        Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans = new HashMap<>();
        List<CollaboratorRole> roles = List.of(CollaboratorRole.CARRIER, CollaboratorRole.RECEIVER, CollaboratorRole.LSP);
        for (CollaboratorRole role : roles) {
            @SuppressWarnings("unchecked")
            Map<Id<?>, FreightCollaborator<?>> collaboratorsById = (Map<Id<?>, FreightCollaborator<?>>)(Map<?, ?>) freightCollaborators.getFreightCollaboratorsByRole(role);
            if (collaboratorsById == null || collaboratorsById.isEmpty()) {
                continue;
            }

            Map<Id<?>, BasicPlan> plansById = new HashMap<>();
            for (Map.Entry<Id<?>, FreightCollaborator<?>> entry : collaboratorsById.entrySet()) {
                FreightCollaborator<?> collaborator = entry.getValue();
                if (collaborator == null) continue;
                BasicPlan selected;
				// Then, we need to deep copy the plan to avoid modifications during the simulation
				switch (collaborator.getRole()) {
					case CollaboratorRole.RECEIVER:
						ReceiverPlan selectedPlan = collaborator.getTypedSelectedPlan();
						selected = selectedPlan.createCopy();
						break;
					case CollaboratorRole.CARRIER:
						try {
							selected = AllocationUtils.copyNoScoreCarrierPlan(collaborator.getTypedSelectedPlan());
						} catch (NullPointerException e) {
							logger.warn("Copy carrier plan into CollaborationDataStore failed for collaborator ID: {}. " +
								"The selected plan might be null.", collaborator.getId());
							selected = null;
						}
						break;
					case CollaboratorRole.LSP:
						try {
							selected = AllocationUtils.copyLspPlan(collaborator.getTypedSelectedPlan());
						} catch (Exception e) {
							logger.warn("Copy LSP plan into CollaborationDataStore failed for collaborator ID: {}", collaborator.getId(), e);
							selected = null;
						}
						break;
					default:
						throw  new UnsupportedOperationException("Unknown role: " + collaborator.getRole());
				}
                if (selected != null) {
                    plansById.put(entry.getKey(), selected);
                }
            }
            if (plansById.isEmpty()) {
                continue;
            }
            originalPlans.put(role, plansById);
        }
        return new CollaborationDataStore(originalPlans);
    }

	private void installCollaborationTypeListeners() {
		// get collaboration types from the config and install corresponding listeners
		FreightCollaborationConfigGroup fccg = (FreightCollaborationConfigGroup) scenario.getConfig().getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		for (var paramSet : fccg.getCollaborationParamSets()) {
			switch (paramSet.getCollaborationType()) {
				case LSP_RECEIVER:
					// install LSP-Receiver specific listeners
//					this.addControlerListenerBinding().to(LspReceiverPreRerouteListener.class);
//					this.addControlerListenerBinding().to(LspReceiverAfterRerouteListener.class);
//					logger.info("LSP_RECEIVER Pre/after Reroute listener registered.");
					this.addControlerListenerBinding().to(ReRouteListener.class);
					break;
				case CARRIER_RECEIVER:
					return;
				default:
					throw new IllegalStateException("Unexpected value: " + paramSet.getCollaborationType());
			}
		}
	}
}
