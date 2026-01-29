"""
Author: Xander PENG
Date: 2026-01-29
File: agg_anls.py
Description: Aggregate analysis module for MATSim freight collaboration experiments.
    This module processes multiple scenario outputs and generates aggregated metrics,
    shipment details, and geo data for each scenario.
"""

import os
import re
import gzip
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Any
from dataclasses import dataclass
from enum import Enum

import pandas as pd
import geopandas as gpd
import numpy as np
import networkx as nx
from shapely.geometry import Point
from tqdm import tqdm

import matsim
import matsim_output_reader
import metric_anls


# ============================================================================
# Data Classes and Enums
# ============================================================================

@dataclass
class ScenarioConfig:
    """Configuration for a single scenario parsed from folder name."""
    depot_location: str  # 'center' or 'left'
    receiver_distribution: str  # 'DISPERSED', 'CLUSTERED', 'FULLY_RANDOM'
    allocation_factor: float
    penalty: float
    instance: int
    folder_name: str
    folder_path: str


class DepotLocation(Enum):
    INSIDE = 'center'
    OUTSIDE = 'left'


class ReceiverDistribution(Enum):
    DISPERSED = 'DISPERSED'
    CLUSTERED = 'CLUSTERED'
    RANDOM = 'FULLY_RANDOM'


# ============================================================================
# Helper Functions
# ============================================================================

def parse_folder_name(folder_name: str, base_path: str) -> Optional[ScenarioConfig]:
    """
    Parse a scenario folder name to extract configuration parameters.
    
    Expected format: {depot}-{receiver_dist}-penSweep-af{af}-p{penalty}-exactShapley-i{instance}
    Example: center-FULLY_RANDOM-penSweep-af0.80-p0.0056-exactShapley-i15
    
    Args:
        folder_name: Name of the scenario folder.
        base_path: Base path where the folder is located.
    
    Returns:
        ScenarioConfig object or None if parsing fails.
    """
    pattern = r'^(center|left)-(DISPERSED|CLUSTERED|FULLY_RANDOM)-penSweep-af(\d+\.\d+)-p(\d+\.\d+)-exactShapley-i(\d+)$'
    match = re.match(pattern, folder_name)
    
    if not match:
        return None
    
    return ScenarioConfig(
        depot_location=match.group(1),
        receiver_distribution=match.group(2),
        allocation_factor=float(match.group(3)),
        penalty=float(match.group(4)),
        instance=int(match.group(5)),
        folder_name=folder_name,
        folder_path=os.path.join(base_path, folder_name)
    )


def get_matching_scenarios(
    base_path: str,
    depot_locations: Optional[List[str]] = None,
    receiver_distributions: Optional[List[str]] = None,
    penalties: Optional[List[float]] = None,
    instances: Optional[List[int]] = None,
    allocation_factor: float = 0.8
) -> List[ScenarioConfig]:
    """
    Get list of scenario configurations matching the specified filters.
    
    Args:
        base_path: Base path containing scenario folders.
        depot_locations: List of depot locations to include (e.g., ['center', 'left']).
        receiver_distributions: List of receiver distributions to include.
        penalties: List of penalty values to include.
        instances: List of instance numbers to include.
        allocation_factor: Allocation factor to match (default 0.8).
    
    Returns:
        List of ScenarioConfig objects matching the filters.
    """
    scenarios = []
    
    for folder_name in os.listdir(base_path):
        folder_path = os.path.join(base_path, folder_name)
        if not os.path.isdir(folder_path):
            continue
        
        config = parse_folder_name(folder_name, base_path)
        if config is None:
            continue
        
        # Apply filters
        if depot_locations and config.depot_location not in depot_locations:
            continue
        if receiver_distributions and config.receiver_distribution not in receiver_distributions:
            continue
        if penalties and not any(abs(config.penalty - p) < 1e-6 for p in penalties):
            continue
        if instances and config.instance not in instances:
            continue
        if abs(config.allocation_factor - allocation_factor) > 1e-6:
            continue
        
        scenarios.append(config)
    
    return sorted(scenarios, key=lambda x: (x.depot_location, x.receiver_distribution, x.penalty, x.instance))


def build_network_graph(network_link_df: pd.DataFrame, network_node_df: pd.DataFrame) -> nx.DiGraph:
    """
    Build a NetworkX directed graph from MATSim network data.
    
    Args:
        network_link_df: DataFrame with link data (link_id, from_node, to_node, length).
        network_node_df: DataFrame with node data (node_id, x, y).
    
    Returns:
        NetworkX DiGraph with nodes having x, y attributes and edges having length/weight.
    """
    G = nx.DiGraph()
    
    # Add nodes with coordinates
    for _, row in network_node_df.iterrows():
        node_id = row['node_id']
        G.add_node(node_id, x=float(row['x']), y=float(row['y']))
    
    # Add edges with length as weight
    for _, row in network_link_df.iterrows():
        from_node = row['from_node']
        to_node = row['to_node']
        link_id = row['link_id']
        length = float(row['length']) if 'length' in row else 1.0
        
        G.add_edge(from_node, to_node, link_id=link_id, length=length, weight=length)
    
    return G


def compute_euclidean_distance(x1: float, y1: float, x2: float, y2: float) -> float:
    """Compute Euclidean distance between two points."""
    return np.sqrt((x1 - x2) ** 2 + (y1 - y2) ** 2)


def compute_network_distance(
    graph: nx.DiGraph,
    source_node: str,
    target_node: str
) -> float:
    """
    Compute shortest path distance between two nodes in the network.
    
    Returns:
        Distance in meters, or np.inf if no path exists.
    """
    try:
        return nx.shortest_path_length(graph, source_node, target_node, weight='length')
    except (nx.NetworkXNoPath, nx.NodeNotFound):
        return np.inf


def get_link_to_node_mapping(network_link_df: pd.DataFrame) -> Dict[str, str]:
    """Create a mapping from link_id to to_node (destination node of link)."""
    return dict(zip(network_link_df['link_id'], network_link_df['to_node']))


def get_node_coordinates(network_node_df: pd.DataFrame) -> Dict[str, Tuple[float, float]]:
    """Create a mapping from node_id to (x, y) coordinates."""
    return {row['node_id']: (float(row['x']), float(row['y'])) 
            for _, row in network_node_df.iterrows()}


# ============================================================================
# Core Analysis Functions
# ============================================================================

def compute_scenario_metrics(
    config: ScenarioConfig,
    network_link_df: pd.DataFrame,
    network_node_df: pd.DataFrame,
    network_graph: Optional[nx.DiGraph] = None,
    original_tw: Tuple[int, int] = (6, 8),
    last_iter: int = 50,
    compute_network_distances: bool = True
) -> Dict[str, Any]:
    """
    Compute all metrics for a single scenario.
    
    Args:
        config: ScenarioConfig for the scenario.
        network_link_df: Network links DataFrame.
        network_node_df: Network nodes DataFrame.
        network_graph: Pre-built NetworkX graph (optional, will be built if None).
        original_tw: Original time window tuple (start_hour, end_hour).
        last_iter: Last iteration number for reading collaboration data.
        compute_network_distances: Whether to compute network-based distances.
    
    Returns:
        Dictionary with all computed metrics.
    """
    folder_path = config.folder_path
    
    # File paths
    carriers_path = os.path.join(folder_path, 'output_carriers.xml.gz')
    receivers_path = os.path.join(folder_path, 'receivers.xml.gz')
    events_path = os.path.join(folder_path, 'output_events.xml.gz')
    iter0_events_path = os.path.join(folder_path, 'ITERS', 'it.0', '0.events.xml.gz')
    
    # Build graph if not provided
    if network_graph is None and compute_network_distances:
        network_graph = build_network_graph(network_link_df, network_node_df)
    
    # Get mappings
    link_to_node = get_link_to_node_mapping(network_link_df)
    node_coords = get_node_coordinates(network_node_df)
    
    # ----- Read basic data -----
    carriers_df, shipments_df = matsim_output_reader.read_carriers(carriers_path)
    receivers_dict = matsim_output_reader.read_receivers(receivers_path, original_tw)
    collaboration_data = matsim_output_reader.read_collaboration_allocation_data(folder_path, last_iter)
    
    # ----- Fleet metrics -----
    fleet_size = carriers_df['num_vehicles'].sum()
    iter0_fleet_size = carriers_df['iter0_num_vehicles'].sum()
    final_carrier_score = carriers_df['carrier_score'].sum()
    iter0_carrier_score = carriers_df['iter0_carrier_score'].sum()
    
    # ----- Receiver metrics -----
    num_collaborative_receivers = receivers_dict['count_collaborative']
    num_non_collab_receivers = receivers_dict['count_non_collaborative']
    total_receiver_scores = receivers_dict['total_score']
    
    # ----- Collaboration metrics -----
    total_cost_savings = sum(collaboration_data.values())
    
    # ----- Time window extension -----
    total_extended_tw_hours = 0.0
    for receiver in receivers_dict['collaborative_receivers']:
        orig_start, orig_end = original_tw
        new_start, new_end = receiver['time_window']
        extension = (orig_start - new_start) + (new_end - orig_end)
        total_extended_tw_hours += extension
    
    # ----- VKT (Vehicle Kilometers Traveled) -----
    travel_chains = metric_anls.derive_freight_vehicle_travel_chains(events_path)
    vkt_dict = metric_anls.compute_freight_vehicle_km_traveled(travel_chains, network_link_df)
    total_vkt = sum(vkt_dict.values()) / 1000.0  # Convert to km
    
    iter0_travel_chains = metric_anls.derive_freight_vehicle_travel_chains(iter0_events_path)
    iter0_vkt_dict = metric_anls.compute_freight_vehicle_km_traveled(iter0_travel_chains, network_link_df)
    total_iter0_vkt = sum(iter0_vkt_dict.values()) / 1000.0
    
    # ----- VTT (Vehicle Travel Time) -----
    vtt_dict = metric_anls.compute_freight_vehicle_travel_times(events_path)
    total_vtt = sum(vtt_dict.values())
    
    iter0_vtt_dict = metric_anls.compute_freight_vehicle_travel_times(iter0_events_path)
    total_iter0_vtt = sum(iter0_vtt_dict.values())
    
    # ----- Ton-km Traveled (TKT) -----
    shipment_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(events_path, network_link_df)
    # Merge with capacity demand
    if 'capacity_demand' in shipment_travel_df.columns:
        shipment_travel_df['capacity_demand'] = shipment_travel_df['capacity_demand'].fillna(1.0)
        tkt = (shipment_travel_df['travel_distance_km'] * shipment_travel_df['capacity_demand']).sum()
    else:
        tkt = shipment_travel_df['travel_distance_km'].sum()
    
    iter0_shipment_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(iter0_events_path, network_link_df)
    if 'capacity_demand' in iter0_shipment_travel_df.columns:
        iter0_shipment_travel_df['capacity_demand'] = iter0_shipment_travel_df['capacity_demand'].fillna(1.0)
        iter0_tkt = (iter0_shipment_travel_df['travel_distance_km'] * iter0_shipment_travel_df['capacity_demand']).sum()
    else:
        iter0_tkt = iter0_shipment_travel_df['travel_distance_km'].sum()
    
    # ----- Receiver distance to depot -----
    # Get depot location(s)
    depot_links = carriers_df['depot_link_id'].dropna().unique().tolist()
    depot_nodes = [link_to_node.get(link) for link in depot_links if link in link_to_node]
    depot_nodes = [n for n in depot_nodes if n is not None]
    
    # Get receiver delivery locations
    receiver_links = shipments_df['delivery_link_id'].dropna().unique().tolist()
    receiver_nodes = [link_to_node.get(link) for link in receiver_links if link in link_to_node]
    receiver_nodes = [n for n in receiver_nodes if n is not None]
    
    # Euclidean distance to depot
    euclidean_distances = []
    if depot_nodes and receiver_nodes:
        for recv_node in receiver_nodes:
            if recv_node in node_coords:
                recv_x, recv_y = node_coords[recv_node]
                min_dist = float('inf')
                for depot_node in depot_nodes:
                    if depot_node in node_coords:
                        depot_x, depot_y = node_coords[depot_node]
                        dist = compute_euclidean_distance(recv_x, recv_y, depot_x, depot_y)
                        min_dist = min(min_dist, dist)
                if min_dist < float('inf'):
                    euclidean_distances.append(min_dist)
    
    mean_receiver_dist_euclidean = np.mean(euclidean_distances) / 1000.0 if euclidean_distances else None  # km
    
    # Network distance to depot
    mean_receiver_dist_network = None
    if compute_network_distances and network_graph and depot_nodes and receiver_nodes:
        network_distances = []
        for recv_node in receiver_nodes:
            min_dist = float('inf')
            for depot_node in depot_nodes:
                dist = compute_network_distance(network_graph, depot_node, recv_node)
                min_dist = min(min_dist, dist)
            if min_dist < float('inf'):
                network_distances.append(min_dist)
        mean_receiver_dist_network = np.mean(network_distances) / 1000.0 if network_distances else None  # km
    
    # ----- Clustering Index -----
    clustering_euclidean = None
    clustering_network = None
    
    if len(receiver_nodes) >= 2:
        # Euclidean clustering
        eucl_nn_distances = []
        for i, node_i in enumerate(receiver_nodes):
            if node_i not in node_coords:
                continue
            x_i, y_i = node_coords[node_i]
            min_dist = float('inf')
            for j, node_j in enumerate(receiver_nodes):
                if i != j and node_j in node_coords:
                    x_j, y_j = node_coords[node_j]
                    dist = compute_euclidean_distance(x_i, y_i, x_j, y_j)
                    min_dist = min(min_dist, dist)
            if min_dist < float('inf'):
                eucl_nn_distances.append(min_dist)
        clustering_euclidean = np.mean(eucl_nn_distances) / 1000.0 if eucl_nn_distances else None  # km
        
        # Network-based clustering
        if compute_network_distances and network_graph:
            net_nn_distances = []
            for i, node_i in enumerate(receiver_nodes):
                min_dist = float('inf')
                for j, node_j in enumerate(receiver_nodes):
                    if i != j:
                        dist = compute_network_distance(network_graph, node_i, node_j)
                        min_dist = min(min_dist, dist)
                if min_dist < float('inf'):
                    net_nn_distances.append(min_dist)
            clustering_network = np.mean(net_nn_distances) / 1000.0 if net_nn_distances else None  # km
    
    return {
        'depot_location': config.depot_location,
        'receiver_distribution': config.receiver_distribution,
        'allocation_factor': config.allocation_factor,
        'penalty': config.penalty,
        'instance': config.instance,
        'fleet_size': fleet_size,
        'iter0_fleet_size': iter0_fleet_size,
        'final_carrier_score': final_carrier_score,
        'iter0_carrier_score': iter0_carrier_score,
        'num_collaborative_receivers': num_collaborative_receivers,
        'num_non_collab_receivers': num_non_collab_receivers,
        'total_receiver_scores': total_receiver_scores,
        'total_cost_savings': total_cost_savings,
        'total_extended_tw_hours': total_extended_tw_hours,
        'VKT_km': total_vkt,
        'iter0_VKT_km': total_iter0_vkt,
        'VTT_seconds': total_vtt,
        'iter0_VTT_seconds': total_iter0_vtt,
        'TKT_tonkm': tkt,
        'iter0_TKT_tonkm': iter0_tkt,
        'mean_receiver_dist_to_depot_euclidean_km': mean_receiver_dist_euclidean,
        'mean_receiver_dist_to_depot_network_km': mean_receiver_dist_network,
        'clustering_index_euclidean_km': clustering_euclidean,
        'clustering_index_network_km': clustering_network
    }


def compute_merged_shipment_df(
    config: ScenarioConfig,
    network_link_df: pd.DataFrame
) -> pd.DataFrame:
    """
    Create a merged shipment DataFrame combining final and iter0 data with travel info.
    
    Args:
        config: ScenarioConfig for the scenario.
        network_link_df: Network links DataFrame.
    
    Returns:
        Merged DataFrame with shipment details from all sources.
    """
    folder_path = config.folder_path
    
    # File paths
    carriers_path = os.path.join(folder_path, 'output_carriers.xml.gz')
    events_path = os.path.join(folder_path, 'output_events.xml.gz')
    iter0_events_path = os.path.join(folder_path, 'ITERS', 'it.0', '0.events.xml.gz')
    
    # Read shipment data
    _, final_shipments_df = matsim_output_reader.read_carriers(carriers_path)
    _, iter0_shipments_df = matsim_output_reader.read_iter0_carriers(folder_path, only_scores=False)
    
    # Get travel data
    final_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(events_path, network_link_df)
    iter0_travel_df = metric_anls.compute_freight_shipment_travel_distance_and_time(iter0_events_path, network_link_df)
    
    # Add prefix to iter0 columns
    iter0_shipments_df = iter0_shipments_df.add_prefix('iter0_')
    iter0_travel_df = iter0_travel_df.add_prefix('iter0_')
    
    # Rename key columns for merging
    iter0_shipments_df = iter0_shipments_df.rename(columns={'iter0_shipment_id': 'shipment_id'})
    iter0_travel_df = iter0_travel_df.rename(columns={'iter0_shipment_id': 'shipment_id'})
    
    # Add prefix to final travel columns
    final_travel_df = final_travel_df.add_prefix('final_')
    final_travel_df = final_travel_df.rename(columns={'final_shipment_id': 'shipment_id'})
    
    # Merge all data
    merged_df = final_shipments_df.copy()
    merged_df = merged_df.merge(final_travel_df, on='shipment_id', how='left')
    merged_df = merged_df.merge(
        iter0_shipments_df.drop(columns=['iter0_carrier_id'], errors='ignore'),
        on='shipment_id',
        how='left'
    )
    merged_df = merged_df.merge(iter0_travel_df, on='shipment_id', how='left')
    
    # Add scenario identifiers
    merged_df['depot_location'] = config.depot_location
    merged_df['receiver_distribution'] = config.receiver_distribution
    merged_df['penalty'] = config.penalty
    merged_df['instance'] = config.instance
    
    return merged_df


def create_geo_dataframe(
    config: ScenarioConfig,
    network_node_df: pd.DataFrame,
    network_link_df: pd.DataFrame,
    original_tw: Tuple[int, int] = (6, 8),
    last_iter: int = 50
) -> gpd.GeoDataFrame:
    """
    Create a GeoDataFrame with carrier and receiver locations and collaboration info.
    
    Args:
        config: ScenarioConfig for the scenario.
        network_node_df: Network nodes DataFrame.
        network_link_df: Network links DataFrame.
        original_tw: Original time window tuple.
        last_iter: Last iteration number.
    
    Returns:
        GeoDataFrame with geometry, collaboration status, and payoff allocations.
    """
    folder_path = config.folder_path
    
    # File paths
    carriers_path = os.path.join(folder_path, 'output_carriers.xml.gz')
    receivers_path = os.path.join(folder_path, 'receivers.xml.gz')
    
    # Get mappings
    link_to_node = get_link_to_node_mapping(network_link_df)
    node_coords = get_node_coordinates(network_node_df)
    
    # Read data
    carriers_df, shipments_df = matsim_output_reader.read_carriers(carriers_path)
    receivers_dict = matsim_output_reader.read_receivers(receivers_path, original_tw)
    collaboration_data = matsim_output_reader.read_collaboration_allocation_data(folder_path, last_iter)
    
    geo_records = []
    
    # ----- Add carriers -----
    for _, carrier in carriers_df.iterrows():
        carrier_id = carrier['carrier_id']
        depot_link = carrier['depot_link_id']
        
        # Get coordinates
        depot_node = link_to_node.get(depot_link)
        if depot_node and depot_node in node_coords:
            x, y = node_coords[depot_node]
            geometry = Point(x, y)
        else:
            geometry = None
        
        payoff = collaboration_data.get(carrier_id, 0.0)
        
        geo_records.append({
            'id': carrier_id,
            'type': 'carrier',
            'is_collaborative': True,  # Carriers are always in the collaboration
            'payoff_allocation': payoff,
            'relaxed_tw_hours': 0.0,  # Carriers don't have TW
            'link_id': depot_link,
            'geometry': geometry
        })
    
    # ----- Add collaborative receivers -----
    for receiver in receivers_dict['collaborative_receivers']:
        receiver_id = receiver['id']
        
        # Find corresponding shipment to get link
        shipment = shipments_df[shipments_df['shipment_id'].str.contains(receiver_id, na=False)]
        if len(shipment) > 0:
            delivery_link = shipment.iloc[0]['delivery_link_id']
        else:
            delivery_link = None
        
        # Get coordinates
        if delivery_link:
            node = link_to_node.get(delivery_link)
            if node and node in node_coords:
                x, y = node_coords[node]
                geometry = Point(x, y)
            else:
                geometry = None
        else:
            geometry = None
        
        # Calculate relaxed TW hours
        orig_start, orig_end = original_tw
        new_start, new_end = receiver['time_window']
        relaxed_tw = (orig_start - new_start) + (new_end - orig_end)
        
        payoff = collaboration_data.get(receiver_id, 0.0)
        
        geo_records.append({
            'id': receiver_id,
            'type': 'receiver',
            'is_collaborative': True,
            'payoff_allocation': payoff,
            'relaxed_tw_hours': relaxed_tw,
            'link_id': delivery_link,
            'geometry': geometry
        })
    
    # ----- Add non-collaborative receivers -----
    for receiver in receivers_dict['non_collaborative_receivers']:
        receiver_id = receiver['id']
        
        # Find corresponding shipment to get link
        shipment = shipments_df[shipments_df['shipment_id'].str.contains(receiver_id, na=False)]
        if len(shipment) > 0:
            delivery_link = shipment.iloc[0]['delivery_link_id']
        else:
            delivery_link = None
        
        # Get coordinates
        if delivery_link:
            node = link_to_node.get(delivery_link)
            if node and node in node_coords:
                x, y = node_coords[node]
                geometry = Point(x, y)
            else:
                geometry = None
        else:
            geometry = None
        
        geo_records.append({
            'id': receiver_id,
            'type': 'receiver',
            'is_collaborative': False,
            'payoff_allocation': 0.0,
            'relaxed_tw_hours': 0.0,
            'link_id': delivery_link,
            'geometry': geometry
        })
    
    # Create GeoDataFrame
    gdf = gpd.GeoDataFrame(geo_records, geometry='geometry')
    
    # Add scenario identifiers
    gdf['depot_location'] = config.depot_location
    gdf['receiver_distribution'] = config.receiver_distribution
    gdf['penalty'] = config.penalty
    gdf['instance'] = config.instance
    
    return gdf


# ============================================================================
# Output Functions
# ============================================================================

def save_scenario_outputs(
    config: ScenarioConfig,
    metrics: Dict[str, Any],
    shipment_df: pd.DataFrame,
    geo_df: gpd.GeoDataFrame,
    output_base_path: str
) -> str:
    """
    Save scenario outputs to files.
    
    Args:
        config: ScenarioConfig for the scenario.
        metrics: Dictionary of computed metrics.
        shipment_df: Merged shipment DataFrame.
        geo_df: GeoDataFrame with carrier/receiver locations.
        output_base_path: Base path for output files.
    
    Returns:
        Path to the created output folder.
    """
    # Create output folder name using scenario parameters
    output_folder_name = f"{config.depot_location}-{config.receiver_distribution}-af{config.allocation_factor:.2f}-p{config.penalty:.4f}-i{config.instance:02d}"
    output_folder = os.path.join(output_base_path, output_folder_name)
    
    os.makedirs(output_folder, exist_ok=True)
    
    # Save metrics as single-row CSV
    metrics_df = pd.DataFrame([metrics])
    metrics_path = os.path.join(output_folder, 'metrics.csv.gz')
    metrics_df.to_csv(metrics_path, index=False, compression='gzip')
    
    # Save shipment DataFrame
    shipment_path = os.path.join(output_folder, 'shipments.csv.gz')
    shipment_df.to_csv(shipment_path, index=False, compression='gzip')
    
    # Save GeoDataFrame as GeoJSON
    geo_path = os.path.join(output_folder, 'geo_data.geojson')
    # Filter out rows with None geometry before saving
    geo_df_valid = geo_df[geo_df['geometry'].notna()]
    if len(geo_df_valid) > 0:
        geo_df_valid.to_file(geo_path, driver='GeoJSON')
    else:
        # Save as regular JSON if no valid geometries
        geo_df.drop(columns=['geometry']).to_json(geo_path, orient='records')
    
    return output_folder


# ============================================================================
# Main Processing Function
# ============================================================================

def process_all_scenarios(
    input_path: str,
    output_path: str,
    depot_locations: Optional[List[str]] = None,
    receiver_distributions: Optional[List[str]] = None,
    penalties: Optional[List[float]] = None,
    instances: Optional[List[int]] = None,
    allocation_factor: float = 0.8,
    original_tw: Tuple[int, int] = (6, 8),
    last_iter: int = 50,
    compute_network_distances: bool = True,
    verbose: bool = True
) -> pd.DataFrame:
    """
    Process all matching scenarios and save outputs.
    
    This is the main entry point for batch processing.
    
    Args:
        input_path: Path to the folder containing scenario output folders.
        output_path: Base path for saving processed outputs.
        depot_locations: List of depot locations to include (default: all).
        receiver_distributions: List of receiver distributions to include (default: all).
        penalties: List of penalty values to include (default: all).
        instances: List of instance numbers to include (default: all).
        allocation_factor: Allocation factor to match.
        original_tw: Original time window tuple.
        last_iter: Last iteration number.
        compute_network_distances: Whether to compute network-based distances.
        verbose: Whether to print progress messages.
    
    Returns:
        DataFrame with all computed metrics for all processed scenarios.
    """
    # Get matching scenarios
    scenarios = get_matching_scenarios(
        input_path,
        depot_locations,
        receiver_distributions,
        penalties,
        instances,
        allocation_factor
    )
    
    if verbose:
        print(f"Found {len(scenarios)} matching scenarios")
    
    if len(scenarios) == 0:
        return pd.DataFrame()
    
    # Create output directory
    os.makedirs(output_path, exist_ok=True)
    
    # Load network data once (use first scenario as reference)
    first_scenario = scenarios[0]
    network_path = os.path.join(first_scenario.folder_path, 'output_network.xml.gz')
    
    if verbose:
        print(f"Loading network from: {network_path}")
    
    matsim_network = matsim.read_network(network_path)
    network_link_df = matsim_network.links
    network_node_df = matsim_network.nodes
    
    # Build network graph once
    network_graph = None
    if compute_network_distances:
        if verbose:
            print("Building network graph...")
        network_graph = build_network_graph(network_link_df, network_node_df)
        if verbose:
            print(f"Graph built with {network_graph.number_of_nodes()} nodes and {network_graph.number_of_edges()} edges")
    
    # Process each scenario
    all_metrics = []
    
    # Use tqdm progress bar
    scenario_iter = tqdm(scenarios, desc="Processing scenarios", disable=not verbose)
    
    for config in scenario_iter:
        scenario_iter.set_postfix_str(config.folder_name[:40] + "..." if len(config.folder_name) > 40 else config.folder_name)
        
        try:
            # Compute metrics
            metrics = compute_scenario_metrics(
                config,
                network_link_df,
                network_node_df,
                network_graph,
                original_tw,
                last_iter,
                compute_network_distances
            )
            all_metrics.append(metrics)
            
            # Compute merged shipment DataFrame
            shipment_df = compute_merged_shipment_df(config, network_link_df)
            
            # Create GeoDataFrame
            geo_df = create_geo_dataframe(
                config,
                network_node_df,
                network_link_df,
                original_tw,
                last_iter
            )
            
            # Save outputs
            output_folder = save_scenario_outputs(
                config,
                metrics,
                shipment_df,
                geo_df,
                output_path
            )
            
        except Exception as e:
            if verbose:
                tqdm.write(f"ERROR processing {config.folder_name}: {str(e)}")
            continue
    
    # Create combined metrics DataFrame
    metrics_df = pd.DataFrame(all_metrics)
    
    # Save combined metrics
    combined_metrics_path = os.path.join(output_path, 'all_scenarios_metrics.csv.gz')
    metrics_df.to_csv(combined_metrics_path, index=False, compression='gzip')
    
    if verbose:
        print(f"\nProcessing complete. Combined metrics saved to: {combined_metrics_path}")
    
    return metrics_df


def process_single_scenario(
    folder_path: str,
    output_path: str,
    network_link_df: Optional[pd.DataFrame] = None,
    network_node_df: Optional[pd.DataFrame] = None,
    network_graph: Optional[nx.DiGraph] = None,
    original_tw: Tuple[int, int] = (6, 8),
    last_iter: int = 50,
    compute_network_distances: bool = True
) -> Tuple[Dict[str, Any], pd.DataFrame, gpd.GeoDataFrame]:
    """
    Process a single scenario folder.
    
    Args:
        folder_path: Full path to the scenario folder.
        output_path: Base path for saving outputs.
        network_link_df: Network links DataFrame (loaded if None).
        network_node_df: Network nodes DataFrame (loaded if None).
        network_graph: Pre-built NetworkX graph (optional).
        original_tw: Original time window tuple.
        last_iter: Last iteration number.
        compute_network_distances: Whether to compute network-based distances.
    
    Returns:
        Tuple of (metrics dict, shipment DataFrame, GeoDataFrame).
    """
    folder_name = os.path.basename(folder_path)
    base_path = os.path.dirname(folder_path)
    
    config = parse_folder_name(folder_name, base_path)
    if config is None:
        raise ValueError(f"Could not parse folder name: {folder_name}")
    
    # Load network if not provided
    if network_link_df is None or network_node_df is None:
        network_path = os.path.join(folder_path, 'output_network.xml.gz')
        matsim_network = matsim.read_network(network_path)
        network_link_df = matsim_network.links
        network_node_df = matsim_network.nodes
    
    # Build graph if needed
    if compute_network_distances and network_graph is None:
        network_graph = build_network_graph(network_link_df, network_node_df)
    
    # Compute everything
    metrics = compute_scenario_metrics(
        config,
        network_link_df,
        network_node_df,
        network_graph,
        original_tw,
        last_iter,
        compute_network_distances
    )
    
    shipment_df = compute_merged_shipment_df(config, network_link_df)
    
    geo_df = create_geo_dataframe(
        config,
        network_node_df,
        network_link_df,
        original_tw,
        last_iter
    )
    
    # Save outputs
    save_scenario_outputs(config, metrics, shipment_df, geo_df, output_path)
    
    return metrics, shipment_df, geo_df


# ============================================================================
# Convenience Function for Notebook Usage
# ============================================================================

def quick_analyze(
    input_path: str,
    output_path: str,
    depot_locations: List[str] = ['center', 'left'],
    receiver_distributions: List[str] = ['DISPERSED', 'CLUSTERED'],
    penalty_list: List[float] = [0, 0.0003, 0.0008, 0.0014, 0.0028, 0.0056, 0.0098, 0.014, 0.0167, 0.0222, 0.028],
    total_instances: int = 50,
    original_tw: Tuple[int, int] = (6, 8),
    compute_network_distances: bool = True
) -> pd.DataFrame:
    """
    Quick analysis function with common default parameters.
    
    Args:
        input_path: Path to folder containing scenario outputs.
        output_path: Path for saving processed data.
        depot_locations: List of depot locations.
        receiver_distributions: List of receiver distributions.
        penalty_list: List of penalty values.
        total_instances: Total number of instances (0 to total_instances-1).
        original_tw: Original time window.
        compute_network_distances: Whether to compute network distances.
    
    Returns:
        DataFrame with all metrics.
    """
    return process_all_scenarios(
        input_path=input_path,
        output_path=output_path,
        depot_locations=depot_locations,
        receiver_distributions=receiver_distributions,
        penalties=penalty_list,
        instances=list(range(total_instances)),
        original_tw=original_tw,
        compute_network_distances=compute_network_distances,
        verbose=True
    )


if __name__ == "__main__":
    print("Starting analysis of all scenarios...")
    repo_root = r'./'
    anls_path = os.path.join(repo_root, "output", "chessboardCarrierReceiverCollab")
    print(f"Analyzing all scenarios in: {anls_path}")
    OUTPUT_PATH = os.path.join(repo_root, "data", "freightChessboardRC", "clean")
    DEPOT_LOCATIONS = [DepotLocation.INSIDE.value, DepotLocation.OUTSIDE.value]
    RECEIVER_DISTRIBUTIONS = [ReceiverDistribution.DISPERSED.value,
                              ReceiverDistribution.CLUSTERED.value,
                              ReceiverDistribution.RANDOM.value]
    PENALTY_LIST = [0, 0.0003, 0.0008, 0.0014, 0.0028, 0.0056,
                    0.0098, 0.014, 0.0167, 0.0222, 0.028]
    ORIGINAL_TW = (6, 8)
    LAST_ITER = 50
    TOTAL_INSTANCES = 50

    test_all_metrics_df = process_all_scenarios(
        input_path=anls_path,
        output_path=OUTPUT_PATH,
        depot_locations=DEPOT_LOCATIONS,  
        receiver_distributions=RECEIVER_DISTRIBUTIONS,
        penalties=PENALTY_LIST,
        allocation_factor=0.8,
        instances=list(range(TOTAL_INSTANCES)),
        original_tw=ORIGINAL_TW,
        last_iter=LAST_ITER,
        compute_network_distances=True,
        verbose=True
    )