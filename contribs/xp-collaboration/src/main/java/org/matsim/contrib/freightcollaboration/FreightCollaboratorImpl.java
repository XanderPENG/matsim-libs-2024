package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FreightCollaboratorImpl<T extends HasPlansAndId<?, ?>> implements FreightCollaborator<T> {

	private final T delegate;
	private final CollaboratorRole role;
	private boolean collaborationEnabled = true;
	private Set<Id<?>> collaborationPartners = Set.of();
	private final Map<CollaboratorRole, Set<Id<?>>> originalConnectedStakeholders = new HashMap<>();

	public FreightCollaboratorImpl(T delegate, CollaboratorRole role) {
		this.delegate = delegate;
		this.role = role;
	}

	@Override
	public T getDelegate() {
		return this.delegate;
	}

	@Override
	public CollaboratorRole getRole() {
		return this.role;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <P extends BasicPlan> P getTypedSelectedPlan(){
		switch (delegate) {
			case Carrier carrier -> {
				return (P) carrier.getSelectedPlan();
			}
			case LSP lsp -> {
				return (P) lsp.getSelectedPlan();
			}
			case Receiver receiver -> {
				return (P) receiver.getSelectedPlan();
			}
			default -> {
				// Default fallback for other HasPlansAndId implementations
				return (P) delegate.getSelectedPlan();
			}
		}
	}

	@Override
	public boolean getCollaborationStatus() {
		return this.collaborationEnabled;
	}

	@Override
	public void enableCollaboration() {
		this.collaborationEnabled = true;
	}

	@Override
	public void disableCollaboration() {
		this.collaborationEnabled = false;
	}

	@Override
	public void setCollaborationPartners(Set<Id<?>> partnerIds) {
		this.collaborationPartners = partnerIds;
	}

	@Override
	public Set<Id<?>> getCollaborationPartners() {
		return this.collaborationPartners;
	}

	@Override
	public void addOriginalConnectedStakeholders(Map<CollaboratorRole, Set<Id<?>>> stakeholders) {
		this.originalConnectedStakeholders.putAll(stakeholders);
	}

	@Override
	public Map<CollaboratorRole, Set<Id<?>>> getOriginalConnectedStakeholders() {
		return this.originalConnectedStakeholders;
	}
}
