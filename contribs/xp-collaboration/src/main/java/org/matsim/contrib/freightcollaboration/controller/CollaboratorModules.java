package org.matsim.contrib.freightcollaboration.controller;

import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.core.controler.AbstractModule;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CollaboratorModules {
	private final Map<CollaboratorRole, AbstractModule> modules;

	public CollaboratorModules() {
		// Create an empty mutable map
		this.modules = new HashMap<>();
	}

	public CollaboratorModules(Map<CollaboratorRole, AbstractModule> modules) {
		this.modules = new HashMap<>();
		Objects.requireNonNull(modules, "modules").forEach(this::addModule);
	}

	public AbstractModule getModule(CollaboratorRole role) {
		return modules.get(role);
	}

	public void addModule(CollaboratorRole role, AbstractModule module) {
		modules.put(Objects.requireNonNull(role, "role"), Objects.requireNonNull(module, "module"));
	}

	public Map<CollaboratorRole, AbstractModule> asMap() {
		return Map.copyOf(modules);
	}
}
