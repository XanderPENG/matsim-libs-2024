package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.utils.objectattributes.attributable.Attributable;

import java.util.Map;
import java.util.Set;

public interface FreightCoalition extends Attributable {

	/**
	 * Get all collaborators.
	 */
	Set<FreightCollaborator<?>> getCollaboratorsSet();

	/**
	 * Get all collaborators with their IDs
	 */
	Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMap();

	/**
	 * Get a collaborator by its ID.
	 * @param id The ID of the collaborator
	 */
	FreightCollaborator<?> getCollaborator(Id<?> id);

	/**
	 * Get all unique roles represented in this coalition.
	 */
	Set<CollaboratorRole> getRoles();

	/**
	 * Check if a collaborator is part of this coalition.
	 * @param collaborator The collaborator to check
	 */
	boolean contains(FreightCollaborator<?> collaborator);

	/**
	 * Check if a collaborator with the given ID is part of this coalition.
	 * @param id The ID to check
	 */
	boolean contains(Id<?> id);

	/**
	 * Get the number of collaborators in this coalition.
	 */
	int size();

	/**
	 * Check if the coalition is empty.
	 */
	boolean isEmpty();

	/**
	 * Get all collaborators with a specific role.
	 * @param role The role to filter by
	 */
	Set<FreightCollaborator<?>> getCollaboratorsSetByRole(CollaboratorRole role);

	/**
	 * Get all collaborators and their IDs with a specific role.
	 */
	Map<Id<?>, FreightCollaborator<?>> getCollaboratorsMapByRole(CollaboratorRole role);
}
