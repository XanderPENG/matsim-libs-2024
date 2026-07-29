package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A mutable version of a Coalition that allows adding and removing collaborators during the simulation.
 *
 */
public class MutableFreightCoalition implements FreightCoalition {
	private final Attributes coalitionAttributes = new AttributesImpl();
	private final Map<CollaboratorKey, FreightCollaborator<?>> collaboratorsMap;
	private CollaborationType collaborationType;

	public MutableFreightCoalition(CollaborationType collaborationType) {
		this.collaborationType = Objects.requireNonNull(collaborationType, "collaborationType");
		this.collaboratorsMap = new LinkedHashMap<>();
	}

	@Override
	public Set<FreightCollaborator<?>> getCollaboratorsSet() {
		return Set.copyOf(collaboratorsMap.values());
	}

	@Override
	@Deprecated
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMap() {
		return legacyIdMap();
	}

	@Override
	public Map<CollaboratorKey, FreightCollaborator<?>> getCollaboratorsByKey() {
		return Map.copyOf(collaboratorsMap);
	}

	@Override
	@Deprecated
	public FreightCollaborator<?> getCollaborator(Id<?> id) {
		return findUnique(id);
	}

	@Override
	public FreightCollaborator<?> getCollaborator(CollaboratorRole role, Id<?> id) {
		return collaboratorsMap.get(new CollaboratorKey(role, id));
	}

	@Override
	public Set<CollaboratorRole> getRoles() {
		return collaboratorsMap.values().stream()
			.map(FreightCollaborator::getRole)
			.filter(role -> role != null)
			.collect(java.util.stream.Collectors.toSet());
	}

	@Override
	public boolean contains(FreightCollaborator<?> collaborator) {
		return collaboratorsMap.containsValue(collaborator);
	}

	@Override
	@Deprecated
	public boolean contains(Id<?> id) {
		return findUnique(id) != null;
	}

	@Override
	public boolean contains(CollaboratorRole role, Id<?> id) {
		return collaboratorsMap.containsKey(new CollaboratorKey(role, id));
	}

	@Override
	public int size() {
		return collaboratorsMap.size();
	}

	@Override
	public boolean isEmpty() {
		return collaboratorsMap.isEmpty();
	}

	@Override
	public Set<FreightCollaborator<?>> getCollaboratorsSetByRole(CollaboratorRole role) {
		return collaboratorsMap.values().stream()
			.filter(c -> c.getRole() == role)
			.collect(java.util.stream.Collectors.toSet());
	}

	@Override
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMapByRole(CollaboratorRole role) {
		return collaboratorsMap.values().stream()
			.filter(c -> c.getRole() == role)
			.collect(java.util.stream.Collectors.toUnmodifiableMap(FreightCollaborator::getId, c -> c));
	}

	public CollaborationType getCollaborationType() {
		return collaborationType;
	}

	public void addCollaborator(FreightCollaborator<?> collaborator) {
		Objects.requireNonNull(collaborator, "collaborator");
		collaboratorsMap.put(collaborator.getKey(), collaborator);
	}

	/**
	 * @deprecated Use {@link #removeCollaborator(CollaboratorRole, Id)}.
	 */
	@Deprecated
	public void removeCollaborator(Id<?> id) {
		FreightCollaborator<?> collaborator = findUnique(id);
		if (collaborator != null) {
			collaboratorsMap.remove(collaborator.getKey());
		}
	}

	public void removeCollaborator(CollaboratorRole role, Id<?> id) {
		collaboratorsMap.remove(new CollaboratorKey(role, id));
	}

	public void addCollaborators(Set<FreightCollaborator<?>> collaborators) {
		for (FreightCollaborator<?> collaborator : collaborators) {
			addCollaborator(collaborator);
		}
	}

	public void removeCollaborators(Set<FreightCollaborator<?>> collaborators) {
		for (FreightCollaborator<?> collaborator : collaborators) {
			collaboratorsMap.remove(collaborator.getKey());
		}
	}

	public void updateCollaborationType(CollaborationType newType) {
		this.collaborationType = Objects.requireNonNull(newType, "newType");
	}

	public void updateCollaborators(Set<FreightCollaborator<?>> newCollaborators) {
		this.collaboratorsMap.clear();
		addCollaborators(newCollaborators);
	}

	@Override
	public Attributes getAttributes() {
		return coalitionAttributes;
	}

	private FreightCollaborator<?> findUnique(Id<?> id) {
		Objects.requireNonNull(id, "id");
		List<FreightCollaborator<?>> matches = collaboratorsMap.entrySet().stream()
			.filter(entry -> entry.getKey().id().equals(id))
			.map(Map.Entry::getValue)
			.toList();
		if (matches.size() > 1) {
			throw new IllegalStateException("Ambiguous collaborator id '" + id
				+ "' is used by multiple roles. Use a role-aware lookup.");
		}
		return matches.isEmpty() ? null : matches.getFirst();
	}

	private Map<Id<?>, FreightCollaborator<?>> legacyIdMap() {
		Map<Id<?>, FreightCollaborator<?>> result = new LinkedHashMap<>();
		for (FreightCollaborator<?> collaborator : collaboratorsMap.values()) {
			if (result.putIfAbsent(collaborator.getId(), collaborator) != null) {
				throw new IllegalStateException("Coalition contains the same collaborator id in multiple roles. "
					+ "Use getCollaboratorsByKey().");
			}
		}
		return Map.copyOf(result);
	}
}
