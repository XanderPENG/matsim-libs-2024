package org.matsim.contrib.freightcollaboration;

import java.util.Set;

public interface CollaborationType {

	/**
	 * Get the name of this collaboration type.
	 */
	String getType();

	/**
	 * Get a description of this collaboration type.
	 */
	String getDescription();

	/**
	 * Check if this collaboration type allows the given collaborator roles.
	 */
	boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2);

	Set<CollaboratorRole> getAllowedRoles();
}

