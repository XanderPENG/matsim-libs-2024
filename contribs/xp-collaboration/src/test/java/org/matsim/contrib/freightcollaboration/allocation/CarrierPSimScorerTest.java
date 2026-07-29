package org.matsim.contrib.freightcollaboration.allocation;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.contrib.freightcollaboration.config.FreightCollaborationConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierCapabilities;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarrierVehicle;
import org.matsim.freight.carriers.CarrierVehicleType;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.freight.carriers.ScheduledTour;
import org.matsim.freight.carriers.TimeWindow;
import org.matsim.freight.carriers.Tour;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;
import org.matsim.freight.carriers.controller.FreightActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CarrierPSimScorerTest {

	@Test
	void standardFactoryIsSupportedAndActivitiesAndLegsAreInterleaved() {
		Carrier carrier = carrierWithTours(1, 0);
		List<String> calls = new ArrayList<>();
		CarrierScoringFunctionFactory factory = ignored -> new RecordingScoring(calls, 0);
		Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> input = Map.of(1,
			new Tuple<>(List.of(activity("a"), activity("b")), List.of(PopulationUtils.createLeg("car"))));

		double score = new CarrierPSimScorer(input, carrier, factory, null).getScore();

		assertEquals(List.of("activity", "leg", "activity", "finish"), calls);
		assertEquals(3.0, score);
	}

	@Test
	void psimFactoryReceivesConfiguredModeInsteadOfConcreteFactoryCast() {
		Carrier carrier = carrierWithTours(1, 0);
		FreightCollaborationConfigGroup config = new FreightCollaborationConfigGroup();
		config.setPsimScoringModeString(FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES.name());
		AtomicInteger psimCalls = new AtomicInteger();
		CarrierPsimScoringFunctionFactory factory = new CarrierPsimScoringFunctionFactory() {
			@Override
			public ScoringFunction createPsimScoringFunction(
					Carrier ignored, FreightCollaborationConfigGroup.PsimScoringMode mode) {
				assertEquals(FreightCollaborationConfigGroup.PsimScoringMode.BASIC_PLUS_FEES, mode);
				psimCalls.incrementAndGet();
				return new RecordingScoring(new ArrayList<>(), 7);
			}

			@Override
			public ScoringFunction createScoringFunction(Carrier ignored) {
				throw new AssertionError("The standard factory method must not be used for PSim mode");
			}
		};

		assertEquals(7.0, new CarrierPSimScorer(
			Map.of(1, new Tuple<>(List.of(), List.of())), carrier, factory, config).getScore());
		assertEquals(1, psimCalls.get());
	}

	@Test
	void multipleDriversCountCarrierFixedCostsOnlyOnce() {
		Carrier carrier = carrierWithTours(2, 10);
		Map<Integer, Tuple<List<FreightActivity>, List<Leg>>> input = new LinkedHashMap<>();
		input.put(1, new Tuple<>(List.of(), List.of()));
		input.put(2, new Tuple<>(List.of(), List.of()));
		CarrierScoringFunctionFactory factory = ignored ->
			new RecordingScoring(new ArrayList<>(), -20);

		assertEquals(-20.0, new CarrierPSimScorer(input, carrier, factory, null).getScore(), 1e-12);
	}

	@Test
	void constructorRejectsMissingDependencies() {
		Carrier carrier = carrierWithTours(1, 0);
		CarrierScoringFunctionFactory factory = ignored -> new RecordingScoring(new ArrayList<>(), 0);
		assertThrows(NullPointerException.class, () -> new CarrierPSimScorer(null, carrier, factory, null));
		assertThrows(NullPointerException.class,
			() -> new CarrierPSimScorer(Map.of(), null, factory, null));
		assertThrows(NullPointerException.class,
			() -> new CarrierPSimScorer(Map.of(), carrier, null, null));
	}

	private static FreightActivity activity(String type) {
		Activity activity = PopulationUtils.createActivityFromLinkId(type, Id.createLinkId("link"));
		activity.setStartTime(0);
		activity.setEndTime(10);
		return new FreightActivity(activity, TimeWindow.newInstance(0, 100));
	}

	private static Carrier carrierWithTours(int tourCount, double fixedCost) {
		Carrier carrier = CarriersUtils.createCarrier(Id.create("carrier", Carrier.class));
		org.matsim.vehicles.VehicleType type = CarrierVehicleType.Builder.newInstance(
				Id.create("type", org.matsim.vehicles.VehicleType.class))
			.setCapacity(10)
			.setFixCost(fixedCost)
			.build();
		List<ScheduledTour> tours = new ArrayList<>();
		CarrierCapabilities.Builder capabilities = CarrierCapabilities.Builder.newInstance();
		for (int i = 0; i < tourCount; i++) {
			CarrierVehicle vehicle = CarrierVehicle.Builder.newInstance(
				Id.createVehicleId("vehicle-" + i), Id.createLinkId("depot"), type).build();
			capabilities.addVehicle(vehicle);
			Tour.Builder tourBuilder = Tour.Builder.newInstance(Id.create("tour-" + i, Tour.class));
			tourBuilder.scheduleStart(Id.createLinkId("depot"));
			tourBuilder.addLeg(new Tour.Leg());
			tourBuilder.scheduleEnd(Id.createLinkId("depot"));
			tours.add(ScheduledTour.newInstance(tourBuilder.build(), vehicle, 0));
		}
		carrier.setCarrierCapabilities(capabilities.build());
		CarrierPlan plan = new CarrierPlan(carrier, tours);
		carrier.addPlan(plan);
		carrier.setSelectedPlan(plan);
		return carrier;
	}

	private static final class RecordingScoring implements ScoringFunction {
		private final List<String> calls;
		private final double baseScore;
		private double score;

		private RecordingScoring(List<String> calls, double baseScore) {
			this.calls = calls;
			this.baseScore = baseScore;
		}

		@Override
		public void handleActivity(Activity activity) {
			calls.add("activity");
			score += 1;
		}

		@Override
		public void handleLeg(Leg leg) {
			calls.add("leg");
			score += 1;
		}

		@Override
		public void agentStuck(double time) {
		}

		@Override
		public void addMoney(double amount) {
			score += amount;
		}

		@Override
		public void addScore(double amount) {
			score += amount;
		}

		@Override
		public void finish() {
			calls.add("finish");
		}

		@Override
		public double getScore() {
			return baseScore + score;
		}

		@Override
		public void handleEvent(Event event) {
		}
	}
}
