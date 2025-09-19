package org.matsim.contrib.xpreceiver.scoring;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.xpreceiver.core.Receiver;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.SumScoringFunction.MoneyScoring;

/**
 * Simple money-only scoring for receivers.
 */
public final class ReceiverScoringFunctionFactoryMoneyOnly implements ReceiverScoringFunctionFactory {
    private static final Logger LOG = LogManager.getLogger(ReceiverScoringFunctionFactoryMoneyOnly.class);

    @Override
    public ScoringFunction createScoringFunction(Receiver receiver) {
        SumScoringFunction sum = new SumScoringFunction();
        sum.addScoringFunction(new CarrierChargeScoring());
        return sum;
    }

    private static final class CarrierChargeScoring implements MoneyScoring {
        private double balance = 0.0;

        @Override
        public void addMoney(double amount) {
            if (amount < 0) {
                LOG.debug("Receiver paying {} monetary units", amount);
            }
            balance += amount;
        }

        @Override
        public void finish() {
        }

        @Override
        public double getScore() {
            return balance;
        }
    }
}
