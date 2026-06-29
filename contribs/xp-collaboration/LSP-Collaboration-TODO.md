# LSP–Receiver Collaboration: Implementation To‑Do

Goal: add LSP–receiver collaboration to `org.matsim.contrib.freightcollaboration` with the same cost‑allocation flow as the existing carrier–receiver path (relax receiver TW/service time; compute coalition vs. baseline; allocate cost/savings to collaborating receivers via configurable model).

## 0) Baseline Understanding (logistics package)
- LSP agent types & plans: `org.matsim.freight.logistics.LSP`, `LSPPlan`, `LSPPlanImpl`, shipment objects in `org.matsim.freight.logistics.shipment.*`, resources in `org.matsim.freight.logistics.resourceImplementations`.
- Events & scoring: logistics events in `org.matsim.freight.logistics.events.*`; scoring via `LSPScorerFactory`; replanning via `LSPStrategyManager`.
- Tests/examples to crib behaviour: `contribs/freight/src/main/java/org/matsim/freight/logistics/examples` and unit tests under `contribs/freight/src/test/java/org/matsim/freight/logistics`.

## 1) Data & Registration
- Extend `FreightCollaboratorFactory` to robustly wrap LSP (already imports LSP, but error paths / status toggles need verifying).
- Ensure `FreightCollaborators.getFreightCollaboratorsByRole(LSP)` works (maps populated when reading LSPs from scenario).
- `CollaborationDataStore`: add support to store original `LSPPlan` snapshots and coalition/standalone scores for LSP role.

## 2) Coalition Formation
- `MutableFreightCoalition`: allow `CollaborationTypes.LSP_RECEIVER` (similar to existing enum for carrier–receiver). Add compatibility checks.
- `FormFreightCoalitionListener`: when collaboration type is LSP–receiver, build coalition with one LSP distributor and a subset of receivers as players.

## 3) Pseudo‑Simulation (PSim) for LSP
- `FreightPseudoSimulator.runPSim` currently only handles carrier–receiver; add branch for LSP–receiver:
  - Deep copy LSP and receiver collaborators (reuse lightweight copy utilities, see §6).
  - Identify non‑collaborating receivers and reset their plans from data store.
  - Trigger LSP replanning to incorporate collaborating receivers’ relaxed orders. Candidate hooks: use `LSPStrategyManager` or a minimal assignment routine (similar to `LinkReceiverAndCarrier.receiversTriggerCarrierReplan`).
  - Produce events or activity/leg lists compatible with `LSPScorerFactory`.
- If events are too heavy, mirror the carrier activity-based path: generate `FreightActivity` + MATSim legs that approximate LSP plan execution.

## 4) Scoring
- Provide `LSPPSimScorer` (parallel to `CarrierPSimScorer`) that consumes the pseudo output and delegates to `LSPScorerFactory`.
- Wire scorer binding in controler module when collaboration type includes LSP.

## 5) Allocation Models
- `AllocationUtils.extractValidPlayers/Distributors`: add `LSP_RECEIVER` case (distributor = LSP, players = receivers).
- `extractDistributorId` in `AllocationModelApproxShapleyValue` and `AllocationModelShapleyValue`: handle LSP_RECEIVER.
- Ensure cost/savings sign conventions match LSP scoring (negative cost). Keep cache keys generic (`Set<Id<?>>` of players).
- Reuse approximate Shapley + proportional + marginal models unchanged once role wiring is done.

## 6) Copying & Replanning Performance
- `AllocationUtils.deepCopyCollaborator`: implement LSP branch using existing logistics factories (create fresh `LSP`, copy attributes, clone `LSPPlan`s without scores). Avoid heavy resource cloning where possible; rely on replanning to rebuild routes.
- Add plan snapshot helper for LSP (akin to `copyNoScorePlan` for carriers).
- Provide `LinkReceiverAndLSP` utility: re‑generate LSP plan after receivers relax TW/service durations; likely needs to:
  - collect receiver orders assigned to this LSP,
  - rebuild shipments (`LspShipment`), assign to resources (carriers, hubs) using existing `ResourceImplementationUtils` builders,
  - route shipments (could reuse logistics routing utilities or a simplified shortest‑path).

## 7) Config & Strategy
- `FreightCollaborationConfigGroup`: add `LSP_RECEIVER` to enums; expose LSP-specific knobs (e.g., max iterations for LSP replanning, default penalty application mode).
- `CollaboratorStrategyManagers` / strategy enums: allow LSP strategies if needed (or stub with KeepSelected for first version).
- Example config: extend example runner to support LSP_RECEIVER with toggles for allocation method, factors, penalties.

## 8) Event / Data Export
- `CollaborationDataStoreValueWriter` and listeners should include LSP role when writing CSV/analysis outputs.
- Add diagnostics for LSP psim runtimes and cache hit rates (important for Shapley sampling).

## 9) Tests
- Unit tests:
  - `FreightPseudoSimulator` LSP branch: baseline vs. coalition score change when receivers relax TW.
  - Allocation models: shapley/proportional produce budget‑balanced split for LSP_RECEIVER toy coalition.
  - Deep copy: verify LSP plans copied without sharing references.
- Integration test: small LSP–receiver scenario (single hub, few shipments) to ensure end‑to‑end allocation runs.

## 10) Example Scenario
- Add `RunLspReceiverShapleyAllocationExample` mirroring the carrier one, with minimal network + one LSP + receivers.
- Provide sample config and run script for users to reproduce allocations.

## 11) Backlog / Stretch
- Multi‑echelon LSP chains (hub handling events, transshipments).
- Parallel execution for Shapley sampling with LSP.
- Richer receiver behaviours (order splitting, price‑responsive collaboration).

## Suggested Implementation Order
1. Wiring: enums, coalition builder, config, extract helpers, allocator distributor ID.
2. LSP deep copy + plan snapshot + minimal replanning helper (no hubs, single leg).
3. PSim + scorer path for LSP; verify scores change with relaxed TW.
4. Allocate using proportional model first; then enable marginal/approx Shapley.
5. Tests + example runner + docs.
