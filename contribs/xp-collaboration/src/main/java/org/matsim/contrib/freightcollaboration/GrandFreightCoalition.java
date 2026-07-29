package org.matsim.contrib.freightcollaboration;


import org.matsim.api.core.v01.Id;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The grand coalition including all potential collaborators, who should be defined at the start of the simulation.
 * Most importantly, it should be immutable (i.e., agents cannot enter the grand coalition in the halfway).
 */
public class GrandFreightCoalition implements FreightCoalition {
	private final Attributes coalitionAttributes = new AttributesImpl();
	private final Map<CollaboratorKey, FreightCollaborator<?>> grandCoalitionCollaborators;
	private final Set<CollaboratorRole> roles;

	public GrandFreightCoalition(Collection<FreightCollaborator<?>> collaborators) {
		if (collaborators == null) {
			throw new IllegalArgumentException("Collaborators collection cannot be null");
		}

		// Create immutable internal structures
		Map<CollaboratorKey, FreightCollaborator<?>> tempMap = new LinkedHashMap<>();
		for (FreightCollaborator<?> collaborator : collaborators) {
			if (collaborator == null) {
				throw new IllegalArgumentException("Collaborator cannot be null");
			}
			FreightCollaborator<?> previous = tempMap.putIfAbsent(collaborator.getKey(), collaborator);
			if (previous != null) {
				throw new IllegalArgumentException("Duplicate collaborator key: " + collaborator.getKey());
			}
		}

		this.grandCoalitionCollaborators = Collections.unmodifiableMap(tempMap);
		this.roles = tempMap.values().stream()
			.map(FreightCollaborator::getRole)
			.filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
	}

	@Override
	public Set<FreightCollaborator<?>> getCollaboratorsSet() {
		return Set.copyOf(grandCoalitionCollaborators.values());
	}

	@Override
	@Deprecated
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMap() {
		return legacyIdMap();
	}

	@Override
	public Map<CollaboratorKey, FreightCollaborator<?>> getCollaboratorsByKey() {
		return grandCoalitionCollaborators;
	}

	@Override
	@Deprecated
	public FreightCollaborator<?> getCollaborator(Id<?> id) {
		return findUnique(id);
	}

	@Override
	public FreightCollaborator<?> getCollaborator(CollaboratorRole role, Id<?> id) {
		return grandCoalitionCollaborators.get(new CollaboratorKey(role, id));
	}

	@Override
	public Set<CollaboratorRole> getRoles() {
		return roles;
	}

	@Override
	public boolean contains(FreightCollaborator<?> collaborator) {
		return grandCoalitionCollaborators.containsValue(collaborator);
	}

	@Override
	@Deprecated
	public boolean contains(Id<?> id) {
		return findUnique(id) != null;
	}

	@Override
	public boolean contains(CollaboratorRole role, Id<?> id) {
		return grandCoalitionCollaborators.containsKey(new CollaboratorKey(role, id));
	}

	@Override
	public int size() {
		return grandCoalitionCollaborators.size();
	}

	@Override
	public boolean isEmpty() {
		return grandCoalitionCollaborators.isEmpty();
	}

	@Override
	public Set<FreightCollaborator<?>> getCollaboratorsSetByRole(CollaboratorRole role) {
		return grandCoalitionCollaborators.values().stream()
			.filter(c -> c.getRole() == role)
			.collect(Collectors.toSet());
	}

	@Override
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMapByRole(CollaboratorRole role) {
		return grandCoalitionCollaborators.values().stream()
			.filter(c -> c.getRole() == role)
			.collect(Collectors.toUnmodifiableMap(FreightCollaborator::getId, c -> c));
	}

	@Override
	public Attributes getAttributes() {
		return coalitionAttributes;
	}

	private FreightCollaborator<?> findUnique(Id<?> id) {
		Objects.requireNonNull(id, "id");
		List<FreightCollaborator<?>> matches = grandCoalitionCollaborators.entrySet().stream()
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
		for (FreightCollaborator<?> collaborator : grandCoalitionCollaborators.values()) {
			if (result.putIfAbsent(collaborator.getId(), collaborator) != null) {
				throw new IllegalStateException("Coalition contains the same collaborator id in multiple roles. "
					+ "Use getCollaboratorsByKey().");
			}
		}
		return Map.copyOf(result);
	}
}
