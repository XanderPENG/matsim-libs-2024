# LSP two‑echelon scheduling flow (current implementation)

This document explains how the freightcollaboration package generates and scores LSP plans in the two‑echelon-with-hub setup, and why receiver time‑window changes currently do not alter LSP scores.

## Pipeline at a glance

1. **Inputs**
   - `LSPPlan` with a chain of `LogisticChainElement`s (e.g., main‑run carrier → hub → distribution carrier).
   - `LspShipment`s carrying pickup TW, delivery TW, service times, capacity.
   - `LSPResource`s bound to chain elements and holding schedulers.

2. **Simple forward hand‑off** (`SimpleForwardLogisticChainScheduler`, created via `ResourceImplementationUtils.createDefaultSimpleForwardLogisticChainScheduler`)
   - Drops all shipments into the first chain element’s incoming queue.
   - Iterates resources in the provided order and calls `resource.schedule(bufferTime, lspPlan)`.
   - Does not inspect time windows or feasibility; defers everything to resource schedulers.

3. **Resource schedulers (where routing happens)**
   - **DistributionCarrierScheduler** (`resourceImplementations.DistributionCarrierScheduler`)
     - Converts each `LspShipment` to a `CarrierService` or `CarrierShipment` *without setting time windows* (see TODOs in `convertToCarrierService` / `convertToCarrierShipment`).
     - Calls `CarrierSchedulerUtils.solveVrpWithJsprit(...)`; jsprit runs without TW constraints → tours ignore TW changes.
     - Builds a new `CarrierPlan`, sets it selected, creates `LspShipmentPlanElement`s using expected times from the VRP solution.
   - **MainRunCarrierScheduler / CollectionCarrierScheduler** follow the same pattern; TWs are not propagated.
   - **TransshipmentHubScheduler** forwards shipments; no TW checks.

4. **Plan replay and scoring** (used by `FreightPseudoSimulator.runLspPseudoSimAndScoring`)
   - `runActivityBasedCarrierSimulation` replays each carrier’s selected plan, using the expected departure/arrival times stored in that plan.
   - `CarrierPSimScorer` scores legs/activities; `runLspPseudoSimAndScoring` sums carrier scores for the LSP score.
   - If tours do not change, scores do not change across coalitions.

5. **Coalition sampling** (`FreightPseudoSimulator`)
   - For each sub‑coalition it deep‑copies collaborators, triggers replans, then runs the pseudo‑sim + scoring described above.
   - Note: `AllocationUtils.deepCopyCollaborator` currently returns the original LSP collaborator (no clone), so state can bleed across samples; this still does not fix TW invisibility.

## Two‑echelon + hub example (current behavior)

- Chain: Main‑run carrier → Hub → Distribution carrier.
- Shipments: pickup TW 00:00–12:00, delivery TW 06:00–08:00, service time from orders.
- Forward scheduler passes all shipments to main‑run; resources scheduled in order.
- Distribution scheduler converts shipments to carrier jobs **without TWs** → jsprit finds the cheapest unconstrained tour.
- P‑sim replays that tour; carrier score identical regardless of receiver TW changes; LSP score = sum of identical carrier scores.

## Limitations in the current code

- Time windows on `LspShipment` are dropped before VRP construction, so tours ignore receiver TW changes.
- Resource schedulers do not enforce feasibility against TWs or service durations beyond capacity and sequence.
- LSP collaborators are not deep‑copied in coalition sampling, so replans can overwrite shared state (secondary issue).

## What would make TWs matter (concept only)

1. When converting `LspShipment` to `CarrierService`/`CarrierShipment`, set delivery (and pickup) TWs and service durations.
2. Ensure `CarrierSchedulerUtils.solveVrpWithJsprit` receives those TWs (jsprit supports TW constraints).
3. Optionally add feasibility checks or waiting logic in resource schedulers.
4. Clone LSPs per sub‑coalition to avoid cross‑sample contamination.

With TWs propagated, changing receiver time windows would alter the VRP solution, change carrier plan timings, and therefore change LSP scores in the pseudo‑sim.

