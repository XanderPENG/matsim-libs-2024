package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.contrib.freightcollaboration.CollaboratorRole;
import org.matsim.contrib.freightcollaboration.MutableFreightCoalition;
import org.matsim.contrib.freightcollaboration.allocation.CollaborationDataStore;
import org.matsim.contrib.freightcollaboration.controller.FreightCoalitionManager;
import org.matsim.contrib.freightcollaboration.strategy.CarrierAllocationFactor;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.freight.carriers.Carrier;
import org.matsim.freight.carriers.CarrierPlan;
import org.matsim.freight.carriers.CarriersUtils;
import org.matsim.api.core.v01.Scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;

/** Writes mutable allocation-factor diagnostics without changing existing collaboration XML. */
public final class MutableAllocationFactorStatsListener implements StartupListener, IterationEndsListener {

	public static final String OUTPUT_FILE = "mutable_allocation_factor_stats.csv";

	private final Scenario scenario;
	private final OutputDirectoryHierarchy output;
	private final CollaborationDataStore dataStore;
	private final FreightCoalitionManager coalitionManager;

	@Inject
	public MutableAllocationFactorStatsListener(Scenario scenario, OutputDirectoryHierarchy output,
			CollaborationDataStore dataStore, FreightCoalitionManager coalitionManager) {
		this.scenario = java.util.Objects.requireNonNull(scenario, "scenario");
		this.output = java.util.Objects.requireNonNull(output, "output");
		this.dataStore = java.util.Objects.requireNonNull(dataStore, "dataStore");
		this.coalitionManager = java.util.Objects.requireNonNull(coalitionManager, "coalitionManager");
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		try (BufferedWriter writer = IOUtils.getBufferedWriter(output.getOutputFilename(OUTPUT_FILE))) {
			writer.write("iteration,carrierId,selectedFactor,selectedPlanScore,bestStoredFactor,bestStoredScore,"
				+ "activeCoalition,receiverCount,signedTransfer\n");
		} catch (IOException e) {
			throw new UncheckedIOException("Could not initialize mutable allocation-factor stats.", e);
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		try (BufferedWriter writer = IOUtils.getAppendingBufferedWriter(output.getOutputFilename(OUTPUT_FILE))) {
			for (Carrier carrier : CarriersUtils.getCarriers(scenario).getCarriers().values().stream()
				.sorted(Comparator.comparing(c -> c.getId().toString())).toList()) {
				writeCarrier(writer, event.getIteration(), carrier);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not append mutable allocation-factor stats.", e);
		}
	}

	private void writeCarrier(BufferedWriter writer, int iteration, Carrier carrier) throws IOException {
		CarrierPlan selected = carrier.getSelectedPlan();
		if (selected == null) {
			throw new IllegalStateException("Carrier has no selected plan at iteration end: " + carrier.getId());
		}
		CarrierPlan best = carrier.getPlans().stream()
			.filter(plan -> plan.getScore() != null && !plan.getScore().isNaN())
			.max(Comparator.comparingDouble(CarrierPlan::getScore))
			.orElse(selected);

		List<MutableFreightCoalition> coalitions = coalitionManager.getMutableFreightCoalitions();
		List<MutableFreightCoalition> carrierCoalitions = coalitions == null ? List.of() : coalitions.stream()
			.filter(coalition -> coalition.contains(CollaboratorRole.CARRIER, carrier.getId()))
			.toList();
		int receiverCount = carrierCoalitions.stream()
			.mapToInt(coalition -> coalition.getCollaboratorsSetByRole(CollaboratorRole.RECEIVER).size())
			.sum();

		writer.write(iteration + "," + csv(carrier.getId().toString()) + ","
			+ CarrierAllocationFactor.require(selected) + "," + nullable(selected.getScore()) + ","
			+ CarrierAllocationFactor.require(best) + "," + nullable(best.getScore()) + ","
			+ (!carrierCoalitions.isEmpty()) + "," + receiverCount + ","
			+ dataStore.getDistributorPlayerTransfer(CollaboratorRole.CARRIER, carrier.getId()) + "\n");
	}

	private static String nullable(Double value) {
		return value == null ? "" : value.toString();
	}

	private static String csv(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
