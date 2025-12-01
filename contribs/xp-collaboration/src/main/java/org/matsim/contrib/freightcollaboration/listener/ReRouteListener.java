package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.utils.LinkReceiverAndLsp;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.freight.carriers.CarrierPlanWriter;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.logistics.LSP;
import org.matsim.freight.logistics.LSPUtils;
import org.matsim.freight.logistics.io.LSPPlanXmlWriter;
import org.matsim.freight.receiver.ReceiverUtils;
import org.matsim.freight.receiver.ReceiversWriter;

import static org.matsim.contrib.freightcollaboration.CollaborationTypes.LSP_RECEIVER;

/**
 * This is a generic listener for re-routing followers when player's plan changed.
 * It should be collaboration type-based
 * Currently, only the LSP-receiver type will be implemented.
 */
public class ReRouteListener implements BeforeMobsimListener {

	@Inject
	Scenario scenario;

	@Inject
	Config config;

	@Inject
	FreightCollaborators freightCollaborators;

	@Inject
	OutputDirectoryHierarchy controlerIO;

	@Override
	public double priority() {
		return BeforeMobsimListener.super.priority();
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		FreightCollaborationConfigGroup fccg = (FreightCollaborationConfigGroup) config.getModules().get(FreightCollaborationConfigGroup.GROUP_NAME);
		// For each collaboration type defined in the config, call its specific pre-reroute
		fccg.getCollaborationParamSets().forEach(paramSet -> {
			switch (paramSet.getCollaborationType()) {
				case LSP_RECEIVER:
					var receiverCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.RECEIVER);
					var lspCollaborators = freightCollaborators.getFreightCollaboratorsByRole(CollaboratorRole.LSP);

					// For each LSP, find its linked receivers

					// Call the @LinkReceiverAndLsp.receiversTriggerLspReplan to create a new LSP with updated plan

					/*
					1. first try to replace the old LSP with the new LSP (however, it might errors)
					2. If it does not work, we can only try to add the new LSP's plan to the old LSP,
						and set it as the selected plan.
						We might need to clean up the old plan to avoid confusion.
					Note: If the method 2 is chosen, we might need to rebuild/update the LSP at the last iteration,
					 	as those shipments/resources details will not be changed accordingly.
					 	(e.g., receivers changed TW and we updated the LSP plan, while the LSPShipment in the plan will not be changed)

					 */


					break;
			default:
				throw new IllegalStateException("Unexpected value: " + paramSet.getCollaborationType());
			}
		});

		writeCollaboratorPlans(event.getIteration());
	}

	private void writeCollaboratorPlans(int iteration) {
		/*
		TODO: Check whether the default LSP's writing sequence is later than this listener;
		    If so, we might need to adjust the priority of this listener to make sure
		    the updated LSP plans are written correctly.
		 */

//		String outputdirectory = config.controller().getOutputDirectory();
//		outputdirectory += outputdirectory.endsWith("/") ? "" : "/";
//		new CarrierPlanWriter(CarriersUtils.getCarriers(scenario)).write(outputdirectory +"./receivers.xml.gz" );
//		new ReceiversWriter( ReceiverUtils.getReceivers(scenario) ).write(outputdirectory + "./carriers.xml.gz");
//		new LSPPlanXmlWriter(LSPUtils.getLSPs(scenario)).write(outputdirectory + "./lsps.xml.gz");
		String carrierFilename = controlerIO.getIterationFilename(iteration, "carriers.xml.gz");
		new CarrierPlanWriter(CarriersUtils.getCarriers(scenario)).write(carrierFilename);

		String receiverFilename = controlerIO.getIterationFilename(iteration, "receivers.xml.gz");
		new ReceiversWriter( ReceiverUtils.getReceivers(scenario) ).write(receiverFilename);

		String lspFilename = controlerIO.getIterationFilename(iteration, "lsps.xml.gz");
		new LSPPlanXmlWriter(LSPUtils.getLSPs(scenario)).write(lspFilename);

	}
}
