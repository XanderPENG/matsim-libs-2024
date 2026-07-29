package org.matsim.contrib.freightcollaboration.listener;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.contrib.freightcollaboration.CollaboratorKey;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.FreightCollaborationTestFixtures;
import org.matsim.contrib.freightcollaboration.FreightCollaborators;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.run.CreateFreightChessboardNetwork;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.freight.carriers.controller.CarrierScoringFunctionFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreightCollaborationListenerTest {

	@Test
	void firstIterationSkipsEngineAndAlwaysRemovesTravelTimeHandler() {
		AtomicInteger runs = new AtomicInteger();
		TestContext context = context(List.of(FreightCollaborationTestFixtures.carrierReceiverCoalition("c", "r")),
			(args) -> runs::incrementAndGet);
		context.dataStore.setAllocatedValues(Map.of(
			new CollaboratorKey(CollaboratorRole.CARRIER,
				FreightCollaborationTestFixtures.carrier("c").getId()), 5.0));

		context.listener.notifyIterationStarts(new IterationStartsEvent(null, 0, false));
		context.listener.notifyAfterMobsim(new AfterMobsimEvent(null, 0, false));

		assertEquals(0, runs.get());
		assertEquals(1, context.events.added);
		assertEquals(1, context.events.removed);
		assertEquals(1, context.dataStore.getAllocatedValues().size(),
			"First-iteration baseline state must not be reset by the skipped collaboration");
	}

	@Test
	void laterIterationResetsStoreAndSkipsMissingOrSingletonCoalitions() {
		for (List<MutableFreightCoalition> coalitions : List.of(
			List.<MutableFreightCoalition>of(),
			List.of(singletonCoalition()))) {
			AtomicInteger runs = new AtomicInteger();
			TestContext context = context(coalitions, args -> runs::incrementAndGet);
			context.dataStore.setAllocatedValues(Map.of(
				new CollaboratorKey(CollaboratorRole.CARRIER,
					FreightCollaborationTestFixtures.carrier("c").getId()), 5.0));

			context.listener.notifyIterationStarts(new IterationStartsEvent(null, 1, false));
			context.listener.notifyAfterMobsim(new AfterMobsimEvent(null, 1, false));

			assertEquals(0, runs.get());
			assertNull(context.dataStore.getAllocatedValues());
			assertEquals(1, context.events.removed);
		}
	}

	@Test
	void executesCollaborationAndCleansUpEvenWhenEngineFails() {
		AtomicInteger runs = new AtomicInteger();
		TestContext context = context(List.of(FreightCollaborationTestFixtures.carrierReceiverCoalition("c", "r")),
			args -> () -> {
				runs.incrementAndGet();
				throw new IllegalStateException("engine failed");
			});

		context.listener.notifyIterationStarts(new IterationStartsEvent(null, 1, false));
		assertThrows(IllegalStateException.class,
			() -> context.listener.notifyAfterMobsim(new AfterMobsimEvent(null, 1, false)));
		assertEquals(1, runs.get());
		assertEquals(1, context.events.removed);

		context.listener.notifyIterationStarts(new IterationStartsEvent(null, 2, false));
		assertEquals(2, context.events.added,
			"A failed engine must not leave the listener stuck with the prior iteration handler");
	}

	@Test
	void afterMobsimWithoutIterationStartFailsClearly() {
		TestContext context = context(List.of(), args -> () -> {
		});
		assertThrows(IllegalStateException.class,
			() -> context.listener.notifyAfterMobsim(new AfterMobsimEvent(null, 1, false)));
	}

	private static MutableFreightCoalition singletonCoalition() {
		MutableFreightCoalition coalition =
			new MutableFreightCoalition(org.matsim.contrib.freightcollaboration.CollaborationTypes.CARRIER_RECEIVER);
		coalition.addCollaborator(FreightCollaborationTestFixtures.carrierCollaborator("c"));
		return coalition;
	}

	private static TestContext context(List<MutableFreightCoalition> coalitions,
									   RunnerFactoryAdapter runnerFactory) {
		Config config = ConfigUtils.createConfig();
		config.controller().setFirstIteration(0);
		Scenario scenario = ScenarioUtils.createScenario(config);
		FreightCollaborators collaborators = new FreightCollaborators();
		CollaborationDataStore dataStore = FreightCollaborationTestFixtures.emptyDataStore();
		FreightCoalitionManager manager = new FreightCoalitionManager();
		manager.setMutableFreightCoalitions(coalitions);
		TrackingEventsManager events = new TrackingEventsManager();
		CarrierScoringFunctionFactory scoringFactory = carrier -> null;
		FreightCollaborationListener listener = new FreightCollaborationListener(events,
			CreateFreightChessboardNetwork.createNetwork(2), config, scenario, collaborators, dataStore, manager,
			scoringFactory, (cfg, sc, cs, ds, c, tt, sf) -> runnerFactory.create(c));
		return new TestContext(listener, events, dataStore);
	}

	private record TestContext(FreightCollaborationListener listener, TrackingEventsManager events,
							   CollaborationDataStore dataStore) {
	}

	@FunctionalInterface
	private interface RunnerFactoryAdapter {
		Runnable create(List<MutableFreightCoalition> coalitions);
	}

	private static final class TrackingEventsManager implements EventsManager {
		private int added;
		private int removed;

		@Override
		public void processEvent(Event event) {
		}

		@Override
		public void addHandler(EventHandler handler) {
			added++;
		}

		@Override
		public void removeHandler(EventHandler handler) {
			removed++;
		}

		@Override
		public void resetHandlers(int iteration) {
		}

		@Override
		public void initProcessing() {
		}

		@Override
		public void afterSimStep(double time) {
		}

		@Override
		public void finishProcessing() {
		}
	}
}
