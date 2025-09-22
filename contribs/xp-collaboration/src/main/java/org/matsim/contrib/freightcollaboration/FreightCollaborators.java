package org.matsim.contrib.freightcollaboration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This is a map to store all freight collaborators in the simulation.
 * It should provide methods to add, remove, and retrieve collaborators based on their roles and IDs
 */
public class FreightCollaborators {
	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborators.class);

	private final Map<Id<?>, FreightCollaborator<?>> freightCollaborators = new LinkedHashMap<>();

	public FreightCollaborators() {
	}

	public FreightCollaborators(Collection<FreightCollaborator<?>> freightCollaborators) {
		makeMap(freightCollaborators);
	}

	private void makeMap(Collection<FreightCollaborator<?>> freightCollaborators) {
		for (FreightCollaborator<?> collaborator : freightCollaborators) {
			this.freightCollaborators.put(collaborator.getId(), collaborator);
		}
	}

	public void addFreightCollaborator(FreightCollaborator<? extends HasPlansAndId<?, ?>> collaborator) {
		if (!freightCollaborators.containsKey(collaborator.getId())) {
			freightCollaborators.put(collaborator.getId(), collaborator);
		} else {
			LOGGER.warn("The collaborator with id {} already exists.", collaborator.getId());
		}
	}

	public void addFreightCollaboratorsFromCollection(Collection<FreightCollaborator<?>> collaborators) {
		for (FreightCollaborator<?> collaborator : collaborators) {
			addFreightCollaborator(collaborator);
		}
	}

	public void removeFreightCollaboratorById(Id<?> id) {
		if (freightCollaborators.containsKey(id)) {
			freightCollaborators.remove(id);
		} else {
			LOGGER.warn("No collaborator found with id {} to remove.", id);
		}
	}

	@SuppressWarnings("unchecked")
	public <T extends HasPlansAndId<P, I>, P extends BasicPlan, I> Map<Id<T>, FreightCollaborator<T>> getFreightCollaboratorsByRole(CollaboratorRole role) {
		Map<Id<T>, FreightCollaborator<T>> result = new LinkedHashMap<>();
		for (Map.Entry<Id<?>, FreightCollaborator<?>> entry : freightCollaborators.entrySet()) {
			if (entry.getValue().getRole() == role) {
				result.put((Id<T>) entry.getKey(), (FreightCollaborator<T>) entry.getValue());
			}
		}
		return result;
	}

	public Map<Id<?>, FreightCollaborator<?>> getFreightCollaborators() {
		return freightCollaborators;
	}
}
