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
	private final Map<Id<?>, FreightCollaborator<?>> grandCoalitionCollaborators;
	private final Set<CollaboratorRole> roles;

	public GrandFreightCoalition(Collection<FreightCollaborator<?>> collaborators) {
		if (collaborators == null) {
			throw new IllegalArgumentException("Collaborators collection cannot be null");
		}

		// Create immutable internal structures
		Map<Id<?>, FreightCollaborator<?>> tempMap = new HashMap<>();
		for (FreightCollaborator<?> collaborator : collaborators) {
			if (collaborator == null) {
				throw new IllegalArgumentException("Collaborator cannot be null");
			}
			tempMap.put(collaborator.getId(), collaborator);
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
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMap() {
		return grandCoalitionCollaborators;
	}

	@Override
	public FreightCollaborator<?> getCollaborator(Id<?> id) {
		return grandCoalitionCollaborators.get(id);
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
	public boolean contains(Id<?> id) {
		return grandCoalitionCollaborators.containsKey(id);
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
			.collect(Collectors.toMap(FreightCollaborator::getId, c -> c));
	}

	@Override
	public Attributes getAttributes() {
		return coalitionAttributes;
	}
}
