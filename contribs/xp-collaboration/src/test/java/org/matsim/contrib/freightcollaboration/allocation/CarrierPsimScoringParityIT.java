package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.contrib.freightcollaboration.run.ScoringFunctionFactoryUsecase;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.controller.FreightActivity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarrierPsimScoringParityIT {

	@Test
	void normalAndPsimActivityScoringProduceTheSameScore() {
		Carrier carrier = FreightCollaborationTestFixtures.carrier("carrier");
		FreightActivity activity = activity(120, 150, TimeWindow.newInstance(0, 100));
		Leg leg = PopulationUtils.createLeg("car");
		CarrierPsimScoringFunctionFactory factory = new CarrierPsimScoringFunctionFactory() {
			@Override
			public ScoringFunction createPsimScoringFunction(
					Carrier ignored, FreightCollaborationConfigGroup.PsimScoringMode mode) {
				return scoringFunction();
			}

			@Override
			public ScoringFunction createScoringFunction(Carrier ignored) {
				return scoringFunction();
			}
		};

		ScoringFunction normal = factory.createScoringFunction(carrier);
		normal.handleActivity(activity);
		normal.handleLeg(leg);
		normal.finish();
		double psim = new CarrierPSimScorer(
			Map.of(1, new Tuple<>(List.of(activity), List.of(leg))), carrier, factory,
			new FreightCollaborationConfigGroup()).getScore();

		assertEquals(normal.getScore(), psim, 1e-12);
	}

	private static ScoringFunction scoringFunction() {
		SumScoringFunction scoring = new SumScoringFunction();
		scoring.addScoringFunction(
			new ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase.SimpleDriversActivityScoring());
		return scoring;
	}

	private static FreightActivity activity(double start, double end, TimeWindow timeWindow) {
		Activity activity = PopulationUtils.createActivityFromLinkId("delivery", Id.createLinkId("link"));
		activity.setStartTime(start);
		activity.setEndTime(end);
		return new FreightActivity(activity, timeWindow);
	}
}
