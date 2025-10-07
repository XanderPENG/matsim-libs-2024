package org.matsim.contrib.freightcollaboration.controller;

import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.listener.FormFreightCoalitionListener;
import org.matsim.contrib.freightcollaboration.listener.NotifyCoalitionInfoListener;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;

/**
 * This module should extend AbstractModule and configure bindings for the freight collaboration framework.
 * It should have control over all collaborator modules (e.g., ReceiverModule, CarrierModule, LSPModule).
 */
public class CollaborationModule extends AbstractModule {

	private final CollaboratorModules collaboratorModules;
	private final FreightCollaborators freightCollaborators;

	public CollaborationModule(CollaboratorModules modules, FreightCollaborators freightCollaborators) {
		this.collaboratorModules = modules;
		this.freightCollaborators = freightCollaborators;
	}

	@Override
	public void install() {
		this.addControlerListenerBinding().to(NotifyCoalitionInfoListener.class);
		this.addControlerListenerBinding().to(FormFreightCoalitionListener.class);
		// Bind the FreightCoalitionManager as a singleton at the start of the simulation
		this.bind(FreightCoalitionManager.class).asEagerSingleton();
		this.bind(FreightCollaborators.class).toInstance(freightCollaborators);
	}

	public void installAllCollaboratorModules(Controler controler) {
		for (CollaboratorRole role : CollaboratorRole.values()) {
			AbstractModule module = collaboratorModules.getModule(role);
			if (module != null) {
				controler.addOverridingModule(module);
			}
		}
	}
}
