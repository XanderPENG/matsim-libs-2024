# Collaboration Data Output Writer

## Overview

The `CollaborationDataStoreValueWriter` writes coalition scores and allocated values from the freight collaboration simulation to XML files at each iteration. This helps track how collaboration benefits evolve over the simulation.

## Features

- **Coalition Scores**: Writes scores for all sub-coalitions tested during Shapley value calculation
- **Allocated Values**: Records how collaboration benefits are distributed among participants
- **Iteration Tracking**: Automatically outputs data for each iteration after the first
- **XML Format**: Structured, human-readable output that can be easily parsed for analysis

## Usage

### Automatic Integration (Recommended)

The writer is automatically integrated into your simulation when you use `CollaborationModule`. It's registered as a listener that writes output after each iteration.

```java
// In your run example (e.g., RunCarrierReceiverShapleyAllocationExample.java)
CollaborationModule collaborationModule = new CollaborationModule(
    collaboratorModules,
    freightCollaborators,
    scenario
);
controler.addOverridingModule(collaborationModule);
```

The output files will automatically be created in: `output/ITERS/it.X/collaboration_data_itX.xml`

### Manual Usage

You can also manually write collaboration data at any point:

```java
// Write both coalition scores and allocated values
CollaborationDataStoreValueWriter.writeCoalitionScoresAndAllocatedValues(
    collaborationDataStore,
    "output/ITERS/it.10/collaboration_data.xml",
    10  // iteration number
);

// Write only coalition scores
CollaborationDataStoreValueWriter.writeCoalitionScores(
    collaborationDataStore,
    "output/coalition_scores_it10.xml",
    10
);

// Write only allocated values
CollaborationDataStoreValueWriter.writeAllocatedValues(
    collaborationDataStore,
    "output/allocated_values_it10.xml",
    10
);
```

## Output Format

### Complete Output Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<collaborationData iteration="10">

    <coalitionScores>
        <coalition id="1" type="CARRIER_RECEIVER" size="11">
            <members>
                <member id="carrier1" role="CARRIER"/>
                <member id="receiver1" role="RECEIVER"/>
                <!-- ... more members ... -->
            </members>
            <subCoalitionScores>
                <!-- Baseline: no collaboration -->
                <subCoalition size="0" score="-1234.56" type="baseline"/>

                <!-- Individual receiver collaboration -->
                <subCoalition size="1" score="-1150.23">
                    <memberId>receiver1</memberId>
                </subCoalition>

                <!-- Two receivers collaborating -->
                <subCoalition size="2" score="-1050.45">
                    <memberId>receiver1</memberId>
                    <memberId>receiver2</memberId>
                </subCoalition>

                <!-- ... all 2^n sub-coalitions ... -->
            </subCoalitionScores>
        </coalition>
    </coalitionScores>

    <allocatedValues>
        <!-- Total allocated value: 345.67 -->
        <allocation collaboratorId="receiver1" value="34.56"/>
        <allocation collaboratorId="receiver2" value="42.13"/>
        <!-- ... more allocations ... -->
    </allocatedValues>

</collaborationData>
```

### Coalition Scores Section

- **coalition**: One per carrier-receiver coalition in the simulation
  - `id`: Sequential coalition number
  - `type`: Collaboration type (e.g., CARRIER_RECEIVER)
  - `size`: Total number of collaborators (carrier + receivers)

- **members**: Lists all coalition members
  - `id`: Collaborator ID
  - `role`: CARRIER, RECEIVER, or LSP

- **subCoalitionScores**: Scores for all 2^n possible sub-coalitions
  - `size`: Number of collaborating receivers in this sub-coalition
  - `score`: Carrier cost/score for serving this sub-coalition
  - `type`: "baseline" for empty set (no collaboration)
  - **memberId**: IDs of receivers in this sub-coalition

### Allocated Values Section

- **allocation**: One per receiver that receives collaboration benefits
  - `collaboratorId`: Receiver ID
  - `value`: Shapley value allocated to this receiver

- **Total comment**: Sum of all allocated values

## Understanding the Data

### Coalition Scores Interpretation

**Negative scores** represent costs (lower is better):
- **Baseline (size=0)**: Carrier cost without any collaboration = -1234.56
- **Sub-coalition score**: Carrier cost when serving specific receivers = -1150.23
- **Cost savings**: baseline - sub-coalition = (-1234.56) - (-1150.23) = -84.33

**More negative baseline + less negative sub-coalition = positive savings**

### Shapley Value Calculation

The Shapley values in `allocatedValues` are calculated from the coalition scores using:

```
Shapley(receiver_i) = Σ [weight(S) × marginal_contribution(receiver_i, S)]
```

Where:
- `S` = sub-coalition not containing receiver_i
- `weight(S) = |S|! × (n - |S| - 1)! / n!`
- `marginal_contribution = score(S ∪ {i}) - score(S)`

## Analyzing the Output

### Python Analysis Example

```python
import xml.etree.ElementTree as ET
import pandas as pd

# Read collaboration data
tree = ET.parse('output/ITERS/it.10/collaboration_data_it10.xml')
root = tree.getroot()

# Extract allocated values
allocations = []
for alloc in root.find('allocatedValues').findall('allocation'):
    allocations.append({
        'receiver': alloc.get('collaboratorId'),
        'value': float(alloc.get('value'))
    })

df = pd.DataFrame(allocations)
print(df.describe())

# Extract coalition scores for analysis
coalitions = []
for coalition in root.find('coalitionScores').findall('coalition'):
    baseline_score = None
    grand_coalition_score = None

    for sub in coalition.find('subCoalitionScores').findall('subCoalition'):
        size = int(sub.get('size'))
        score = float(sub.get('score'))

        if size == 0:
            baseline_score = score
        elif size == coalition.get('size') - 1:  # All receivers
            grand_coalition_score = score

    if baseline_score and grand_coalition_score:
        savings = baseline_score - grand_coalition_score
        coalitions.append({
            'coalition_id': coalition.get('id'),
            'baseline_cost': baseline_score,
            'collaborative_cost': grand_coalition_score,
            'total_savings': savings
        })

coalition_df = pd.DataFrame(coalitions)
print(coalition_df)
```

### Tracking Convergence Over Iterations

```python
import glob
import matplotlib.pyplot as plt

# Read all iteration files
files = sorted(glob.glob('output/ITERS/it.*/collaboration_data_it*.xml'))
iterations = []
total_savings = []

for file in files:
    tree = ET.parse(file)
    root = tree.getroot()
    iteration = int(root.get('iteration'))

    # Calculate total allocated value
    total_alloc = sum(
        float(a.get('value'))
        for a in root.find('allocatedValues').findall('allocation')
    )

    iterations.append(iteration)
    total_savings.append(total_alloc)

plt.plot(iterations, total_savings)
plt.xlabel('Iteration')
plt.ylabel('Total Allocated Savings')
plt.title('Collaboration Benefits Over Time')
plt.show()
```

## File Locations

- **Main writer class**: `utils/CollaborationDataStoreValueWriter.java`
- **Integration listener**: `listener/WriteCollaborationDataListener.java`
- **Module registration**: `controller/CollaborationModule.java` (line 50)
- **Example output**: `resources/example_collaboration_data_output.xml`

## Disabling Output

If you don't want the collaboration data output, you can comment out the listener binding in `CollaborationModule.java`:

```java
// this.addControlerListenerBinding().to(WriteCollaborationDataListener.class);
```

## Troubleshooting

### No output files generated

**Check:**
1. Simulation runs beyond first iteration
2. `CollaborationDataStore` has data (not null/empty)
3. `WriteCollaborationDataListener` is registered in `CollaborationModule`
4. Output directory has write permissions

### Empty or partial XML files

**Possible causes:**
- Coalition scores not calculated (PSim not run)
- Allocated values not computed (Shapley calculation failed)
- Check logs for warnings/errors from `WriteCollaborationDataListener`

### Large file sizes

With N receivers, there are 2^N sub-coalitions. For 10 receivers = 1,024 sub-coalitions per carrier.

**Solutions:**
- Write only every K iterations
- Write compressed files (.xml.gz)
- Write only allocated values, not coalition scores

## Related Documentation

- [Shapley Value Allocation](SHAPLEY_ALLOCATION.md)
- [Freight Collaboration Framework](FREIGHT_COLLABORATION.md)
- [CollaborationDataStore API](API_COLLABORATION_DATASTORE.md)