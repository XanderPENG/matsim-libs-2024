**Purpose**
- Define a cohesive structure for cost allocation in freight collaboration using a lightweight pseudo-simulation to generate events for scoring, then allocate costs across collaborators.
- Target initial focus: carriers’ plans; extensible to LSP/receiver.

**Context Review (Existing Code)**
- `org.matsim.contrib.freightcollaboration` already provides:
  - Roles and types: `CollaboratorRole`, `CollaborationType`, `CollaborationTypes`.
  - Collaborator wrapping and registry: `FreightCollaborator`, `FreightCollaboratorImpl`, `FreightCollaborators`.
  - Coalitions: `GrandFreightCoalition`, `MutableFreightCoalition`.
  - Config + strategy placeholders: `FreightCollaborationConfigGroup`, `CollaborationParamSet`, `CollaboratorStrategyManagers`.
  - Listeners with travel time capture hook: `FreightCollaborationListener` builds a `TravelTimeCalculator` each iteration.
  - Allocation package stubs: `CollaborationDataStore`, `FreightPseudoSimulator`.
- Relevant freight contrib utilities for event-based scoring:
  - Events: `CarrierTourStartEvent`, `CarrierTourEndEvent`, plus standard `LinkEnterEvent`.
  - Sample scorer: `EventBasedCarrierScorer4MultipleChains` shows minimal event set to compute time/distance/fixed costs.

**High-Level Flow**
- Baseline scoring: pseudo-simulate each collaborator’s standalone plan(s) → emit basic events → compute standalone cost/score.
- Coalition scoring: pseudo-simulate coalition plan(s) with collaboration changes → emit same events → compute coalition cost/score.
- Allocation: feed standalone and coalition results to an allocation model (e.g., proportional savings, marginal contribution approximation, Shapley sampling) → return per-collaborator allocation result + diagnostics.

**Package Structure**
- `allocation.core`
  - `CostAllocationEngine` — orchestrates end-to-end runs (baseline/coalition), scoring, and allocation.
  - `AllocationRequest` — input DTO: which coalition, which collaborators, config flags.
  - `AllocationResult` — output DTO: per-collaborator allocations, totals, diagnostics.
  - `RunArtifacts` — internal: references to generated events, scores, timing.
- `allocation.data`
  - `CollaborationDataStore` (existing) — store original plans, snapshots, and run results (see enhancements below).
  - `PlanSnapshot` — immutable snapshot of a collaborator’s selected plan (deep copy + metadata).
  - `ScoreResult` — per-collaborator score breakdown (time, distance, fixed, toll, penalties, total).
  - `CoalitionScoreBundle` — aggregates `ScoreResult`s for a run (baseline or coalition).
- `allocation.sim`
  - `FreightPseudoSimulator` (existing) — drives pseudo-simulation for a set of plans.
  - `PseudoEventsPlayer` — converts plans into events (tour start/end, link enter sequence, pickups/deliveries optional).
  - `PseudoSimConfig` — configuration options (event granularity, include service events, route mode, time resolution).
  - `RouteResolver` — resolves missing `NetworkRoute` for legs using network + travel time.
  - `TimeModel` — converts distances + travel times into consistent event timestamps.
- `allocation.scoring`
  - `FreightScoreRunner` — constructs scoring functions and feeds events to compute scores.
  - `FreightScoreCalculator` — interface; `EventDrivenScoreCalculator` implementation using MATSim scoring.
  - `CompositeFreightScorer` — aggregates component scorers (time, distance, fixed, emissions/toll, penalties).
- `allocation.models`
  - `AllocationModel` — interface with `allocate(CoalitionScoreBundle baseline, CoalitionScoreBundle coalition)`.
  - `ProportionalSavingsAllocation` — allocates savings proportional to standalone costs or usage.
  - `MarginalContributionApprox` — toggles one collaborator at a time with pseudo-sim to approximate marginal contribution.
  - `ShapleySampler` — Monte-Carlo sampling of permutations, using pseudo-sim to estimate Shapley values.
- `allocation.util`
  - `EventsCaptureBuffer` — captures emitted events in-memory per collaborator/tour.
  - `PlanCloner` — safe deep-copy helpers for `BasicPlan`/carrier tour structures.
  - `Diagnostics` — timing, counts, consistency checks.

**Core Orchestration**
- `CostAllocationEngine`
  - Responsibilities:
    - Build baseline and coalition scenarios from `CollaborationDataStore` snapshots.
    - Invoke `FreightPseudoSimulator` with `PseudoSimConfig` for each run.
    - Use `FreightScoreRunner` to compute `ScoreResult`s.
    - Call `AllocationModel` to compute `AllocationResult`.
  - Key methods:
    - `AllocationResult allocateCosts(AllocationRequest req)`
    - `CoalitionScoreBundle runStandalone(Collection<FreightCollaborator<?>> collaborators)`
    - `CoalitionScoreBundle runCoalition(FreightCoalition coalition)`
    - `void setAllocationModel(AllocationModel model)`
  - Inputs via DI:
    - `EventsManager`, `Network`, `TravelTime`, `Scenario`, and `CarrierScoringFunctionFactory`.

**Data Layer**
- `CollaborationDataStore` (enhance plan and result storage):
  - Fields:
    - `Map<CollaboratorRole, Map<Id<?>, ? extends BasicPlan>> originalPlans`
    - `Map<Id<?>, PlanSnapshot> standaloneSnapshots`
    - `Map<Id<?>, PlanSnapshot> coalitionSnapshots`
    - `CoalitionScoreBundle lastBaselineScores`
    - `CoalitionScoreBundle lastCoalitionScores`
  - Methods:
    - `Optional<PlanSnapshot> getOriginalPlan(Id<?>)`
    - `void putStandaloneSnapshot(Id<?>, PlanSnapshot)` / `putCoalitionSnapshot(...)`
    - `void setLastScores(CoalitionScoreBundle baseline, CoalitionScoreBundle coalition)`
- `PlanSnapshot`
  - Fields: `Id<?> ownerId`, `CollaboratorRole role`, `BasicPlan planCopy`, `String originTag`.
  - Methods: `BasicPlan plan()`, `CollaboratorRole role()`, `Id<?> id()`.
- `ScoreResult`
  - Fields per collaborator: `Id<?> id`, `double timeCost`, `double distanceCost`, `double fixedCost`, `double tollCost`, `double penalties`, `double total`.
  - Methods: getters; `double total()` returns sum with sign convention consistent with MATSim scoring.
- `CoalitionScoreBundle`
  - Fields: `Map<Id<?>, ScoreResult> byCollaborator`, `double totalCoalitionScore`.
  - Methods: accessors; `ScoreResult get(Id<?>)`.

**Pseudo Simulation**
- `FreightPseudoSimulator`
  - Responsibilities:
    - For a given set of collaborators, iterate their selected plans and emit minimal events required by the scoring function.
    - Coordinate `PseudoEventsPlayer` per collaborator with a shared `EventsManager`.
  - Key methods:
    - `CoalitionScoreBundle simulate(Collection<FreightCollaborator<?>> collaborators, PseudoSimConfig cfg)`
    - Overload for coalition: `CoalitionScoreBundle simulate(FreightCoalition coalition, PseudoSimConfig cfg)`
  - Inputs via DI: `EventsManager`, `Network`, `TravelTime`, `Scenario`, `FreightScoreRunner`.
- `PseudoEventsPlayer`
  - Strategy: for carriers, generate at least `CarrierTourStartEvent`, `LinkEnterEvent` sequence for each leg’s route, and `CarrierTourEndEvent`.
  - Optional extensions: pickup/delivery start/end, service start/end for penalty scoring.
  - Key methods:
    - `void playCarrierPlan(Carrier carrier, EventsManager events, TravelTime tt, Network net, TimeModel timeModel)`
    - Internal helpers: `emitTourStart(...)`, `emitLinkEnterSequence(...)`, `emitTourEnd(...)`.
- `RouteResolver`
  - When a tour leg lacks a `NetworkRoute`, compute a plausible shortest path using current network + travel time.
  - Key methods: `NetworkRoute resolve(Tour.Leg leg, Id<Link> from, Id<Link> to, double depTime)`.
- `TimeModel`
  - Computes timestamps for link entries/leaves based on `TravelTime` and link lengths.
  - Key methods: `double nextLinkEnterTime(Id<Link> link, double currentTime, Id<Vehicle> vehicleId)`.
- `PseudoSimConfig`
  - Fields: `boolean emitServiceEvents`, `boolean resolveMissingRoutes`, `boolean includeTolls`, `double defaultDepartureTime`, `boolean collectEvents`.
  - Methods: builder-style setters; `static PseudoSimConfig defaultForCarriers()`.

**Scoring**
- `FreightScoreRunner`
  - Responsibilities: build scoring functions, attach as event handlers, run pseudo-sim, finalize and collect scores.
  - Key methods:
    - `ScoreResult scoreCarrier(Id<Carrier> carrierId, Runnable player)` — wires a `ScoringFunction` for the carrier, runs `player.run()`, returns `ScoreResult`.
    - `CoalitionScoreBundle scoreCoalition(Collection<Id<Carrier>> carriers, Runnable player)` — runs multi-carrier scoring.
  - DI: `CarrierScoringFunctionFactory`, `Scenario`, `Network`.
- `FreightScoreCalculator`
  - Interface: `ScoreResult compute(EventsCaptureBuffer buffer)` for offline score from captured events; default path uses event-driven scoring directly.
- `CompositeFreightScorer`
  - Wraps MATSim scorers; can inject optional components (toll, emissions, penalties) to match `ScoreResult` breakdown.

**Allocation Models**
- `AllocationModel`
  - `AllocationResult allocate(CoalitionScoreBundle baseline, CoalitionScoreBundle coalition)`
  - Contract: negative values indicate costs; savings = baseline.total − coalition.total.
- `ProportionalSavingsAllocation`
  - Algorithm: allocate total savings proportional to standalone cost shares; ensure budget-balance.
  - Config: choose base (time, distance, total), min/max bounds.
- `MarginalContributionApprox`
  - For each collaborator i: simulate coalition without i, compute Δ_i = (score_without_i − coalition_score); allocate based on Δ_i.
  - Controls: reuse events where possible; caching for efficiency.
- `ShapleySampler`
  - Monte-Carlo permutations Π: for each π, add collaborators incrementally, pseudo-simulate marginal contribution for entrant; average over Π.
  - Controls: sample size, variance target, seed.

**Results and Diagnostics**
- `AllocationResult`
  - Fields: `Map<Id<?>, Double> allocation`, `double coalitionScore`, `double baselineTotal`, `double savings`, `Map<Id<?>, ScoreResult> baselineScores`, `Map<Id<?>, ScoreResult> coalitionScores`.
  - Diagnostics: sample counts, runtime, cache hit rates, fairness checks (budget-balance, individual rationality warnings).

**Integration Points**
- Use `FreightCollaborationListener`’s iteration lifecycle to obtain `TravelTime` each iteration and pass into `FreightPseudoSimulator`.
- Bind `CostAllocationEngine` and desired `AllocationModel` in a Guice module (e.g., extend `CollaborationModule`).
- For carriers, reuse or adapt an existing `CarrierScoringFunctionFactory` or provide a basic event-based factory aligned with emitted events.

**Example Usage (Conceptual)**
- Build inputs:
  - Get `GrandFreightCoalition` from `FreightCoalitionManager`.
  - Prepare `AllocationRequest` with coalition members and config.
- Run allocation:
  - `AllocationResult result = costAllocationEngine.allocateCosts(request);`
  - Inspect `result.getAllocation()` and `result.getDiagnostics()`.

**Class Sketches (Key Methods)**
- `CostAllocationEngine`
  - `AllocationResult allocateCosts(AllocationRequest req)`
  - `CoalitionScoreBundle runStandalone(Collection<FreightCollaborator<?>> collaborators)`
  - `CoalitionScoreBundle runCoalition(FreightCoalition coalition)`
- `FreightPseudoSimulator`
  - `CoalitionScoreBundle simulate(Collection<FreightCollaborator<?>> collaborators, PseudoSimConfig cfg)`
  - `CoalitionScoreBundle simulate(FreightCoalition coalition, PseudoSimConfig cfg)`
- `FreightScoreRunner`
  - `ScoreResult scoreCarrier(Id<Carrier> carrierId, Runnable player)`
  - `CoalitionScoreBundle scoreCoalition(Collection<Id<Carrier>> carriers, Runnable player)`
- `AllocationModel`
  - `AllocationResult allocate(CoalitionScoreBundle baseline, CoalitionScoreBundle coalition)`

**Roadmap / Iterative Implementation**
- Milestone 1: implement pseudo-sim minimal path for carriers (tour start/end + link-enter events), integrate basic event-based scoring.
- Milestone 2: add data store snapshots + orchestrator; implement `ProportionalSavingsAllocation` model.
- Milestone 3: add marginal contribution approximation; caching and reuse of partial runs.
- Milestone 4: add Shapley sampling; extend events to service start/end and time-window penalties if needed.

**Notes and Assumptions**
- Scores follow MATSim convention (typically negative costs). Allocation models should be explicit about sign and budget-balance.
- Pseudo-sim uses static `TravelTime` from the last mobsim iteration; it does not feed back into demand — this is intentional for tractability.
- Focus on carriers first; LSP/receiver support can mirror carriers once plan structures and scoring needs are clarified.

