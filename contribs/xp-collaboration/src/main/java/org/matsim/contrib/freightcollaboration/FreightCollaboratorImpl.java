package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;

public class FreightCollaboratorImpl<T extends HasPlansAndId<?, ?>> implements FreightCollaborator<T> {

	private final T delegate;
	private final CollaboratorRole role;
	private boolean collaborationEnabled = true;

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
	public void enableCollaboration() {
		this.collaborationEnabled = true;
	}

	@Override
	public void disableCollaboration() {
		this.collaborationEnabled = false;
	}
}
