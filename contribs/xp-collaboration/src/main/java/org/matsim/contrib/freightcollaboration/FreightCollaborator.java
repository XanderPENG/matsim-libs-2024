package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;

import java.util.List;

public interface FreightCollaborator<T extends HasPlansAndId<?, ?>> {

	/**
	 * Get the delegate object (e.g., Carrier, LSP, Receiver).
	 */
	T getDelegate();

	/**
	 * Get the Id of the delegate.
	 */
	default Id<?> getId() {
		return getDelegate().getId();
	}
	/**
	 * Get the role of the collaborator (e.g., "Carrier", "LSP", "Receiver").
	 */
	CollaboratorRole getRole();

	/**
	 * Gets the Plans of the delegate.
	 */
	default List<? extends BasicPlan> getPlans() {
		return getDelegate().getPlans();
	}

	/**
	 * Get the selected plan of the delegate.
	 */
	default BasicPlan getSelectedPlan() {
		return getDelegate().getSelectedPlan();
	}

	/**
	 * Get the selected plan of the delegate with its specific type (CarrierPlan, LSPPlan, etc.)
	 * @return The typed plan, or null if the type is not recognized
	 * @throws ClassCastException if the plan is not of the expected type
	 */
	<P extends BasicPlan> P getTypedSelectedPlan();

	void enableCollaboration();

	void disableCollaboration();
}
