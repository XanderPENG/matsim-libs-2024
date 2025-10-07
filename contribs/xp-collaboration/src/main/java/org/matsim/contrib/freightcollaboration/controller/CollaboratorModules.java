package org.matsim.contrib.freightcollaboration.controller;

import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.core.controler.AbstractModule;

import java.util.HashMap;
import java.util.Map;

public class CollaboratorModules {
	private final Map<CollaboratorRole, AbstractModule> modules;

	public CollaboratorModules() {
		// Create an empty mutable map
		this.modules = new HashMap<>();
	}

	public CollaboratorModules(Map<CollaboratorRole, AbstractModule> modules) {
		this.modules = modules;
	}

	public AbstractModule getModule(CollaboratorRole role) {
		return modules.get(role);
	}

	public void addModule(CollaboratorRole role, AbstractModule module) {
		modules.put(role, module);
	}


}
