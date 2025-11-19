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
import org.matsim.contrib.freightcollaboration.listener.AllocationToScoreListener;
import org.matsim.contrib.freightcollaboration.listener.FormFreightCoalitionListener;
import org.matsim.contrib.freightcollaboration.listener.FreightCollaborationListener;
import org.matsim.contrib.freightcollaboration.listener.NotifyCoalitionInfoListener;
import org.matsim.contrib.freightcollaboration.utils.AllocationUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.freight.receiver.ReceiverPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		// Bind the FreightCoalitionManager as a singleton at the start of the simulation
		this.bind(FreightCoalitionManager.class).asEagerSingleton();
		this.bind(FreightCollaborators.class).toInstance(freightCollaborators);

		// Initialize and bind the CollaborationDataStore so it is available for injection
		CollaborationDataStore dataStore = initCollaborationDataStore();
		dataStore.setScenario(scenario);
		this.bind(CollaborationDataStore.class).toInstance(dataStore);
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
							selected = AllocationUtils.copyNoScorePlan(collaborator.getTypedSelectedPlan());
						} catch (NullPointerException e) {
							logger.warn("Copy carrier plan into CollaborationDataStore failed for collaborator ID: {}. " +
								"The selected plan might be null.", collaborator.getId());
							selected = null;
						}
						break;
					case CollaboratorRole.LSP:
						// raise exception for now as LSP plan copying is not implemented yet
						throw new UnsupportedOperationException("LSP plan copying is not implemented yet.");
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
}
