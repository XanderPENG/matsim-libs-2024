package org.matsim.contrib.xpreceiver.control;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.contrib.xpreceiver.config.ReceiverConfigGroup;
import org.matsim.contrib.xpreceiver.config.ReceiverReplanningType;
import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.contrib.xpreceiver.core.ReceiverPlan;
import org.matsim.contrib.xpreceiver.core.Receivers;
import org.matsim.contrib.xpreceiver.cost.ReceiverCostAllocation;
import org.matsim.contrib.xpreceiver.replanning.ReceiverStrategyManager;
import org.matsim.contrib.xpreceiver.scoring.ReceiverScoringFunctionFactory;
import org.matsim.contrib.xpreceiver.util.ReceiverUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ScoringEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ScoringListener;

/**
 * Minimal controler listener that scores receivers and triggers replanning.
 */
final class ReceiverControlerListener implements IterationStartsListener, ScoringListener {
    private final ReceiverStrategyManager strategyManager;
    private final ReceiverScoringFunctionFactory scoringFunctionFactory;
    private final ReceiverCostAllocation costAllocation;

    private final Scenario scenario;
    private final ReceiverConfigGroup configGroup;

    @Inject
    ReceiverControlerListener(ReceiverStrategyManager strategyManager,
                              ReceiverScoringFunctionFactory scoringFunctionFactory,
                              ReceiverCostAllocation costAllocation,
                              Scenario scenario,
                              ReceiverConfigGroup configGroup) {
        this.strategyManager = strategyManager;
        this.scoringFunctionFactory = scoringFunctionFactory;
        this.costAllocation = costAllocation;
        this.scenario = scenario;
        this.configGroup = configGroup;
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        if (configGroup.getReplanningType() == ReceiverReplanningType.none) {
            return;
        }
        if (event.getIteration() == 0) {
            return;
        }
        if (event.getIteration() % configGroup.getReceiverReplanningInterval() != 0) {
            return;
        }
        Receivers receivers = ReceiverUtils.getReceivers(scenario);
        strategyManager.run(receivers.values(), event.getIteration());
    }

    @Override
    public void notifyScoring(ScoringEvent event) {
        Receivers receivers = ReceiverUtils.getReceivers(scenario);
        for (Receiver receiver : receivers.values()) {
            ReceiverPlan plan = receiver.getSelectedPlan();
            if (plan == null) {
                continue;
            }
            double cost = costAllocation.calculateCost(receiver, plan);
            ScoringFunction scoringFunction = scoringFunctionFactory.createScoringFunction(receiver);
            scoringFunction.addMoney(-cost);
            scoringFunction.finish();
            plan.setScore(scoringFunction.getScore());
            receiver.setInitialCost(cost);
        }
    }
}
