package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;

import java.util.Objects;

/**
 * Role-aware identity for a freight collaborator.
 *
 * <p>MATSim {@link Id} equality is based on the textual id. Consequently, a carrier and a receiver
 * with the same textual id cannot safely share a map keyed only by {@code Id<?>}. This key keeps the
 * role as part of the identity.</p>
 */
public record CollaboratorKey(CollaboratorRole role, Id<?> id) implements Comparable<CollaboratorKey> {

	public CollaboratorKey {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(id, "id");
	}

	public static CollaboratorKey of(FreightCollaborator<?> collaborator) {
		Objects.requireNonNull(collaborator, "collaborator");
		return new CollaboratorKey(collaborator.getRole(), collaborator.getId());
	}

	@Override
	public int compareTo(CollaboratorKey other) {
		int roleComparison = role.compareTo(other.role);
		return roleComparison != 0 ? roleComparison : id.toString().compareTo(other.id.toString());
	}
}
