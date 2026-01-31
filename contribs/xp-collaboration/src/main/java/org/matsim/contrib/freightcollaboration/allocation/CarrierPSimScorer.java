package org.matsim.contrib.freightcollaboration.allocation;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.contrib.freightcollaboration.run.ScoringFunctionFactoryUsecase;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.FreightActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class CarrierPSimScorer {
	Map<Integer, ScoringFunction> scoringFunctions = null;

	Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> driverLegsAndActivities;
	Carrier carrier;
	FreightCollaborationConfigGroup freightConfig;

//	@Inject
	CarrierScoringFunctionFactory carrierScoringFunctionFactory;

	CarrierPSimScorer(Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> driverLegsAndActivities, Carrier carrier,
					  CarrierScoringFunctionFactory carrierScoringFunctionFactory,
					  FreightCollaborationConfigGroup freightConfig) {
		this.driverLegsAndActivities = driverLegsAndActivities;
		this.carrier = carrier;
		this.carrierScoringFunctionFactory = carrierScoringFunctionFactory;
		this.freightConfig = freightConfig;
	}

	private void initScoringFunctions(){
		scoringFunctions = new HashMap<>();
		for(Integer driverId : driverLegsAndActivities.keySet()) {
			// create scoring function for each driver, only considering cost components
			// TODO: make this more general if other scoring functions are needed
			ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase factory =
				(ScoringFunctionFactoryUsecase.CarrierScoringFunctionFactoryUsecase) carrierScoringFunctionFactory;
			ScoringFunction scoringFunction;
			if (freightConfig != null
				&& freightConfig.getPsimScoringMode() == FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES) {
				scoringFunction = factory.createBasicCostPlusFeesScoringFunction(carrier);
			} else {
				scoringFunction = factory.createBasicCostScoringFunction(carrier);
			}
			scoringFunctions.put(driverId, scoringFunction);
		}
	}

	public double getScore() {
		// init scoring functions if not yet done
		if(scoringFunctions == null) {
			initScoringFunctions();
		}
		// calculate total score
		double totalScore = 0.0;
		// iterate over drivers
		for(Integer driverId : driverLegsAndActivities.keySet()) {
			ScoringFunction scoringFunction = scoringFunctions.get(driverId);
			Tuple<List<FreightActivity>, List<Leg>> legsAndActivities = driverLegsAndActivities.get(driverId);
			// get activities and legs
			List<FreightActivity> activities = legsAndActivities.getFirst();
			List<Leg> legs = legsAndActivities.getSecond();
			int activityIndex = 0;
			int legIndex = 0;
			// Assuming that activities and legs are interleaved, starting and ending with an activity
			while(activityIndex < activities.size() || legIndex < legs.size()) {
				if(activityIndex < activities.size()) {
					scoringFunction.handleActivity(activities.get(activityIndex));
					activityIndex++;
				}
				if(legIndex < legs.size()) {
					scoringFunction.handleLeg(legs.get(legIndex));
					legIndex++;
				}
			}
			// Lastly, using getScore and finish to finalize scoring
			scoringFunction.finish();
			totalScore += scoringFunction.getScore();
		}
		// Fixed vehicle costs are carrier-level and should only be counted once.
		int driverCount = driverLegsAndActivities.size();
		if (driverCount > 1) {
			double fixedCost = 0.0;
			CarrierPlan selectedPlan = carrier.getSelectedPlan();
			if (selectedPlan != null) {
				for (ScheduledTour tour : selectedPlan.getScheduledTours()) {
					if (!tour.getTour().getTourElements().isEmpty()) {
						fixedCost += (-1) * tour.getVehicle().getType().getCostInformation().getFixedCosts();
					}
				}
			}
			totalScore -= (driverCount - 1) * fixedCost;
		}
		return totalScore;
	}

}
