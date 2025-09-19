package org.matsim.contrib.freightcollaboration;

public enum CollaborationTypes implements CollaborationType {

	CARRIER_CARRIER("Carrier-Carrier", "Collaboration between carriers for joint deliveries") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return role1 == CollaboratorRole.CARRIER && role2 == CollaboratorRole.CARRIER;
		}
	},

	RECEIVER_RECEIVER("Receiver-Receiver", "Collaboration between receivers for consolidated deliveries") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return role1 == CollaboratorRole.RECEIVER && role2 == CollaboratorRole.RECEIVER;
		}
	},

	LSP_LSP("LSP-LSP", "Collaboration between logistics service providers for sharing resources") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return role1 == CollaboratorRole.LSP && role2 == CollaboratorRole.LSP;
		}
	},

	CARRIER_LSP("Carrier-LSP", "Collaboration between carriers and logistics service providers") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return (role1 == CollaboratorRole.CARRIER && role2 == CollaboratorRole.LSP) ||
				(role1 == CollaboratorRole.LSP && role2 == CollaboratorRole.CARRIER);
		}
	},

	CARRIER_RECEIVER("Carrier-Receiver", "Collaboration between carriers and receivers") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return (role1 == CollaboratorRole.CARRIER && role2 == CollaboratorRole.RECEIVER) ||
				(role1 == CollaboratorRole.RECEIVER && role2 == CollaboratorRole.CARRIER);
		}
	},

	LSP_RECEIVER("LSP-Receiver", "Collaboration between logistics service providers and receivers") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return (role1 == CollaboratorRole.LSP && role2 == CollaboratorRole.RECEIVER) ||
				(role1 == CollaboratorRole.RECEIVER && role2 == CollaboratorRole.LSP);
		}
	},

	MIXED("Mixed", "Mixed collaboration between different types of entities") {
		@Override
		public boolean isCompatible(CollaboratorRole role1, CollaboratorRole role2) {
			return true; // Mixed type allows any combination
		}
	};

	private final String type;
	private final String description;

	CollaborationTypes(String type, String description) {
		this.type = type;
		this.description = description;
	}

	@Override
	public String getType() {
		return type;
	}

	@Override
	public String getDescription() {
		return description;
	}
}

