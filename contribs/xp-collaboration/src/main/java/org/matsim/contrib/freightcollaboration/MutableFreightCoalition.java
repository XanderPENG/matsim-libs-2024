package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.utils.objectattributes.attributable.Attributes;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.Map;
import java.util.Set;

/**
 * A mutable version of a Coalition that allows adding and removing collaborators during the simulation.
 *
 */
public class MutableFreightCoalition implements FreightCoalition {
	private final Attributes coalitionAttributes = new AttributesImpl();
	private final Map<Id<?>, FreightCollaborator<?>> collaboratorsMap;
	private CollaborationType collaborationType;

	public MutableFreightCoalition(CollaborationType collaborationType) {
		this.collaborationType = collaborationType;
		this.collaboratorsMap = new java.util.HashMap<>();
	}

	@Override
	public Set<FreightCollaborator<?>> getCollaboratorsSet() {
		return Set.copyOf(collaboratorsMap.values());
	}

	@Override
	public Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMap() {
		return collaboratorsMap;
	}

	@Override
	public FreightCollaborator<?> getCollaborator(Id<?> id) {
		return collaboratorsMap.get(id);
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
	public boolean contains(Id<?> id) {
		return collaboratorsMap.containsKey(id);
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
			.collect(java.util.stream.Collectors.toMap(FreightCollaborator::getId, c -> c));
	}

	public CollaborationType getCollaborationType() {
		return collaborationType;
	}

	public void addCollaborator(FreightCollaborator<?> collaborator) {
		collaboratorsMap.put(collaborator.getId(), collaborator);
	}

	public void removeCollaborator(Id<?> id) {
		collaboratorsMap.remove(id);
	}

	public void addCollaborators(Set<FreightCollaborator<?>> collaborators) {
		for (FreightCollaborator<?> collaborator : collaborators) {
			addCollaborator(collaborator);
		}
	}

	public void removeCollaborators(Set<FreightCollaborator<?>> collaborators) {
		for (FreightCollaborator<?> collaborator : collaborators) {
			removeCollaborator(collaborator.getId());
		}
	}

	public void updateCollaborationType(CollaborationType newType) {
		this.collaborationType = newType;
	}

	public void updateCollaborators(Set<FreightCollaborator<?>> newCollaborators) {
		this.collaboratorsMap.clear();
		addCollaborators(newCollaborators);
	}

	@Override
	public Attributes getAttributes() {
		return coalitionAttributes;
	}
}
