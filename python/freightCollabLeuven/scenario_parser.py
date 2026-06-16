"""
@Author  : XanderPENG
@File    : scenario_parser.py
@Description : Parse the nested MATSim-freightCollab Leuven output tree and extract
    the key parameters of every concrete scenario.

The output tree has three levels::

    <MATSIM_OUTPUT_PATH>/
        r{ring}_{Direction}/            # e.g. r8_NE
            ins{instance}/              # e.g. ins0
                leuvenCRCollab{N}Receivers-r{ring}_{Direction}-{method}-af{af}-p{pen}-i{inst}/

Only the innermost folder is a concrete scenario output, e.g.
``leuvenCRCollab15Receivers-r8_NE-marginal-af0.80-p0.0056-i00``.
"""

import re
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence


# ---------------------------------------------------------------------------
# Scenario configuration
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ScenarioConfig:
    """Immutable configuration of a single scenario parsed from its folder name.

    Attributes:
        num_receivers: Number of receivers in the scenario (e.g. 15).
        ring_level: Ring level token, e.g. ``'r8'`` (replaces ``depot_location``).
        direction: Cardinal direction token, e.g. ``'NE'``.
        allocation_method: Payoff allocation method, e.g. ``'marginal'``,
            ``'exact_shapley'``, ``'approx_shapley_mc'``.
        allocation_factor: Allocation factor value (e.g. 0.80).
        penalty: Penalty value (e.g. 0.0056).
        instance: Instance index parsed from the ``-i{NN}`` token.
        folder_name: Name of the innermost scenario folder.
        folder_path: Absolute/relative path to the scenario folder.
    """

    num_receivers: int
    ring_level: str
    direction: str
    allocation_method: str
    allocation_factor: float
    penalty: float
    instance: int
    folder_name: str
    folder_path: str


# Matches e.g. "leuvenCRCollab15Receivers-r8_NE-approx_shapley_mc-af0.80-p0.0056-i00".
# `method` allows letters, digits and underscores but not the '-' delimiter.
_SCENARIO_PATTERN = re.compile(
    r"^leuvenCRCollab(?P<num>\d+)Receivers-"
    r"(?P<ring>r\d+)_(?P<dir>[A-Z]+)-"
    r"(?P<method>[A-Za-z0-9_]+)-"
    r"af(?P<af>\d+\.\d+)-"
    r"p(?P<pen>\d+\.\d+)-"
    r"i(?P<inst>\d+)$"
)


def parse_folder_name(folder_name: str, folder_path: str) -> Optional[ScenarioConfig]:
    """Parse a scenario folder name into a :class:`ScenarioConfig`.

    Args:
        folder_name: Name of the innermost scenario folder.
        folder_path: Path to the scenario folder (stored on the returned config).

    Returns:
        A :class:`ScenarioConfig`, or ``None`` if the name does not match the
        expected scenario pattern.
    """
    match = _SCENARIO_PATTERN.match(folder_name)
    if match is None:
        return None

    return ScenarioConfig(
        num_receivers=int(match.group("num")),
        ring_level=match.group("ring"),
        direction=match.group("dir"),
        allocation_method=match.group("method"),
        allocation_factor=float(match.group("af")),
        penalty=float(match.group("pen")),
        instance=int(match.group("inst")),
        folder_name=folder_name,
        folder_path=folder_path,
    )


def discover_scenarios(
    base_path: str,
    ring_levels: Optional[Sequence[str]] = None,
    directions: Optional[Sequence[str]] = None,
    allocation_methods: Optional[Sequence[str]] = None,
    allocation_factors: Optional[Sequence[float]] = None,
    penalties: Optional[Sequence[float]] = None,
    instances: Optional[Sequence[int]] = None,
) -> List[ScenarioConfig]:
    """Discover all scenario folders under ``base_path`` matching optional filters.

    The function globs the fixed three-level layout
    ``<base>/<ring_dir>/<ins>/<scenario>`` and keeps every leaf folder whose name
    parses as a scenario.

    Args:
        base_path: Root of the MATSim output tree.
        ring_levels: Keep only these ring tokens (e.g. ``['r0', 'r8']``).
        directions: Keep only these directions (e.g. ``['NE', 'SW']``).
        allocation_methods: Keep only these allocation methods.
        allocation_factors: Keep only these allocation factors (float match within 1e-6).
        penalties: Keep only these penalties (float match within 1e-6).
        instances: Keep only these instance indices.

    Returns:
        Scenario configs sorted by
        ``(ring_level, direction, allocation_method, allocation_factor, penalty, instance)``.
    """
    base = Path(base_path)
    scenarios: List[ScenarioConfig] = []

    # Scenario folders live exactly three levels below the base directory.
    for scenario_dir in base.glob("*/*/leuvenCRCollab*"):
        if not scenario_dir.is_dir():
            continue

        config = parse_folder_name(scenario_dir.name, str(scenario_dir))
        if config is None:
            continue

        if ring_levels and config.ring_level not in ring_levels:
            continue
        if directions and config.direction not in directions:
            continue
        if allocation_methods and config.allocation_method not in allocation_methods:
            continue
        if allocation_factors and not any(
            abs(config.allocation_factor - af) < 1e-6 for af in allocation_factors
        ):
            continue
        if penalties and not any(abs(config.penalty - p) < 1e-6 for p in penalties):
            continue
        if instances and config.instance not in instances:
            continue

        scenarios.append(config)

    return sorted(
        scenarios,
        key=lambda c: (
            c.ring_level,
            c.direction,
            c.allocation_method,
            c.allocation_factor,
            c.penalty,
            c.instance,
        ),
    )
