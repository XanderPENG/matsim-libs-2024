package org.matsim.contrib.freightcollaboration.controler;

import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.listener.CoalitionFormationListener;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;

/**
 * This module should extend AbstractModule and configure bindings for the freight collaboration framework.
 * It should have control over all collaborator modules (e.g., ReceiverModule, CarrierModule, LSPModule).
 */
public class CollaborationModule extends AbstractModule {

	public CollaboratorModules collaboratorModules;

	public CollaborationModule(CollaboratorModules modules) {
		this.collaboratorModules = modules;
	}

	@Override
	public void install() {
		this.addControlerListenerBinding().to(CoalitionFormationListener.class);
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
