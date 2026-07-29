package org.matsim.contrib.freightcollaboration.strategy;

import com.google.inject.Provider;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.core.replanning.GenericStrategyManager;
import org.matsim.freight.receiver.replanning.ReceiverStrategyManager;

import java.util.Map;
import java.util.Objects;

/**
 * This is a "Master Manager" that manages different agents' strategy managers.
 * e.g., CarrierStrategyManager, LSPStrategyManager, ReceiverStrategyManager.
 *
 */
public class CollaboratorStrategyManagers {
	Map<CollaboratorRole, GenericStrategyManager<?,?>> strategyManagers;

	private CollaboratorStrategyManagers(Map<CollaboratorRole, GenericStrategyManager<?,?>> strategyManagers) {
		this.strategyManagers = Map.copyOf(strategyManagers);
	}

	public static class Builder {
		private final Map<CollaboratorRole, GenericStrategyManager<?,?>> strategyManagers;

		public Builder() {
			this.strategyManagers = new java.util.HashMap<>();
		}

		public Builder addStrategyManager(CollaboratorRole role, GenericStrategyManager<?,?> manager) {
			this.strategyManagers.put(Objects.requireNonNull(role, "role"),
				Objects.requireNonNull(manager, "manager"));
			return this;
		}

		public Builder addStrategyManager(CollaboratorRole role, Provider<ReceiverStrategyManager> manager) {
			this.strategyManagers.put(Objects.requireNonNull(role, "role"),
				Objects.requireNonNull(Objects.requireNonNull(manager, "manager").get(),
					"provided strategy manager"));
			return this;
		}

		public CollaboratorStrategyManagers build() {
			return new CollaboratorStrategyManagers(this.strategyManagers);
		}
	}

	@SuppressWarnings("unchecked")
	public <M extends GenericStrategyManager<?, ?>> M getStrategyManager(CollaboratorRole role) {
		switch (role) {
			case CARRIER -> {
				return (M) strategyManagers.get(CollaboratorRole.CARRIER);
			}
			case LSP -> {
				return (M) strategyManagers.get(CollaboratorRole.LSP);
			}
			case RECEIVER -> {
				return (M) strategyManagers.get(CollaboratorRole.RECEIVER);
			}
			default -> {
				throw new IllegalArgumentException("Unsupported CollaboratorRole: " + role);
			}
		}
	}
}
