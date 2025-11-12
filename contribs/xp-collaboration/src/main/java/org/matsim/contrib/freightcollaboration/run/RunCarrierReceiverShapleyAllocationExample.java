package org.matsim.contrib.freightcollaboration.run;

import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.freightcollaboration.CollaborationTypes;
import org.matsim.contrib.freightcollaboration.config.CollaborationParamSet;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.strategy.CollaborationStrategies;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;

import java.net.URL;
import java.util.Set;

public class RunCarrierReceiverShapleyAllocationExample {

	public static void main(String[] args) {
		// Create basic and freight collaboration config
		Config config = createExampleConfigWithDefaultNetwork();
		config.addModule(createExampleFreightCollaborationConfig());

		Scenario scenario = ScenarioUtils.loadScenario(config);

		// Generate Carriers

		// Generate Receivers

		// Init controller and bind required modules

		// Run the scenario
	}

	static Config createExampleConfigWithDefaultNetwork() {
		URL context = ExamplesUtils.getTestScenarioURL("freight-chessboard-9x9");
		Config config = ConfigUtils.createConfig();
		config.setContext(context);
		config.network().setInputFile("grid9x9.xml");
		config.controller().setOutputDirectory("output/carrierReceiverShapleyExample/");
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
		config.controller().setFirstIteration(0);
		config.controller().setLastIteration(10);
		return config;
	}

	static FreightCollaborationConfigGroup createExampleFreightCollaborationConfig() {
		// Create CollaboratorParameterSet
		CollaborationParamSet collaborationParamSet = new CollaborationParamSet(CollaborationTypes.CARRIER_RECEIVER,
			Set.of(CollaborationStrategies.RECEIVER_TIME_WINDOW_MUTATION, CollaborationStrategies.COLLABORATION_STATUS_MUTATION));
		return new FreightCollaborationConfigGroup(Set.of(collaborationParamSet), null);
	}

}
