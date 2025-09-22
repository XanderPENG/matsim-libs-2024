package org.matsim.contrib.freightcollaboration;

import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.receiver.Receiver;

public class FreightCollaboratorFactory {
	public static <T extends HasPlansAndId<?, ?>> FreightCollaborator<T> createCollaborator(T delegate) {
		return switch (delegate) {
			case Carrier carrier -> new FreightCollaboratorImpl<>(delegate, CollaboratorRole.CARRIER);
			case LSP lsp -> new FreightCollaboratorImpl<>(delegate, CollaboratorRole.LSP);
			case Receiver receiver -> new FreightCollaboratorImpl<>(delegate, CollaboratorRole.RECEIVER);
			case null, default -> {
				assert delegate != null;
				throw new IllegalArgumentException("Unsupported delegate type: " + delegate.getClass().getName());
			}
		};
	}
}
