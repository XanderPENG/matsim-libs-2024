package org.matsim.contrib.xpreceiver.examples;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;
import org.matsim.contrib.xpreceiver.config.ReceiverReplanningType;
import org.matsim.contrib.xpreceiver.control.ReceiverModule;
import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverOrder;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;
import org.matsim.contrib.xpreceiver.core.Receivers;
import org.matsim.contrib.xpreceiver.core.TimeWindow;
import org.matsim.contrib.xpreceiver.cost.ReceiverCostAllocation;
import org.matsim.contrib.xpreceiver.util.ReceiverUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.Carrier;

/**
 * Runs a small MATSim instance with the xp-receiver module enabled.
 */
public final class RunExampleChessboard {
    private RunExampleChessboard() {
    }

    public static void main(String[] args) {
        Config config = ConfigUtils.createConfig();
        ReceiverConfigGroup receiverConfig = ReceiverUtils.getConfigGroup(config);
        receiverConfig.setReceiverReplanningInterval(1);
        receiverConfig.setReplanningType(ReceiverReplanningType.timeWindow);
        receiverConfig.setTimeWindowMutationStep(600.0);

        config.controller().setLastIteration(1);
        config.controller().setOverwriteFileSetting(ConfigUtils.OverwriteFileSetting.deleteDirectoryIfExists);
        config.qsim().setEndTime(12 * 3600);
        config.qsim().setNumberOfThreads(1);

        Scenario scenario = ScenarioUtils.createScenario(config);
        buildNetwork(scenario.getNetwork());
        addReceiver(scenario);

        Controler controler = new Controler(scenario);
        ReceiverCostAllocation costAllocation = ReceiverUtils.createEqualProportionCostAllocation();
        controler.addOverridingModule(new ReceiverModule(costAllocation));
        controler.run();
    }

    private static void buildNetwork(Network network) {
        Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("1"), 0.0, 0.0);
        Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("2"), 1000.0, 0.0);
        NetworkUtils.createAndAddLink(network, Id.createLinkId("1-2"), n1, n2, 1000.0, 50.0, 1000.0, 1.0);
        NetworkUtils.createAndAddLink(network, Id.createLinkId("2-1"), n2, n1, 1000.0, 50.0, 1000.0, 1.0);
    }

    private static void addReceiver(Scenario scenario) {
        Receivers receivers = ReceiverUtils.createReceivers();
        Receiver receiver = ReceiverUtils.newReceiver(Id.create("receiver-1", Receiver.class));
        receiver.setLinkId(Id.createLinkId("1-2"));

        ReceiverPlan plan = new ReceiverPlan();
        plan.setType("baseline");
        plan.addOrder(ReceiverOrder.builder(Id.create("carrier-1", Carrier.class))
                .setDeliveryLinkId(Id.createLinkId("1-2"))
                .setQuantity(5.0)
                .setServiceDuration(300.0)
                .setCarrierCost(120.0)
                .setTimeWindow(TimeWindow.of(8 * 3600, 10 * 3600))
                .build());

        receiver.addPlan(plan);
        receiver.setSelectedPlan(plan);
        receivers.addReceiver(receiver);
        ReceiverUtils.setReceivers(receivers, scenario);
    }
}
