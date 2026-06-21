# MATSim Freight Collaboration Review

Review scope:

- Java extension: `contribs/xp-collaboration/src/main/java/org/matsim/contrib/freightcollaboration`
- Related receiver contrib code: `contribs/freightreceiver/src/main/java/org/matsim/freight/receiver`
- Leuven post-analysis code: `python/freightCollabLeuven`
- Concrete sanity-check output:
  `data/randomDemand15ReceiversOutput/randomDemand15ReceiversOutput/r6_NW/ins0/leuvenCRCollab15Receivers-r6_NW-exact_shapley-af0.80-p0.0014-i00`

This document is a code/model/design review only. No Java or Python code was changed.

## Executive Summary

The two anomalies you observed are real, and they are primarily caused by design/implementation mismatches rather than a single Python arithmetic bug.

1. `total_cost_savings` currently means "sum of allocated values written by the collaboration allocation model". In the current Java model, that value is based on pseudo-simulation carrier score differences for the current mutable coalition baseline, not necessarily on `iter0_carrier_score`.
2. Receiver final scores include fixed delivery fees, collaboration allocation received, and receiver relaxation penalties. The allocation model currently allocates carrier operational cost savings; it does not subtract the receiver relaxation penalty before writing `allocatedValues`.
3. The Python aggregator reads `receivers.xml.gz` from the output root. In this model that file is written in `BeforeMobsim`, before the iteration's scoring and receiver replanning have completed. It is not guaranteed to be identical to `ITERS/it.30/30.receivers.xml`.
4. In the checked sample, `30.receivers.xml` is internally consistent with `-1500 + receiver_allocation - relaxation_penalty`, while root `receivers.xml.gz` contains receiver selected-plan scores from a different point in the iteration lifecycle.
5. There is a likely bug in `ReceiverControlerListener.notifyReplanning`: receiver replanning is run inside the receiver loop, so the strategy manager can run repeatedly on growing receiver collections in one replanning event.

If the intended accounting identity is:

```text
total_cost_savings
  == (total_receiver_scores - initial_receiver_scores)
   + (final_carrier_scores - iter0_carrier_score)
```

then the model needs a precise definition of `total_cost_savings` as net system welfare gain, not just allocated carrier cost savings. It must also use the same final-iteration receiver/carrier snapshot and the same baseline score convention.

## Main Java Design

### Scenario Runner

File: `contribs/xp-collaboration/src/main/java/org/matsim/contrib/freightcollaboration/run/Run20ReceiversWithDistantSingleDepot.java`

Role:

- Discovers demand CSVs under `data/randomDemand15Receivers_nni`.
- Builds one MATSim scenario per depot direction/ring, instance, allocation method, allocation factor, and receiver penalty.
- Creates carriers and receivers from externally generated Leuven demand files.
- Runs a carrier-receiver collaboration experiment for 30 iterations.

Important implementation details:

- `INPUT_BASE_DIR` and `OUTPUT_BASE_DIR` are hard-coded to the 15-receiver Leuven experiment.
- `RUN_TAG` is `leuvenCRCollab15Receivers`.
- `AllocationMethod` maps labels such as `exact_shapley`, `marginal`, `proportional`, `approx_shapley_mc`, and `approx_shapley_stratified` to `AllocationModels`.
- `createConfig` sets:
  - first iteration = 0
  - last iteration = 30
  - write events interval = 10
  - write plans interval = 10
  - output directory = `data/randomDemand15ReceiversOutput/<ring_direction>/insX/<runId>/`
- The freight collaboration config is created via `RunCarrierReceiverCollabChessboardExample.createExampleFreightCollaborationConfig()`.
  - This is important: that helper overrides `RECEIVER_FIXED_FEE = 100.0` and `CARRIER_CHARGED_FEE = 100.0`.
  - Therefore a 15-receiver baseline of `-1500` is consistent with this runner.
- All receivers are initially marked:
  - `grandCoalitionMember = true`
  - `collaborationStatus = true`
  - linked to a carrier through `affiliatedCarrierId`.
- The runner installs:
  - `ReceiverModule`
  - `CarrierModule`
  - `CollaborationModule`
  - custom carrier and receiver scoring factories from `ScoringFunctionFactoryUsecase`.

Potential issues:

- The class name still says `20Receivers`, but the constants and output tags use 15 receivers.
- The runner skips a run if the output directory exists, even though the MATSim config says `deleteDirectoryIfExists`. This is conservative but can leave partial/failed runs looking "completed" to later analysis if the folder exists.
- It relies on a config helper from the chessboard example. This is convenient but hides important fee defaults away from the Leuven runner.

### Collaboration Module And Data Store

Files:

- `contribs/xp-collaboration/.../controller/CollaborationModule.java`
- `contribs/xp-collaboration/.../allocation/CollaborationDataStore.java`

Role:

- Binds collaboration infrastructure into MATSim.
- Creates and binds one `CollaborationDataStore`.
- Registers listeners that form coalitions, run allocation, and write collaboration data.

Registered listeners:

- `Iter0BaselineCarrierScoreListener`
- `NotifyCoalitionInfoListener`
- `FormFreightCoalitionListener`
- `FreightCollaborationListener`
- `WriteCollaborationDataListener`

The old `AllocationToScoreListener` is commented out because allocation is now injected through scoring functions.

`CollaborationDataStore` stores:

- `originalPlans`: copied selected plans at module initialization.
- `simulatedCoalitionScores`: pseudo-simulation scores by mutable coalition and sub-coalition.
- `allocatedValues`: final allocation per collaborator ID for the current iteration.
- optional iter0 carrier baselines:
  - `iter0CarrierBaselineFeeFree`
  - `iter0CarrierBaselineFeeIncluded`

Detailed explanation of the two optional iter0 carrier baseline maps:

```java
Map<Id<Carrier>, Double> iter0CarrierBaselineFeeIncluded
Map<Id<Carrier>, Double> iter0CarrierBaselineFeeFree
```

Both maps are cached by `Iter0BaselineCarrierScoreListener` at the end of iteration 0, but only if `FreightCollaborationConfigGroup.ITER0_BASELINE_MODE` is not `DISABLED`.

They exist because carrier scores in the actual MATSim run and carrier scores in the collaboration pseudo-simulation can use different accounting scopes:

- The actual selected carrier plan score in iteration 0 is produced by the normal carrier scoring function.
- In this extension, the normal carrier scoring function includes a positive fixed fee charged to receivers through `SimpleChargingReceiverScoring`.
- The pseudo-simulation used for allocation usually uses `BASIC_COST`, which scores only operational carrier cost components and excludes fee income and receiver payouts.
- Therefore, if the allocation baseline should be iteration 0, the model needs to know whether to use the carrier's full scored iteration-0 value or a fee-free operational approximation.

`iter0CarrierBaselineFeeIncluded`:

- Key: carrier ID.
- Value: the carrier selected-plan score exactly as stored at the end of iteration 0.
- Includes the fixed fee income from linked receivers.
- For the Leuven runner, the fixed carrier fee is usually `CARRIER_CHARGED_FEE = 100.0` per linked receiver because `createExampleFreightCollaborationConfig()` overrides the default.
- This value is closest to the Python column `iter0_carrier_score`, because `read_iter0_carriers(...)` reads the selected plan score from `ITERS/it.0/0.carrierPlans.xml`.

Conceptually:

```text
iter0CarrierBaselineFeeIncluded
  = iter0_basic_operational_carrier_score
  + CARRIER_CHARGED_FEE * number_of_linked_receivers
```

`iter0CarrierBaselineFeeFree`:

- Key: carrier ID.
- Value: the iteration-0 selected-plan score minus the fixed receiver fee income.
- This is intended as a closer baseline for pSim `BASIC_COST` scoring.
- It still relies on the selected carrier plan score produced in iteration 0, but removes the fee component added by `SimpleChargingReceiverScoring`.

Conceptually:

```text
iter0CarrierBaselineFeeFree
  = iter0CarrierBaselineFeeIncluded
  - CARRIER_CHARGED_FEE * number_of_linked_receivers
```

For a 15-receiver Leuven case with one carrier and `CARRIER_CHARGED_FEE = 100`:

```text
feeIncluded = iter0 selected carrier score from 0.carrierPlans.xml
feeFree     = feeIncluded - 1500
```

How they are used:

- `FreightPseudoSimulator.runPSim(...)` checks these maps only for the empty sub-coalition baseline.
- This happens only when:

```text
collaboratingSubset.isEmpty()
and ITER0_BASELINE_MODE != DISABLED
```

- If `ITER0_BASELINE_MODE = FEE_INCLUDED`, the empty-coalition score is read from `iter0CarrierBaselineFeeIncluded`.
- If `ITER0_BASELINE_MODE = FEE_FREE`, the empty-coalition score is read from `iter0CarrierBaselineFeeFree`.
- If `ITER0_BASELINE_MODE = DISABLED`, the empty coalition is re-simulated in the current iteration context instead of using iteration 0.

Implication for the accounting identity:

- If Python compares against `iter0_carrier_score`, it is comparing against a fee-included baseline.
- If Java allocation uses `BASIC_COST` pSim and `ITER0_BASELINE_MODE = FEE_FREE`, the comparable Python baseline should be fee-free, not raw `iter0_carrier_score`.
- If Java allocation leaves `ITER0_BASELINE_MODE = DISABLED`, then the allocation baseline is neither `iter0CarrierBaselineFeeIncluded` nor `iter0CarrierBaselineFeeFree`; it is the current iteration's pSim empty-coalition score.

Potential issues:

- `ITER0_BASELINE_MODE` exists but defaults to `DISABLED`, and the Leuven runner does not enable it. Therefore the allocation baseline is not the actual iteration-0 carrier score unless explicitly configured.
- Original carrier plans may be unavailable at module initialization because carrier plans are created later by receiver-triggered carrier replanning. This is not fatal for the current carrier-receiver allocation where receivers are the players, but it is fragile for other collaboration types.

### Iteration Lifecycle

Main files:

- `FormFreightCoalitionListener.java`
- `FreightCollaborationListener.java`
- `WriteCollaborationDataListener.java`
- receiver package listeners described below.

Approximate lifecycle relevant to this model:

1. `BeforeMobsim`
   - receiver-triggered carrier replanning can rebuild carriers from current receiver selected plans.
   - root `receivers.xml.gz` is written by `ReceiverTriggersCarrierReplanningListener`.
   - mutable freight coalitions are formed by `FormFreightCoalitionListener`.
2. mobsim runs.
3. `AfterMobsim`
   - `FreightCollaborationListener` runs `FreightCollaborationEngine`.
   - pseudo-simulation evaluates sub-coalitions.
   - allocation model writes `allocatedValues` into `CollaborationDataStore`.
4. scoring
   - carrier and receiver scoring functions read `allocatedValues`.
5. receiver replanning
   - receiver selected plans can change.
6. `IterationEnds`
   - receiver score stats and `ITERS/it.X/X.receivers.xml` are written.
   - collaboration XML is written as `ITERS/it.X/X.collaboration_data.xml`.

Important consequence:

`receivers.xml.gz` in the output root is not a final copy of `ITERS/it.30/30.receivers.xml`. It is a before-mobsim snapshot written at the start of an iteration. For final receiver scores, analysis should prefer `ITERS/it.30/30.receivers.xml` or a dedicated shutdown/final writer.

## Coalition Formation

File: `FormFreightCoalitionListener.java`

Role:

- At iteration 0, forms a grand freight coalition.
- From iteration 1 onward, forms mutable coalitions for the configured collaboration types.
- For carrier-receiver collaboration, each carrier gets a mutable coalition containing linked receivers whose current plan differs from the original plan in a collaboration-relevant way.

Core detection logic:

- `precomputeReceiverDeltas()` compares original and current receiver plans.
- `CoalitionUtils.computeReceiverDelta(...)` flags:
  - time-window extension
  - service-duration contraction
  - affected carrier IDs
- `findCollaboratingReceiversForCarrier(...)` includes only receivers with a detected change for that carrier.

Design intent:

- A receiver becomes a "collaborating player" only when it actually relaxes the delivery constraint compared with the original plan.
- The carrier is the distributor; receivers are players.

Potential issues:

- For carrier-receiver collaboration, `FreightCollaborator.collaborationStatus` is mostly bypassed; the actual condition is plan delta against the original plan.
- Coalition membership depends on the selected receiver plan at `BeforeMobsim`, while allocation and scoring occur later in the same iteration. This is expected but should be documented because selected plans may change again during replanning.

## Pseudo-Simulation And Allocation

### FreightCollaborationEngine

File: `allocation/FreightCollaborationEngine.java`

Role:

- Gets current mutable coalitions from `FreightCoalitionManager`.
- Builds a `FreightPseudoSimulator` using current network travel times.
- Evaluates the sub-coalitions needed by the configured allocation model.
- Creates the allocation model and writes `allocatedValues`.

Sub-coalition evaluation:

- `SHAPLEY`: evaluates all receiver subsets.
- `PROPORTIONAL`: currently evaluates only empty and full coalition.
- `MARGINAL`: evaluates empty, full, and full-without-one-player subsets.
- `APPROX_SHAPLEY`: the approximate model runs its own sampled pseudo-simulations.

Potential issues:

- `runAllSubCoalitions` uses `parallelStream()` inside executor tasks. This can oversubscribe threads and makes runtime/determinism harder to reason about.
- For proportional allocation, singleton subsets are commented out in `buildSubCoalitionsForProportional`, but `AllocationModelProportional` attempts to use singleton savings as weights. In practice this makes proportional fall back to equal shares unless singleton results are available from somewhere else.

### FreightPseudoSimulator

File: `allocation/FreightPseudoSimulator.java`

Role:

- Deep-copies distributors and players.
- Resets non-collaborating players to original plans.
- Replans the carrier with the selected collaborating receiver subset.
- Scores the replanned carrier using `CarrierPSimScorer`.

Carrier-receiver logic:

- For empty coalition, if `ITER0_BASELINE_MODE` is enabled, it can return cached iteration-0 carrier baselines.
- Otherwise, empty coalition is re-simulated from current iteration context by resetting all collaborating receivers to original plans.
- For full coalition, it replans using all currently collaborating receivers.
- It also includes receivers linked to the same carrier but not in the current mutable coalition as non-collaborating receivers, reset to original plan.

Potential issues:

- With default `ITER0_BASELINE_MODE = DISABLED`, the empty coalition baseline is not the same as `ITERS/it.0/0.carrierPlans.xml`.
- The pseudo-simulation scorer normally uses `BASIC_COST`, while actual MATSim carrier scoring includes fee income and receiver payouts. This is a deliberate separation, but it means raw pSim values cannot be directly compared with final `carrier_score`.
- JSprit solving is stochastic unless all random sources are controlled. Negative savings are clipped in allocation models, which can hide solver noise.
- The pSim activity-based carrier simulation is a simplified reproduction of carrier scoring. Any mismatch in activities, final depot activity treatment, or route timing creates differences from actual MATSim scoring.

### Allocation Models

Files:

- `AllocationModelShapleyValue.java`
- `AllocationModelApproxShapleyValue.java`
- `AllocationModelMarginalContribution.java`
- `AllocationModelProportional.java`

Current value convention:

- Scores follow MATSim utility convention: higher score is better; costs are often negative.
- Cost savings are computed as:

```text
savings = collaborativeScore - baselineScore
```

For exact Shapley:

- `baseline = coalitionScores.get(Set.of())`
- For every sub-coalition: `savings = subCoalitionScore - baseline`
- Negative savings are clipped to 0.
- Shapley values are computed on this clipped savings game.
- Receivers receive `allocationFactor * shapleyValue`.
- The distributor/carrier receives the reserved share `(1 - allocationFactor) * totalShapleyValue`.

For marginal/proportional:

- Uses full-vs-baseline savings.
- Receiver shares sum to `allocationFactor * totalSavings`.
- Carrier receives `(1 - allocationFactor) * totalSavings`.

For approximate Shapley:

- `valueCache` stores savings values in `COST_SAVINGS` mode, not raw pSim scores.
- It clips negative estimated Shapley values to zero.
- Budget balance against the true full-coalition savings is approximate, not guaranteed.

Potential issues:

- The XML tag name `subCoalition score` means raw pSim score for exact methods but savings value for approximate Shapley. This is confusing for downstream diagnostics.
- Negative clipping changes the cooperative game and can make "exact Shapley" exact only for the clipped game, not the raw pSim game.
- Approximate Shapley can be non-budget-balanced because positive marginal clipping changes the sum of player values.

## Scoring Model

File: `run/ScoringFunctionFactoryUsecase.java`

### Carrier Scoring

The main carrier scoring function contains:

- `SimpleDriversLegScoring`: negative distance/time costs.
- `SimpleVehicleEmploymentScoring`: negative fixed vehicle costs.
- `SimpleDriversActivityScoring`: negative service/activity cost and late time-window penalty.
- `SimpleChargingReceiverScoring`: positive fee income from linked receivers.
- `distributeCostSavings`: negative payout to linked receivers.

Actual carrier score can be conceptualized as:

```text
carrier_score
  = carrier_basic_operational_score
  + carrier_charged_fee_income
  - receiver_allocations_paid
```

For the Leuven runner:

```text
carrier_charged_fee_income = 100 * linked_receiver_count
```

The pSim scorer usually uses:

```text
carrier_basic_operational_score
```

unless `PSIM_SCORING_MODE = BASIC_PLUS_FEES`.

Potential issue:

- The allocation XML contains a reserved carrier allocation, but the actual carrier scoring function does not add `allocatedValues[carrier]`. The carrier's reserved share is instead implicit: it is the operational cost improvement left after receiver payouts. This is reasonable, but downstream analysis must not interpret `allocatedValues[carrier]` as a score component that was explicitly added to the carrier plan.

### Receiver Scoring

The collaboration receiver scoring function contains:

- fixed receiver fee via `CarrierToReceiverCostAllocation` and `ReceiverCostAllocationFixed`
- `ReceiverRelaxationPenalty`
- `AllocationFromDistributor`

Conceptually:

```text
receiver_score
  = -receiver_fixed_fee
  + receiver_allocation_received
  - relaxation_penalty
```

For the Leuven runner:

```text
receiver_fixed_fee = 100
initial total receiver score for 15 receivers = -1500
```

`ReceiverRelaxationPenalty` computes:

- penalty per second of time-window relaxation
- plus penalty per second of service-duration contraction

Potential issue:

- Receiver relaxation penalties affect receiver scores, but they are not subtracted inside the allocation model's `total_cost_savings`. Therefore:

```text
sum(allocatedValues)
```

is carrier-side operational savings, while:

```text
(receiver score gain) + (carrier score gain)
```

is closer to net welfare gain after receiver inconvenience penalties, assuming all snapshots and baselines are aligned.

## Receiver Output Mismatch

Relevant files:

- `contribs/freightreceiver/.../ReceiverTriggersCarrierReplanningListener.java`
- `contribs/freightreceiver/.../ReceiverScoreStats.java`
- `contribs/freightreceiver/.../ReceiversWriter.java`

Root output:

- `ReceiverTriggersCarrierReplanningListener.notifyBeforeMobsim(...)` writes:
  - root `carriers.xml.gz`
  - root `receivers.xml.gz`
- This happens before the mobsim/scoring/replanning of that iteration.

Iteration output:

- `ReceiverScoreStats.notifyIterationEnds(...)` writes:
  - `ITERS/it.X/X.carrierPlans.xml`
  - `ITERS/it.X/X.receivers.xml`
- This happens at iteration end after scoring and replanning.

Therefore:

```text
output root receivers.xml.gz != ITERS/it.30/30.receivers.xml
```

is expected under the current writer design.

For post-analysis, root `receivers.xml.gz` is a poor source for final receiver scores. Use the last iteration receiver file, or add a dedicated shutdown/final receiver writer if root output is intended to mean final state.

## Concrete Sample Check

Scenario checked:

```text
data/randomDemand15ReceiversOutput/randomDemand15ReceiversOutput/r6_NW/ins0/leuvenCRCollab15Receivers-r6_NW-exact_shapley-af0.80-p0.0014-i00
```

Files present:

- `receivers.xml.gz`
- `ITERS/it.30/30.receivers.xml`
- `output_carriers.xml.gz`
- `ITERS/it.0/0.carrierPlans.xml`
- `ITERS/it.30/30.collaboration_data.xml`

Observed values:

```text
root receiver total selected score     = -1354.8263675281057
it30 receiver total selected score     = -1567.8275162268633
final carrier score                    =   975.6176357284955
iter0 carrier score                    =   740.2087761006647
allocation sum, all collaborators      =     9.715604716420797
allocation sum, receivers only         =     7.7724837731366385
allocation sum, carrier reserved share =     1.943120943284159
```

The collaboration XML for iteration 30 has:

```text
pSim baseline score = -526.0696217962852
pSim full score     = -516.3540170798644
pSim savings        =    9.7156047164208
```

So the Python `total_cost_savings = sum(allocation_data.values())` is reading the allocation file correctly for this sample.

But the user's expected equation gives:

```text
using root receivers:
(-1354.8263675281057 - -1500) + (975.6176357284955 - 740.2087761006647)
= 380.5824920997251

using it30 receivers:
(-1567.8275162268633 - -1500) + (975.6176357284955 - 740.2087761006647)
= 167.5813434009675
```

Neither equals `9.715604716420797`.

Why:

1. `9.7156` is the current-iteration pSim full-vs-empty coalition savings, not final-vs-iter0 score delta.
2. The root receiver file is not the final iteration-end receiver score snapshot.
3. The it30 receiver total is internally consistent with the receiver scoring model:

```text
-1500 + receiver_allocations - relaxation_penalty
= -1500 + 7.7724837731366385 - 75.6
= -1567.8275162268633
```

4. The carrier final-vs-iter0 delta reflects actual carrier score changes, including operational improvement and receiver payouts, while allocation pSim used a different baseline.

## Python Post-Analysis Design

### scenario_parser.py

Role:

- Parses nested Leuven output folders:

```text
<base>/<ring_direction>/<ins>/<scenario-folder>
```

- Extracts:
  - receiver count
  - ring level
  - direction
  - allocation method
  - allocation factor
  - penalty
  - instance

Potential issues:

- Sorting `ring_level` as strings can order `r10` before `r2` if such levels exist.
- Float filters are handled with tolerance, which is good.

### aggregate_matsim_outputs.py

Role:

- Discovers scenario folders.
- Reads root carrier and receiver outputs.
- Reads iteration-0 carriers.
- Reads collaboration allocation XML for `LAST_ITER = 30`.
- Computes aggregate metrics:
  - fleet size
  - carrier scores
  - receiver scores
  - total allocated value
  - time-window extension
  - VKT/VTT/TKT
  - spatial metrics
- Writes:
  - per-scenario `metrics.csv.gz`
  - per-scenario `shipments.csv.gz`
  - per-scenario `geo_data.geojson`
  - combined `all_scenarios_metrics.csv.gz`

Important current behavior:

- `compute_scenario_metrics` reads:

```python
carriers_path = folder_path / "output_carriers.xml.gz"
receivers_path = folder_path / "receivers.xml.gz"
collaboration_data = read_collaboration_allocation_data(folder_path, last_iter)
```

- `total_cost_savings` is:

```python
sum(collaboration_data.values())
```

This is better named `total_allocated_value` or `allocated_carrier_cost_savings` unless the Java model is changed to write net welfare savings.

Potential issues:

- `receivers.xml.gz` is not the final receiver state. For final scores, use `ITERS/it.{last_iter}/{last_iter}.receivers.xml`.
- The underlying `read_receivers` helper currently uses `gzip.open` even for `.xml`, so it would fail on uncompressed `30.receivers.xml` unless fixed.
- `_REQUIRED_FILES` does not require:
  - `ITERS/it.30/30.collaboration_data.xml`
  - `ITERS/it.30/30.receivers.xml`
  - `ITERS/it.30/30.carrierPlans.xml`
- Missing collaboration data silently becomes zero through `read_collaboration_allocation_data`.
- `ORIGINAL_TW = (6, 7)` is in hours, and receiver time-window parsing truncates to integer hours. This is okay for whole-hour windows but fragile for minute-level changes.
- Receiver "collaborative" classification is based only on time-window difference. Service-time collaboration would be missed.
- `MATSIM_OUTPUT_PATH` default is `output/randomDemand15ReceiversOutput`, while the checked data path is under `data/randomDemand15ReceiversOutput/randomDemand15ReceiversOutput`. If notebook or caller overrides this, fine; otherwise the script default is probably stale.

### matsim_output_reader.py

Location:

- `python/test/matsim_output_reader.py`

Used by:

- `aggregate_matsim_outputs.py`

Important functions:

- `read_receivers(receiver_file_path, tw=None)`
  - reads selected receiver plan
  - extracts score and time window
  - classifies collaborative vs non-collaborative
- `read_carriers(carrier_file_path, with_iter0_scores=True)`
  - reads carrier score from selected plan
  - reads shipments
  - optionally joins iter0 score and vehicle count
- `read_iter0_carriers(file_path, only_scores=True)`
  - reads `ITERS/it.0/0.carrierPlans.xml`
- `read_collaboration_allocation_data(folder_path, last_iter)`
  - reads `ITERS/it.{last_iter}/{last_iter}.collaboration_data.xml`
  - returns allocation values by collaborator ID

Potential issues:

- `read_receivers` opens `.xml` with `gzip.open`, which only works for gzipped files.
- If selected plan score is missing, it defaults to `-9999999` rather than failing loudly.
- `read_collaboration_allocation_data` returns `{'carrier1': 0.0}` when the file is missing or the path is invalid. This masks incomplete runs as zero-savings runs.
- `read_carriers` assumes selected plan exists and has a score.

### analysis.ipynb

Actual file name:

- `python/freightCollabLeuven/anaysis.ipynb`

Role:

- Reads the aggregated CSV.
- Filters scenarios.
- Plots `total_cost_savings` by ring level.
- Contains manual checks of score differences.

Potential issues:

- File name is misspelled as `anaysis.ipynb`.
- `derive_scenarios_set_df` casts values to strings for filtering. This is fragile for floats.
- Cell 17 contains a hard-coded arithmetic check. It should be replaced by a named diagnostic calculation using columns.
- Cell 18 references `test_NW_exact_shap_10p_scenarios_df`, but the visible notebook defines `test_SW_approx_shap_stratified_10p_scenarios_df`.
- `penalty_dict` omits some penalties used by the runner, such as `0.0167`, `0.0222`, and `0.028`.

## Point-By-Point Findings And Suggested Fixes

### 1. `total_cost_savings` is not the same object as the expected score identity

Problem:

- Java writes `allocatedValues`.
- Python sums them and calls the result `total_cost_savings`.
- The allocation values are based on pSim coalition savings under the configured baseline.
- The expected identity uses actual final carrier score, iteration-0 carrier score, and receiver scores.

Why it matters:

- These are different score spaces and sometimes different time snapshots.

Possible fixes:

- Decide which metric is desired:
  - `carrier_operational_savings`: final basic carrier score minus iter0 fee-free basic carrier score.
  - `allocated_carrier_savings`: sum of allocation XML values.
  - `net_welfare_gain`: carrier score gain plus receiver score gain.
- Rename the Python column if it remains allocation-based.
- Add explicit output diagnostics for each component:

```text
carrier_basic_score
carrier_fee_income
receiver_payout
receiver_fixed_fee
receiver_relaxation_penalty
sum_receiver_allocations
carrier_reserved_share
operational_savings
net_welfare_gain
```

### 2. Allocation baseline is not iteration 0 by default

Problem:

- `ITER0_BASELINE_MODE` defaults to `DISABLED`.
- The Leuven runner does not enable it.
- Empty coalition pSim baseline is therefore not `iter0_carrier_score`.

Observed sample:

```text
pSim baseline = -526.0696
pSim full     = -516.3540
savings       = 9.7156

iter0 carrier score = 740.2088
final carrier score = 975.6176
```

Possible fixes:

- If allocation should be against iteration 0, enable:

```java
fccg.setIter0BaselineModeString(FreightCollaborationConfigGroup.Iter0BaselineMode.FEE_FREE.name());
```

- Keep `PSIM_SCORING_MODE = BASIC_COST` if comparing fee-free operational costs.
- Document that `iter0_carrier_score` in Python includes carrier fee income, so fee-free baseline should be derived as `iter0_carrier_score - CARRIER_CHARGED_FEE * n_receivers` if using output XML.
- If comparing to the raw Python `iter0_carrier_score` column, use `FEE_INCLUDED`; if comparing to pSim `BASIC_COST` values, use `FEE_FREE`.

### 3. Receiver relaxation penalties are outside allocation

Problem:

- Receiver score includes `-relaxation_penalty`.
- Allocation total does not subtract the relaxation penalty.

Implication:

If final scores are aligned, then:

```text
carrier score gain + receiver score gain
```

is closer to:

```text
carrier operational savings - receiver relaxation penalties
```

not pure carrier operational savings.

Possible fixes:

- If the intended welfare metric should include receiver inconvenience, subtract receiver penalties before reporting `total_cost_savings`.
- Or keep operational savings and report receiver penalties separately.

### 4. Python uses root `receivers.xml.gz`, which is not final

Problem:

- Root `receivers.xml.gz` is written in `BeforeMobsim`.
- `ITERS/it.30/30.receivers.xml` is written at iteration end.

Observed sample:

```text
root receiver total = -1354.8264
it30 receiver total = -1567.8275
```

Possible fixes:

- Read `ITERS/it.{last_iter}/{last_iter}.receivers.xml` for final receiver metrics.
- Update `read_receivers` to support uncompressed XML.
- Or add a shutdown/final receiver writer that writes a true final root `output_receivers.xml.gz`.

### 5. Receiver replanning is likely executed repeatedly per event

Problem:

- In `ReceiverControlerListener.notifyReplanning`, `strategyManager.run(...)` is inside the loop over receivers.
- On replanning iterations this can run once for receiver 0, again for receivers 0-1, again for receivers 0-2, etc.

Why it matters:

- This can over-mutate receiver plans.
- It can make selected plan evolution and final snapshots harder to interpret.

Possible fix:

- Build `receiverCollection` and `receiverControlCollection` first.
- Run `strategyManager.run(...)` once per collection after the loop.

### 6. Missing final files are silently treated as zero collaboration

Problem:

- `_REQUIRED_FILES` does not include final collaboration XML.
- `read_collaboration_allocation_data` returns `{'carrier1': 0.0}` when the file is missing.

Possible fixes:

- Require the last iteration files during completeness checks.
- Return an empty dict plus warning, or raise an exception, for missing required allocation data.
- Add a status column like `collaboration_data_found`.

### 7. Proportional allocation probably falls back to equal split

Problem:

- `buildSubCoalitionsForProportional` only evaluates empty and full coalition.
- `AllocationModelProportional` tries to use singleton coalition scores as weights.
- Since singleton scores are absent, weights default to baseline and become zero.

Possible fix:

- Re-enable singleton sub-coalitions for proportional allocation, or explicitly design proportional as equal-share when singleton scores are not evaluated.

### 8. Approximate Shapley diagnostics mix raw scores and savings

Problem:

- Exact methods store raw pSim scores in `simulatedCoalitionScores`.
- Approximate Shapley stores savings values in the same data structure when in `COST_SAVINGS` mode.

Possible fix:

- Write separate XML attributes:

```text
rawScore
savingsVsBaseline
valueUsedForAllocation
```

### 9. Python receiver parser is too coarse for future variants

Problems:

- Time windows are parsed as integer hours.
- Collaboration is inferred only from time-window difference.
- `.xml` files are treated as gzip.

Possible fixes:

- Parse times as seconds.
- Detect service-time changes against original plan when needed.
- Use `gzip.open` only for `.gz`, normal `open`/`etree.parse` for `.xml`.

### 10. Notebook should expose explicit accounting diagnostics

Problem:

- Current notebook plots `total_cost_savings` but does not decompose why it differs from score gains.

Possible diagnostics to add:

```python
df["carrier_delta"] = df["final_carrier_score"] - df["iter0_carrier_score"]
df["receiver_delta"] = df["total_receiver_scores"] - (-100 * df["num_receivers"])
df["net_score_gain"] = df["carrier_delta"] + df["receiver_delta"]
df["allocation_gap_to_net_score_gain"] = df["net_score_gain"] - df["total_cost_savings"]
```

But this only becomes meaningful after receiver scores are read from the last iteration file and the baseline definition is aligned.

## Recommended Next Step

Before changing code, decide the accounting target:

1. If the paper/model wants carrier operational savings, then keep allocation as carrier-side savings, rename `total_cost_savings`, and compare it to carrier operational score components rather than receiver+carrier final score gains.
2. If the paper/model wants net social welfare gain, then compute:

```text
carrier operational savings - receiver relaxation penalties
```

and either write this value from Java or compute it consistently in Python using final iteration receiver files.
3. If the model wants the exact identity originally stated, align all three:
   - use last-iteration receiver XML
   - use fee-free iteration-0 carrier operational baseline
   - include receiver relaxation penalties in `total_cost_savings`, or exclude them from the receiver side of the identity.
