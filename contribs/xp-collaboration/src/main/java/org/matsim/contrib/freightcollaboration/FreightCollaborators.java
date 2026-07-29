package org.matsim.contrib.freightcollaboration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This is a map to store all freight collaborators in the simulation.
 * It should provide methods to add, remove, and retrieve collaborators based on their roles and IDs
 */
public class FreightCollaborators {
	private static final Logger LOGGER = LogManager.getLogger(FreightCollaborators.class);

	private final Map<CollaboratorKey, FreightCollaborator<?>> freightCollaborators = new LinkedHashMap<>();

	public FreightCollaborators() {
	}

	public FreightCollaborators(Collection<FreightCollaborator<?>> freightCollaborators) {
		makeMap(freightCollaborators);
	}

	private void makeMap(Collection<FreightCollaborator<?>> freightCollaborators) {
		Objects.requireNonNull(freightCollaborators, "freightCollaborators");
		for (FreightCollaborator<?> collaborator : freightCollaborators) {
			addFreightCollaborator(collaborator);
		}
	}

	public void addFreightCollaborator(FreightCollaborator<? extends HasPlansAndId<?, ?>> collaborator) {
		Objects.requireNonNull(collaborator, "collaborator");
		CollaboratorKey key = collaborator.getKey();
		if (!freightCollaborators.containsKey(key)) {
			freightCollaborators.put(key, collaborator);
		} else {
			LOGGER.warn("The collaborator with key {} already exists.", key);
		}
	}

	public void addFreightCollaboratorsFromCollection(Collection<FreightCollaborator<?>> collaborators) {
		Objects.requireNonNull(collaborators, "collaborators");
		for (FreightCollaborator<?> collaborator : collaborators) {
			addFreightCollaborator(collaborator);
		}
	}

	/**
	 * Removes the only collaborator with the given raw id.
	 *
	 * @deprecated Use {@link #removeFreightCollaborator(CollaboratorRole, Id)} because a raw id can
	 * identify collaborators in more than one role.
	 */
	@Deprecated
	public void removeFreightCollaboratorById(Id<?> id) {
		CollaboratorKey key = uniqueKeyFor(id);
		if (key == null) {
			LOGGER.warn("No collaborator found with id {} to remove.", id);
		} else {
			freightCollaborators.remove(key);
		}
	}

	public void removeFreightCollaborator(CollaboratorRole role, Id<?> id) {
		CollaboratorKey key = new CollaboratorKey(role, id);
		if (freightCollaborators.remove(key) == null) {
			LOGGER.warn("No collaborator found with key {} to remove.", key);
		}
	}

	public FreightCollaborator<?> getFreightCollaborator(CollaboratorRole role, Id<?> id) {
		return freightCollaborators.get(new CollaboratorKey(role, id));
	}

	@SuppressWarnings("unchecked")
	public <T extends HasPlansAndId<P, I>, P extends BasicPlan, I> Map<Id<T>, FreightCollaborator<T>> getFreightCollaboratorsByRole(CollaboratorRole role) {
		Map<Id<T>, FreightCollaborator<T>> result = new LinkedHashMap<>();
		for (Map.Entry<CollaboratorKey, FreightCollaborator<?>> entry : freightCollaborators.entrySet()) {
			if (entry.getKey().role() == role) {
				result.put((Id<T>) entry.getKey().id(), (FreightCollaborator<T>) entry.getValue());
			}
		}
		return Map.copyOf(result);
	}

	public Map<CollaboratorKey, FreightCollaborator<?>> getFreightCollaboratorsByKey() {
		return Map.copyOf(freightCollaborators);
	}

	/**
	 * Returns a legacy raw-id view.
	 *
	 * @throws IllegalStateException when the same textual id is used by multiple roles
	 * @deprecated Use {@link #getFreightCollaboratorsByKey()} or
	 * {@link #getFreightCollaboratorsByRole(CollaboratorRole)}.
	 */
	@Deprecated
	public Map<Id<?>, FreightCollaborator<?>> getFreightCollaborators() {
		Map<Id<?>, FreightCollaborator<?>> result = new LinkedHashMap<>();
		for (FreightCollaborator<?> collaborator : freightCollaborators.values()) {
			if (result.putIfAbsent(collaborator.getId(), collaborator) != null) {
				throw new IllegalStateException("Ambiguous collaborator id '" + collaborator.getId()
					+ "' is used by multiple roles. Use a role-aware lookup.");
			}
		}
		return Map.copyOf(result);
	}

	private CollaboratorKey uniqueKeyFor(Id<?> id) {
		Objects.requireNonNull(id, "id");
		var matches = freightCollaborators.keySet().stream()
			.filter(key -> key.id().equals(id))
			.collect(Collectors.toList());
		if (matches.size() > 1) {
			throw new IllegalStateException("Ambiguous collaborator id '" + id
				+ "' is used by multiple roles. Use a role-aware lookup.");
		}
		return matches.isEmpty() ? null : matches.getFirst();
	}
}
