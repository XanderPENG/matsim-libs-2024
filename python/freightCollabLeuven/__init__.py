"""MATSim-freightCollab Leuven output aggregation package.

``scenario_parser`` is lightweight (stdlib only) and always importable.
``aggregate_matsim_outputs`` pulls in heavy geospatial / matsim dependencies, so
it is imported lazily to keep ``import freightCollabLeuven`` cheap and side-effect free.
"""

from .scenario_parser import ScenarioConfig, discover_scenarios, parse_folder_name

__all__ = [
    "ScenarioConfig",
    "parse_folder_name",
    "discover_scenarios",
    "process_all_scenarios",
    "compute_scenario_metrics",
    "compute_merged_shipment_df",
    "create_geo_dataframe",
]


def __getattr__(name: str):
    """Lazily expose the aggregation API without importing heavy deps at package load."""
    if name in {
        "process_all_scenarios",
        "compute_scenario_metrics",
        "compute_merged_shipment_df",
        "create_geo_dataframe",
    }:
        from . import aggregate_matsim_outputs as _agg

        return getattr(_agg, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
