"""
@Time    : 6/16/26
@Author  : XanderPENG
@File    : aggregate_matsim_outputs.py
@Description : Aggregate the outputs of the MATSim-freightCollab Leuven scenarios.

For every concrete scenario found under ``MATSIM_OUTPUT_PATH`` this script computes
a row of metrics (mirroring ``python/test/agg_anls.py``), writes per-scenario
``shipments.csv.gz`` and ``geo_data.geojson`` artifacts into ``CLEAN_OUTPUT_PATH``,
and finally writes the combined ``all_scenarios_metrics.csv.gz``.

Differences from ``agg_anls.py`` metric schema:
    - no ``receiver_distribution`` field;
    - ``ring_level`` replaces ``depot_location``;
    - adds ``direction`` and ``allocation_method`` fields.

Run with the environment that provides ``matsim-tools`` (e.g. the ``GWML`` conda env)::

    /opt/anaconda3/envs/GWML/bin/python python/freightCollabLeuven/aggregate_matsim_outputs.py
"""

import logging
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import geopandas as gpd
import networkx as nx
import numpy as np
import pandas as pd
from shapely.geometry import Point
from tqdm import tqdm

import matsim

# Reuse the proven MATSim readers/metric helpers from the sibling ``test`` package.
_TEST_DIR = Path(__file__).resolve().parent.parent / "test"
if str(_TEST_DIR) not in sys.path:
    sys.path.insert(0, str(_TEST_DIR))

import matsim_output_reader  # noqa: E402
import metric_anls  # noqa: E402

try:  # works when run as a script (this dir is on sys.path)
    from scenario_parser import ScenarioConfig, discover_scenarios  # noqa: E402
except ModuleNotFoundError:  # works when imported as a package submodule
    from .scenario_parser import ScenarioConfig, discover_scenarios

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

MATSIM_OUTPUT_PATH = "output/randomDemand15ReceiversOutput"
CLEAN_OUTPUT_PATH = "data/freightCollabLeuven15Receivers/clean"
NETWORK_PATH = "data/GemeenteLeuvenWithHbefaType/carGemeenteLeuvenWithHbefaType.xml.gz"

ORIGINAL_TW: Tuple[int, int] = (6, 7)  # 06:00-07:00 base delivery window
LAST_ITER = 30
SCENARIO_CRS = "EPSG:31370"  # Belgian Lambert 72, as used by the Leuven demand generator
# Reference "centre" of the input road network; depot-to-centre Euclidean distance
# is reported against this link's destination node. Override via process_all_scenarios.
DEFAULT_NETWORK_CENTER_LINK_ID = "353678367_1"


# ---------------------------------------------------------------------------
# Network helpers
# ---------------------------------------------------------------------------

def build_network_graph(
    network_link_df: pd.DataFrame, network_node_df: pd.DataFrame
) -> nx.DiGraph:
    """Build a directed NetworkX graph from MATSim link/node DataFrames.

    Nodes carry ``x``/``y`` attributes; edges carry ``length`` (also exposed as
    ``weight``) in metres.
    """
    graph = nx.DiGraph()
    for node_id, x, y in zip(
        network_node_df["node_id"], network_node_df["x"], network_node_df["y"]
    ):
        graph.add_node(node_id, x=float(x), y=float(y))
    for link_id, from_node, to_node, length in zip(
        network_link_df["link_id"],
        network_link_df["from_node"],
        network_link_df["to_node"],
        network_link_df["length"],
    ):
        graph.add_edge(
            from_node, to_node, link_id=link_id, length=float(length), weight=float(length)
        )
    return graph


def get_link_to_node_mapping(network_link_df: pd.DataFrame) -> Dict[str, str]:
    """Map ``link_id`` to its destination ``to_node``."""
    return dict(zip(network_link_df["link_id"], network_link_df["to_node"]))


def get_node_coordinates(network_node_df: pd.DataFrame) -> Dict[str, Tuple[float, float]]:
    """Map ``node_id`` to ``(x, y)`` coordinates."""
    return {
        node_id: (float(x), float(y))
        for node_id, x, y in zip(
            network_node_df["node_id"], network_node_df["x"], network_node_df["y"]
        )
    }


def get_link_lengths(network_link_df: pd.DataFrame) -> Dict[str, float]:
    """Map ``link_id`` to link ``length`` in metres (for fast VKT lookups)."""
    return {
        link_id: float(length)
        for link_id, length in zip(network_link_df["link_id"], network_link_df["length"])
    }


def compute_euclidean_distance(x1: float, y1: float, x2: float, y2: float) -> float:
    """Euclidean distance between two points."""
    return float(np.hypot(x1 - x2, y1 - y2))


def _vehicle_km_from_chains(
    travel_chains: Dict[str, List[str]], link_lengths: Dict[str, float]
) -> Dict[str, float]:
    """Sum link lengths (metres) along each vehicle travel chain via dict lookup.

    This replaces ``metric_anls.compute_freight_vehicle_km_traveled`` which scans
    the full link DataFrame per link; the dict lookup is essential for the large
    Leuven network (~52k links).
    """
    return {
        vehicle_id: sum(link_lengths.get(link_id, 0.0) for link_id in links)
        for vehicle_id, links in travel_chains.items()
    }


def _min_network_dists_to_targets(
    graph: nx.DiGraph, sources: List[str], targets: List[str]
) -> List[float]:
    """For each target, the minimum source->target shortest-path length over all sources.

    Runs one single-source Dijkstra per source (cheap relative to per-pair calls).
    Targets unreachable from every source are dropped.
    """
    best: Dict[str, float] = {t: float("inf") for t in targets}
    for source in sources:
        if source not in graph:
            continue
        lengths = nx.single_source_dijkstra_path_length(graph, source, weight="length")
        for target in targets:
            dist = lengths.get(target, float("inf"))
            if dist < best[target]:
                best[target] = dist
    return [d for d in best.values() if d < float("inf")]


def _network_nearest_neighbor_dists(graph: nx.DiGraph, nodes: List[str]) -> List[float]:
    """Nearest-neighbour shortest-path distance for each node (network clustering)."""
    nn_distances: List[float] = []
    for source in nodes:
        if source not in graph:
            continue
        lengths = nx.single_source_dijkstra_path_length(graph, source, weight="length")
        min_dist = float("inf")
        for target in nodes:
            if target != source:
                dist = lengths.get(target, float("inf"))
                if dist < min_dist:
                    min_dist = dist
        if min_dist < float("inf"):
            nn_distances.append(min_dist)
    return nn_distances


def _compute_spatial_metrics(
    depot_nodes: List[str],
    receiver_nodes: List[str],
    node_coords: Dict[str, Tuple[float, float]],
    network_graph: Optional[nx.DiGraph],
    compute_network_distances: bool,
    network_center_node: Optional[str] = None,
    spatial_cache: Optional[Dict[Tuple, Dict[str, Optional[float]]]] = None,
) -> Dict[str, Optional[float]]:
    """Compute receiver-to-depot distance, clustering index and depot-to-centre distance (km).

    Spatial geometry depends only on the receiver/depot node sets (and the fixed
    network centre), which are identical across allocation methods and penalties
    for a given (ring, direction, instance). Results are therefore cached.
    """
    depot_nodes = sorted(set(depot_nodes))
    receiver_nodes = sorted(set(receiver_nodes))
    cache_key = (frozenset(depot_nodes), frozenset(receiver_nodes), network_center_node)
    if spatial_cache is not None and cache_key in spatial_cache:
        return spatial_cache[cache_key]

    result: Dict[str, Optional[float]] = {
        "mean_receiver_dist_to_depot_euclidean_km": None,
        "mean_receiver_dist_to_depot_network_km": None,
        "clustering_index_euclidean_km": None,
        "clustering_index_network_km": None,
        "depot_to_network_center_euclidean_km": None,
    }

    # ----- Euclidean depot-to-network-centre distance (nearest depot) -----
    if network_center_node is not None and network_center_node in node_coords:
        cx, cy = node_coords[network_center_node]
        center_dists = [
            compute_euclidean_distance(*node_coords[depot], cx, cy)
            for depot in depot_nodes
            if depot in node_coords
        ]
        if center_dists:
            result["depot_to_network_center_euclidean_km"] = float(min(center_dists)) / 1000.0

    # ----- Euclidean receiver-to-depot distance -----
    euclidean_dists: List[float] = []
    for recv in receiver_nodes:
        if recv not in node_coords:
            continue
        rx, ry = node_coords[recv]
        min_dist = float("inf")
        for depot in depot_nodes:
            if depot in node_coords:
                dx, dy = node_coords[depot]
                min_dist = min(min_dist, compute_euclidean_distance(rx, ry, dx, dy))
        if min_dist < float("inf"):
            euclidean_dists.append(min_dist)
    if euclidean_dists:
        result["mean_receiver_dist_to_depot_euclidean_km"] = float(np.mean(euclidean_dists)) / 1000.0

    # ----- Euclidean clustering index (mean nearest-neighbour distance) -----
    if len(receiver_nodes) >= 2:
        nn_dists: List[float] = []
        for i, node_i in enumerate(receiver_nodes):
            if node_i not in node_coords:
                continue
            xi, yi = node_coords[node_i]
            min_dist = float("inf")
            for j, node_j in enumerate(receiver_nodes):
                if i != j and node_j in node_coords:
                    xj, yj = node_coords[node_j]
                    min_dist = min(min_dist, compute_euclidean_distance(xi, yi, xj, yj))
            if min_dist < float("inf"):
                nn_dists.append(min_dist)
        if nn_dists:
            result["clustering_index_euclidean_km"] = float(np.mean(nn_dists)) / 1000.0

    # ----- Network distances -----
    if compute_network_distances and network_graph is not None:
        if depot_nodes and receiver_nodes:
            net_dists = _min_network_dists_to_targets(network_graph, depot_nodes, receiver_nodes)
            if net_dists:
                result["mean_receiver_dist_to_depot_network_km"] = float(np.mean(net_dists)) / 1000.0
        if len(receiver_nodes) >= 2:
            net_nn = _network_nearest_neighbor_dists(network_graph, receiver_nodes)
            if net_nn:
                result["clustering_index_network_km"] = float(np.mean(net_nn)) / 1000.0

    if spatial_cache is not None:
        spatial_cache[cache_key] = result
    return result


# ---------------------------------------------------------------------------
# Core analysis
# ---------------------------------------------------------------------------

# Files that must exist for a scenario output to be considered complete.
_REQUIRED_FILES = (
    "output_carriers.xml.gz",
    "receivers.xml.gz",
    "output_events.xml.gz",
    os.path.join("ITERS", "it.0", "0.events.xml.gz"),
    os.path.join("ITERS", "it.0", "0.carrierPlans.xml"),
)


def is_scenario_complete(config: ScenarioConfig) -> bool:
    """Return True if the scenario folder has all files required for analysis.

    Many discovered folders are placeholders for runs that have not finished, so
    these are skipped quietly rather than reported as errors.
    """
    return all(
        os.path.exists(os.path.join(config.folder_path, rel)) for rel in _REQUIRED_FILES
    )


def _scenario_identifiers(config: ScenarioConfig) -> Dict[str, Any]:
    """Scenario key parameters shared by every output (parsed from the folder name)."""
    return {
        "ring_level": config.ring_level,
        "direction": config.direction,
        "allocation_method": config.allocation_method,
        "allocation_factor": config.allocation_factor,
        "penalty": config.penalty,
        "instance": config.instance,
        "num_receivers": config.num_receivers,
    }


def compute_scenario_metrics(
    config: ScenarioConfig,
    network_link_df: pd.DataFrame,
    link_lengths: Dict[str, float],
    link_to_node: Dict[str, str],
    node_coords: Dict[str, Tuple[float, float]],
    network_graph: Optional[nx.DiGraph] = None,
    original_tw: Tuple[int, int] = ORIGINAL_TW,
    last_iter: int = LAST_ITER,
    compute_network_distances: bool = True,
    network_center_link_id: str = DEFAULT_NETWORK_CENTER_LINK_ID,
    spatial_cache: Optional[Dict[Tuple, Dict[str, Optional[float]]]] = None,
) -> Dict[str, Any]:
    """Compute all aggregate metrics for a single scenario."""
    folder_path = config.folder_path
    carriers_path = os.path.join(folder_path, "output_carriers.xml.gz")
    receivers_path = os.path.join(folder_path, "receivers.xml.gz")
    events_path = os.path.join(folder_path, "output_events.xml.gz")
    iter0_events_path = os.path.join(folder_path, "ITERS", "it.0", "0.events.xml.gz")

    # ----- Read basic data -----
    carriers_df, shipments_df = matsim_output_reader.read_carriers(carriers_path)
    receivers_dict = matsim_output_reader.read_receivers(receivers_path, original_tw)
    collaboration_data = matsim_output_reader.read_collaboration_allocation_data(folder_path, last_iter)

    # ----- Fleet & score metrics -----
    fleet_size = carriers_df["num_vehicles"].sum()
    iter0_fleet_size = carriers_df["iter0_num_vehicles"].sum()
    final_carrier_score = carriers_df["carrier_score"].sum()
    iter0_carrier_score = carriers_df["iter0_carrier_score"].sum()

    # ----- Receiver metrics -----
    num_collaborative_receivers = receivers_dict["count_collaborative"]
    num_non_collab_receivers = receivers_dict["count_non_collaborative"]
    total_receiver_scores = receivers_dict["total_score"]

    # ----- Collaboration metrics -----
    total_cost_savings = sum(collaboration_data.values())

    # ----- Time-window extension -----
    total_extended_tw_hours = 0.0
    for receiver in receivers_dict["collaborative_receivers"]:
        orig_start, orig_end = original_tw
        new_start, new_end = receiver["time_window"]
        total_extended_tw_hours += (orig_start - new_start) + (new_end - orig_end)

    # ----- VKT (vehicle km traveled) -----
    travel_chains = metric_anls.derive_freight_vehicle_travel_chains(events_path)
    total_vkt = sum(_vehicle_km_from_chains(travel_chains, link_lengths).values()) / 1000.0
    iter0_travel_chains = metric_anls.derive_freight_vehicle_travel_chains(iter0_events_path)
    total_iter0_vkt = sum(_vehicle_km_from_chains(iter0_travel_chains, link_lengths).values()) / 1000.0

    # ----- VTT (vehicle travel time, seconds) -----
    total_vtt = sum(metric_anls.compute_freight_vehicle_travel_times(events_path).values())
    total_iter0_vtt = sum(metric_anls.compute_freight_vehicle_travel_times(iter0_events_path).values())

    # ----- TKT (ton-km traveled) -----
    tkt = _compute_tkt(events_path, network_link_df)
    iter0_tkt = _compute_tkt(iter0_events_path, network_link_df)

    # ----- Spatial metrics (cached by receiver/depot node set) -----
    depot_links = carriers_df["depot_link_id"].dropna().unique().tolist()
    depot_nodes = [link_to_node[link] for link in depot_links if link in link_to_node]
    receiver_links = shipments_df["delivery_link_id"].dropna().unique().tolist()
    receiver_nodes = [link_to_node[link] for link in receiver_links if link in link_to_node]
    network_center_node = link_to_node.get(network_center_link_id)
    spatial = _compute_spatial_metrics(
        depot_nodes, receiver_nodes, node_coords, network_graph,
        compute_network_distances, network_center_node, spatial_cache,
    )

    metrics: Dict[str, Any] = _scenario_identifiers(config)
    metrics.update(
        {
            "fleet_size": fleet_size,
            "iter0_fleet_size": iter0_fleet_size,
            "final_carrier_score": final_carrier_score,
            "iter0_carrier_score": iter0_carrier_score,
            "num_collaborative_receivers": num_collaborative_receivers,
            "num_non_collab_receivers": num_non_collab_receivers,
            "total_receiver_scores": total_receiver_scores,
            "total_cost_savings": total_cost_savings,
            "total_extended_tw_hours": total_extended_tw_hours,
            "VKT_km": total_vkt,
            "iter0_VKT_km": total_iter0_vkt,
            "VTT_seconds": total_vtt,
            "iter0_VTT_seconds": total_iter0_vtt,
            "TKT_tonkm": tkt,
            "iter0_TKT_tonkm": iter0_tkt,
        }
    )
    metrics.update(spatial)
    return metrics


def _compute_tkt(events_path: str, network_link_df: pd.DataFrame) -> float:
    """Ton-km traveled = sum(shipment travel distance km * capacity demand)."""
    travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(
        events_path, network_link_df
    )
    if travel_df.empty:
        return 0.0
    if "capacity_demand" in travel_df.columns:
        capacity = travel_df["capacity_demand"].fillna(1.0)
        return float((travel_df["travel_distance_km"] * capacity).sum())
    return float(travel_df["travel_distance_km"].sum())


def compute_merged_shipment_df(
    config: ScenarioConfig, network_link_df: pd.DataFrame
) -> pd.DataFrame:
    """Merge final & iter0 shipment data with travel info and delivery-time deviations."""
    folder_path = config.folder_path
    carriers_path = os.path.join(folder_path, "output_carriers.xml.gz")
    events_path = os.path.join(folder_path, "output_events.xml.gz")
    iter0_events_path = os.path.join(folder_path, "ITERS", "it.0", "0.events.xml.gz")

    _, final_shipments_df = matsim_output_reader.read_carriers(carriers_path)
    _, iter0_shipments_df = matsim_output_reader.read_iter0_carriers(folder_path, only_scores=False)

    final_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(
        events_path, network_link_df
    )
    iter0_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(
        iter0_events_path, network_link_df
    )

    iter0_shipments_df = iter0_shipments_df.add_prefix("iter0_").rename(
        columns={"iter0_shipment_id": "shipment_id"}
    )
    iter0_travel_df = iter0_travel_df.add_prefix("iter0_").rename(
        columns={"iter0_shipment_id": "shipment_id"}
    )
    final_travel_df = final_travel_df.add_prefix("final_").rename(
        columns={"final_shipment_id": "shipment_id"}
    )

    merged_df = final_shipments_df.copy()
    merged_df = merged_df.merge(final_travel_df, on="shipment_id", how="left")
    merged_df = merged_df.merge(
        iter0_shipments_df.drop(columns=["iter0_carrier_id"], errors="ignore"),
        on="shipment_id",
        how="left",
    )
    merged_df = merged_df.merge(iter0_travel_df, on="shipment_id", how="left")

    for key, value in _scenario_identifiers(config).items():
        merged_df[key] = value

    # ----- Delivery-time deviations (+ late / - early relative to window end) -----
    merged_df["start_delivery_sec"] = merged_df["start_delivery"].apply(_to_seconds)
    merged_df["end_delivery_sec"] = merged_df["end_delivery"].apply(_to_seconds)

    for prefix in ("final", "iter0"):
        time_col = f"{prefix}_delivery_time"
        if time_col in merged_df.columns:
            merged_df[f"{prefix}_delivery_time_deviation_min"] = (
                merged_df[time_col] - merged_df["end_delivery_sec"]
            ) / 60.0
            merged_df[f"{prefix}_early_arrival_deviation_min"] = (
                (merged_df["start_delivery_sec"] - merged_df[time_col]).clip(lower=0) / 60.0
            )
        else:
            merged_df[f"{prefix}_delivery_time_deviation_min"] = np.nan
            merged_df[f"{prefix}_early_arrival_deviation_min"] = np.nan

    merged_df = merged_df.drop(columns=["start_delivery_sec", "end_delivery_sec"], errors="ignore")
    return merged_df


def _to_seconds(value: Any) -> float:
    """Convert a numeric or ``HH:MM:SS`` time value to seconds."""
    if pd.isna(value):
        return np.nan
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            parts = value.split(":")
            if len(parts) == 3:
                return float(parts[0]) * 3600 + float(parts[1]) * 60 + float(parts[2])
            if len(parts) == 2:
                return float(parts[0]) * 60 + float(parts[1])
    return np.nan


def create_geo_dataframe(
    config: ScenarioConfig,
    link_to_node: Dict[str, str],
    node_coords: Dict[str, Tuple[float, float]],
    original_tw: Tuple[int, int] = ORIGINAL_TW,
    last_iter: int = LAST_ITER,
) -> gpd.GeoDataFrame:
    """Build a GeoDataFrame of carrier & receiver locations with collaboration info."""
    folder_path = config.folder_path
    carriers_path = os.path.join(folder_path, "output_carriers.xml.gz")
    receivers_path = os.path.join(folder_path, "receivers.xml.gz")

    carriers_df, shipments_df = matsim_output_reader.read_carriers(carriers_path)
    receivers_dict = matsim_output_reader.read_receivers(receivers_path, original_tw)
    collaboration_data = matsim_output_reader.read_collaboration_allocation_data(folder_path, last_iter)

    def _point_for_link(link_id: Optional[str]) -> Optional[Point]:
        if link_id and link_id in link_to_node:
            node = link_to_node[link_id]
            if node in node_coords:
                x, y = node_coords[node]
                return Point(x, y)
        return None

    def _delivery_link(receiver_id: str) -> Optional[str]:
        matches = shipments_df[shipments_df["shipment_id"].str.contains(receiver_id, na=False)]
        return matches.iloc[0]["delivery_link_id"] if len(matches) > 0 else None

    geo_records: List[Dict[str, Any]] = []

    for _, carrier in carriers_df.iterrows():
        depot_link = carrier["depot_link_id"]
        geo_records.append(
            {
                "id": carrier["carrier_id"],
                "type": "carrier",
                "is_collaborative": True,
                "payoff_allocation": collaboration_data.get(carrier["carrier_id"], 0.0),
                "relaxed_tw_hours": 0.0,
                "link_id": depot_link,
                "geometry": _point_for_link(depot_link),
            }
        )

    for receiver in receivers_dict["collaborative_receivers"]:
        delivery_link = _delivery_link(receiver["id"])
        orig_start, orig_end = original_tw
        new_start, new_end = receiver["time_window"]
        geo_records.append(
            {
                "id": receiver["id"],
                "type": "receiver",
                "is_collaborative": True,
                "payoff_allocation": collaboration_data.get(receiver["id"], 0.0),
                "relaxed_tw_hours": (orig_start - new_start) + (new_end - orig_end),
                "link_id": delivery_link,
                "geometry": _point_for_link(delivery_link),
            }
        )

    for receiver in receivers_dict["non_collaborative_receivers"]:
        delivery_link = _delivery_link(receiver["id"])
        geo_records.append(
            {
                "id": receiver["id"],
                "type": "receiver",
                "is_collaborative": False,
                "payoff_allocation": 0.0,
                "relaxed_tw_hours": 0.0,
                "link_id": delivery_link,
                "geometry": _point_for_link(delivery_link),
            }
        )

    gdf = gpd.GeoDataFrame(geo_records, geometry="geometry", crs=SCENARIO_CRS)
    for key, value in _scenario_identifiers(config).items():
        gdf[key] = value
    return gdf


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def scenario_output_name(config: ScenarioConfig) -> str:
    """Flat, collision-free folder name encoding all scenario key parameters."""
    return (
        f"{config.ring_level}_{config.direction}-{config.allocation_method}"
        f"-af{config.allocation_factor:.2f}-p{config.penalty:.4f}-i{config.instance:02d}"
    )


def save_scenario_outputs(
    config: ScenarioConfig,
    metrics: Dict[str, Any],
    shipment_df: pd.DataFrame,
    geo_df: gpd.GeoDataFrame,
    output_base_path: str,
) -> str:
    """Persist per-scenario metrics, shipments and geo data; return the folder path."""
    output_folder = os.path.join(output_base_path, scenario_output_name(config))
    os.makedirs(output_folder, exist_ok=True)

    pd.DataFrame([metrics]).to_csv(
        os.path.join(output_folder, "metrics.csv.gz"), index=False, compression="gzip"
    )
    shipment_df.to_csv(
        os.path.join(output_folder, "shipments.csv.gz"), index=False, compression="gzip"
    )

    geo_path = os.path.join(output_folder, "geo_data.geojson")
    valid_geo = geo_df[geo_df["geometry"].notna()]
    if len(valid_geo) > 0:
        valid_geo.to_file(geo_path, driver="GeoJSON")
    else:
        geo_df.drop(columns=["geometry"]).to_json(geo_path, orient="records")
    return output_folder


# ---------------------------------------------------------------------------
# Main processing
# ---------------------------------------------------------------------------

def process_all_scenarios(
    input_path: str = MATSIM_OUTPUT_PATH,
    output_path: str = CLEAN_OUTPUT_PATH,
    network_path: str = NETWORK_PATH,
    ring_levels: Optional[List[str]] = None,
    directions: Optional[List[str]] = None,
    allocation_methods: Optional[List[str]] = None,
    allocation_factors: Optional[List[float]] = None,
    penalties: Optional[List[float]] = None,
    instances: Optional[List[int]] = None,
    original_tw: Tuple[int, int] = ORIGINAL_TW,
    last_iter: int = LAST_ITER,
    compute_network_distances: bool = True,
    network_center_link_id: str = DEFAULT_NETWORK_CENTER_LINK_ID,
    verbose: bool = True,
) -> pd.DataFrame:
    """Process every matching scenario and write the combined metrics CSV.

    Args:
        input_path: Root of the MATSim output tree.
        output_path: Destination for per-scenario artifacts and the combined CSV.
        network_path: External MATSim network used for all spatial/distance metrics.
        ring_levels/directions/allocation_methods/allocation_factors/penalties/instances:
            Optional filters (default: process everything discovered).
        original_tw: Base delivery time window (hours).
        last_iter: Last iteration index (for collaboration allocation data).
        compute_network_distances: Whether to compute network shortest-path metrics.
        network_center_link_id: Link whose destination node is the road-network centre
            used for the ``depot_to_network_center_euclidean_km`` metric.
        verbose: Whether to log progress.

    Returns:
        DataFrame of metrics for all successfully processed scenarios.
    """
    scenarios = discover_scenarios(
        input_path,
        ring_levels=ring_levels,
        directions=directions,
        allocation_methods=allocation_methods,
        allocation_factors=allocation_factors,
        penalties=penalties,
        instances=instances,
    )
    if verbose:
        logger.info("Found %d matching scenarios", len(scenarios))
    if not scenarios:
        return pd.DataFrame()

    os.makedirs(output_path, exist_ok=True)

    if verbose:
        logger.info("Loading network from: %s", network_path)
    matsim_network = matsim.read_network(network_path)
    network_link_df = matsim_network.links
    network_node_df = matsim_network.nodes
    link_lengths = get_link_lengths(network_link_df)
    link_to_node = get_link_to_node_mapping(network_link_df)
    node_coords = get_node_coordinates(network_node_df)

    if network_center_link_id not in link_to_node:
        logger.warning(
            "Network centre link %r not found in network; "
            "depot_to_network_center_euclidean_km will be NaN.",
            network_center_link_id,
        )

    network_graph: Optional[nx.DiGraph] = None
    if compute_network_distances:
        if verbose:
            logger.info("Building network graph...")
        network_graph = build_network_graph(network_link_df, network_node_df)
        if verbose:
            logger.info(
                "Graph built with %d nodes and %d edges",
                network_graph.number_of_nodes(),
                network_graph.number_of_edges(),
            )

    spatial_cache: Dict[Tuple, Dict[str, Optional[float]]] = {}
    all_metrics: List[Dict[str, Any]] = []
    failures: List[Tuple[str, str]] = []
    skipped_incomplete = 0

    scenario_iter = tqdm(scenarios, desc="Processing scenarios", disable=not verbose)
    for config in scenario_iter:
        scenario_iter.set_postfix_str(config.folder_name[-40:])
        if not is_scenario_complete(config):
            skipped_incomplete += 1
            continue
        try:
            metrics = compute_scenario_metrics(
                config,
                network_link_df,
                link_lengths,
                link_to_node,
                node_coords,
                network_graph,
                original_tw,
                last_iter,
                compute_network_distances,
                network_center_link_id,
                spatial_cache,
            )
            shipment_df = compute_merged_shipment_df(config, network_link_df)
            geo_df = create_geo_dataframe(config, link_to_node, node_coords, original_tw, last_iter)
            save_scenario_outputs(config, metrics, shipment_df, geo_df, output_path)
            all_metrics.append(metrics)
        except Exception as exc:  # noqa: BLE001 - keep batch alive, record failure
            failures.append((config.folder_name, str(exc)))
            if verbose:
                tqdm.write(f"ERROR processing {config.folder_name}: {exc}")
            continue

    metrics_df = pd.DataFrame(all_metrics)
    combined_path = os.path.join(output_path, "all_scenarios_metrics.csv.gz")
    metrics_df.to_csv(combined_path, index=False, compression="gzip")

    if verbose:
        logger.info(
            "Done. %d processed, %d skipped (incomplete output), %d failed. Combined metrics: %s",
            len(all_metrics),
            skipped_incomplete,
            len(failures),
            combined_path,
        )
        if failures:
            logger.warning("First failures: %s", failures[:5])
    return metrics_df


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    process_all_scenarios(
        input_path=MATSIM_OUTPUT_PATH,
        output_path=CLEAN_OUTPUT_PATH,
        network_path=NETWORK_PATH,
        compute_network_distances=True,
        network_center_link_id=DEFAULT_NETWORK_CENTER_LINK_ID,  # change to use a custom centre
        verbose=True,
    )
