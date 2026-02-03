"""
Author: Xander PENG
Date: 2026-01-29
File: metric_anls.py
Description: This is a utility that can process and analyze MATSim output files,
    and compute various metrics related to transportation simulations.
    
"""

import matsim_output_reader
import pandas as pd
import geopandas as gpd
import numpy as np
from typing import List, Dict, Optional
import networkx as nx


def derive_freight_vehicle_travel_chains(events_file: str) -> Dict[str, List[str]]:
    """ Get the travel chains of freight vehicles from events file.

    Args:
        events_file (str): Path to the MATSim events file.

    Returns:
        Dict[str, List[str]]: key: vehicle_id, value: list of link_ids representing the travel chain
    """
    # Read relevant events: entered link and left link events
    events_df = matsim_output_reader.read_matsim_events_as_df(
        events_file, 
        "entered link,left link,vehicle enters traffic,vehicle leaves traffic"
    )
    
    # Filter for freight vehicles only (vehicle IDs containing 'freight')
    events_df = events_df[events_df['vehicle'].str.contains('freight', na=False)].copy()
    
    # Sort by time to ensure proper order
    events_df = events_df.sort_values('time')
    
    # Build travel chains for each vehicle
    travel_chains = {}
    
    for vehicle_id in events_df['vehicle'].unique():
        vehicle_events = events_df[events_df['vehicle'] == vehicle_id].copy()
        
        # Collect links from 'entered link' events
        link_chain = []
        for _, event in vehicle_events.iterrows():
            if event['type'] == 'entered link' and pd.notna(event.get('link')):
                link_chain.append(event['link'])
            elif event['type'] == 'vehicle enters traffic' and pd.notna(event.get('link')):
                # Add the starting link when vehicle enters traffic
                if not link_chain or link_chain[-1] != event['link']:
                    link_chain.append(event['link'])
        
        travel_chains[vehicle_id] = link_chain
    
    return travel_chains


def compute_freight_vehicle_km_traveled(travel_chains: Dict[str, List[str]], network_gdf: gpd.GeoDataFrame) -> Dict[str, float]:
    """ Compute the kilometers traveled by each freight vehicle. 
    
    Args:
        travel_chains (Dict[str, List[str]]): Dictionary with vehicle_id as key and list of link_ids as value.
        network_gdf (gpd.GeoDataFrame): GeoDataFrame containing network links with 'link_id' and geometry.
    
    Returns:
        Dict[str, float]: Dictionary with vehicle_id as key and total km traveled as value.
    """
    # Create a lookup for link lengths
    # Assuming the network GeoDataFrame has 'link_id' and geometry columns
    if 'link_id' in network_gdf.columns and 'length' in network_gdf.columns:
        link_id_col = 'link_id'
    else:
        raise ValueError("Network GeoDataFrame must have 'link_id' and 'length' columns")
    
    # Compute total km for each vehicle
    vehicle_km = {}
    for vehicle_id, links in travel_chains.items():
        total_km = 0.0
        for link_id in links:
            total_km += network_gdf.loc[network_gdf['link_id'] == link_id, 'length'].sum()
        vehicle_km[vehicle_id] = total_km
    
    return vehicle_km


def compute_freight_vehicle_travel_times(events_file: str) -> Dict[str, float]:
    """ Compute the travel times of each freight vehicle.
    
    Args:
        events_file (str): Path to the MATSim events file.
    
    Returns:
        Dict[str, float]: Dictionary with vehicle_id as key and total travel time (in seconds) as value.
    """
    # Read tour start and end events
    events_df = matsim_output_reader.read_matsim_events_as_df(
        events_file, 
        "Freight tour starts,Freight tour ends"
    )
    
    # Sort by time
    events_df = events_df.sort_values('time')
    
    # Compute travel time for each vehicle/tour
    travel_times = {}
    
    # Group by vehicle
    for vehicle_id in events_df['vehicle'].unique():
        vehicle_events = events_df[events_df['vehicle'] == vehicle_id].copy()
        
        # Find tour start and end times
        start_events = vehicle_events[vehicle_events['type'] == 'Freight tour starts']
        end_events = vehicle_events[vehicle_events['type'] == 'Freight tour ends']
        
        if len(start_events) > 0 and len(end_events) > 0:
            start_time = start_events['time'].min()
            end_time = end_events['time'].max()
            travel_times[vehicle_id] = end_time - start_time
        else:
            travel_times[vehicle_id] = 0.0
    
    return travel_times


def compute_freight_shipment_travel_distance_and_time(events_file: str, 
                                                       network_gdf: Optional[gpd.GeoDataFrame] = None) -> pd.DataFrame:
    """ Compute the travel distance and time of each freight shipment.
    
    Args:
        events_file (str): Path to the MATSim events file.
        network_gdf (gpd.GeoDataFrame, optional): GeoDataFrame containing network links with 'link_id' and 'length' columns.
    
    Returns:
        pd.DataFrame: DataFrame with shipment details including travel time and optionally distance.
    """
    # Read shipment pickup and delivery events
    shipment_events_df = matsim_output_reader.read_matsim_events_as_df(
        events_file, 
        "Freight shipment pickup starts,Freight shipment pickup ends,Freight shipment delivered starts,Freight shipment delivered ends"
    )
    
    # Sort by time
    shipment_events_df = shipment_events_df.sort_values('time')
    
    # If network is provided, also read link traversal events for distance calculation
    if network_gdf is not None:
        link_events_df = matsim_output_reader.read_matsim_events_as_df(
            events_file,
            "entered link,vehicle enters traffic"
        )
        # Filter for freight vehicles
        link_events_df = link_events_df[link_events_df['vehicle'].str.contains('freight', na=False)].copy()
        link_events_df = link_events_df.sort_values('time')
        
        # Create link length lookup
        if 'link_id' not in network_gdf.columns or 'length' not in network_gdf.columns:
            raise ValueError("Network GeoDataFrame must have 'link_id' and 'length' columns")
        link_lengths = dict(zip(network_gdf['link_id'], network_gdf['length']))
    
    shipment_data = []
    
    # Get unique shipment IDs
    shipment_ids = shipment_events_df['shipmentId'].unique()
    
    for shipment_id in shipment_ids:
        if pd.isna(shipment_id):
            continue
            
        shipment_events = shipment_events_df[shipment_events_df['shipmentId'] == shipment_id].copy()
        
        # Get pickup and delivery times
        pickup_start = shipment_events[shipment_events['type'] == 'Freight shipment pickup starts']
        pickup_end = shipment_events[shipment_events['type'] == 'Freight shipment pickup ends']
        delivery_start = shipment_events[shipment_events['type'] == 'Freight shipment delivered starts']
        delivery_end = shipment_events[shipment_events['type'] == 'Freight shipment delivered ends']
        
        if len(pickup_end) > 0 and len(delivery_start) > 0:
            pickup_time = pickup_end['time'].iloc[0]
            delivery_time = delivery_start['time'].iloc[0]
            travel_time = delivery_time - pickup_time
            
            # Get additional info
            carrier_id = pickup_start['carrierId'].iloc[0] if len(pickup_start) > 0 else None
            vehicle_id = pickup_start['vehicle'].iloc[0] if len(pickup_start) > 0 else None
            pickup_link = pickup_start['link'].iloc[0] if len(pickup_start) > 0 else None
            delivery_link = delivery_start['link'].iloc[0] if len(delivery_start) > 0 else None
            capacity_demand = pickup_start['capacityDemand'].iloc[0] if len(pickup_start) > 0 else None
            
            # Calculate accurate travel distance if network is provided
            travel_distance_km = None
            if network_gdf is not None and vehicle_id is not None:
                # Get links traversed by this vehicle between pickup and delivery times
                vehicle_links = link_events_df[
                    (link_events_df['vehicle'] == vehicle_id) &
                    (link_events_df['time'] >= pickup_time) &
                    (link_events_df['time'] <= delivery_time)
                ].copy()
                
                # Sum up the lengths of all links traversed
                total_distance = 0.0
                for _, link_event in vehicle_links.iterrows():
                    link_id = link_event.get('link')
                    if link_id and link_id in link_lengths:
                        total_distance += link_lengths[link_id]
                
                travel_distance_km = total_distance / 1000.0  # Convert meters to km
            
            shipment_data.append({
                'shipment_id': shipment_id,
                'carrier_id': carrier_id,
                'vehicle_id': vehicle_id,
                'pickup_link': pickup_link,
                'delivery_link': delivery_link,
                'pickup_time': pickup_time,
                'delivery_time': delivery_time,
                'travel_time_seconds': travel_time,
                'travel_distance_km': travel_distance_km,
                'capacity_demand': capacity_demand
            })
    
    return pd.DataFrame(shipment_data)


def _time_str_to_seconds(time_str: str) -> float:
    """Convert time string (HH:MM:SS) to seconds.
    
    Args:
        time_str (str): Time string in format "HH:MM:SS" or already numeric.
    
    Returns:
        float: Time in seconds.
    """
    if isinstance(time_str, (int, float)):
        return float(time_str)
    if isinstance(time_str, str):
        # Try direct float conversion first
        try:
            return float(time_str)
        except ValueError:
            pass
        # Parse HH:MM:SS format
        parts = time_str.split(':')
        if len(parts) == 3:
            hours, minutes, seconds = parts
            return float(hours) * 3600 + float(minutes) * 60 + float(seconds)
        elif len(parts) == 2:
            minutes, seconds = parts
            return float(minutes) * 60 + float(seconds)
    return None


def analyse_missed_time_windows(events_file: str, shipments_df: pd.DataFrame) -> pd.DataFrame:
    """ Analyze the missed time windows for freight shipments.
    
    Args:
        events_file (str): Path to the MATSim events file.
        shipments_df (pd.DataFrame): DataFrame containing shipment info with time windows.
            Expected columns: 'shipment_id', 'start_delivery', 'end_delivery'
    
    Returns:
        pd.DataFrame: DataFrame with time window analysis including missed windows.
    """
    # Read delivery events to get actual delivery times
    events_df = matsim_output_reader.read_matsim_events_as_df(
        events_file, 
        "Freight shipment delivered starts,Freight shipment delivered ends"
    )
    
    # Get actual delivery start times
    delivery_starts = events_df[events_df['type'] == 'Freight shipment delivered starts'].copy()
    
    # Create a lookup for actual delivery times
    actual_delivery_times = {}
    for _, row in delivery_starts.iterrows():
        shipment_id = row.get('shipmentId')
        if shipment_id:
            actual_delivery_times[shipment_id] = row['time']
    
    # Analyze each shipment
    analysis_data = []
    
    for _, shipment in shipments_df.iterrows():
        shipment_id = shipment.get('shipment_id') or shipment.get('shipmentId')
        
        # Get time window bounds (convert from string to seconds if needed)
        start_delivery = shipment.get('start_delivery') or shipment.get('startDelivery')
        end_delivery = shipment.get('end_delivery') or shipment.get('endDelivery')
        
        # Convert to seconds (handles HH:MM:SS format)
        start_delivery = _time_str_to_seconds(start_delivery)
        end_delivery = _time_str_to_seconds(end_delivery)
        
        actual_time = actual_delivery_times.get(shipment_id)
        
        if actual_time is not None and start_delivery is not None and end_delivery is not None:
            # Check if delivery was within time window
            is_early = actual_time < start_delivery
            is_late = actual_time > end_delivery
            is_on_time = not is_early and not is_late
            
            # Calculate deviation
            if is_early:
                deviation = start_delivery - actual_time  # positive = arrived early
                deviation_with_sign = -deviation
            elif is_late:
                deviation = actual_time - end_delivery  # positive = arrived late
                deviation_with_sign = deviation
            else:
                deviation = 0.0
                deviation_with_sign = 0.0
            
            analysis_data.append({
                'shipment_id': shipment_id,
                'time_window_start': start_delivery,
                'time_window_end': end_delivery,
                'actual_delivery_time': actual_time,
                'is_on_time': is_on_time,
                'is_early': is_early,
                'is_late': is_late,
                'deviation_seconds': deviation,
                'deviation_minutes': deviation / 60.0,
                'deviation_with_sign (min)': deviation_with_sign / 60.0
            })
        else:
            analysis_data.append({
                'shipment_id': shipment_id,
                'time_window_start': start_delivery,
                'time_window_end': end_delivery,
                'actual_delivery_time': actual_time,
                'is_on_time': None,
                'is_early': None,
                'is_late': None,
                'deviation_seconds': None,
                'deviation_minutes': None,
                'deviation_with_sign (min)': None
            })
    
    return pd.DataFrame(analysis_data)

def compute_receiver_location_clustering_index(shipments_df: pd.DataFrame,
                                               network: nx.Graph,
                                               network_based_dist: bool = True) -> float:
    """ Compute the clustering index of receiver locations for freight shipments.
        The distance (based on the network distance or not) between each pair of receiver locations is calculated,
        and the average distance to the nearest neighbor is computed as the clustering index.
    Args:
        shipments_df (pd.DataFrame): DataFrame containing shipment info with receiver coordinates.
            Expected columns: 'delivery_link_id' or 'link_id'
        network: networkx graph representing the transportation network.
            For network_based_dist=True: edges should have 'weight' or 'length' attribute.
            For network_based_dist=False: nodes should have 'x' and 'y' attributes for coordinates.
        network_based_dist (bool): Whether to use network-based distance (shortest path) or Euclidean distance.
    
    Returns:
        float: Clustering index (average nearest neighbor distance; lower values indicate more clustering).
    """
    # Get unique receiver locations (delivery link IDs)
    if 'delivery_link_id' in shipments_df.columns:
        link_col = 'delivery_link_id'
    elif 'delivery_link' in shipments_df.columns:
        link_col = 'delivery_link'
    elif 'link_id' in shipments_df.columns:
        link_col = 'link_id'
    else:
        raise ValueError("shipments_df must have 'delivery_link_id', 'delivery_link', or 'link_id' column")
    
    receiver_locations = shipments_df[link_col].dropna().unique().tolist()
    
    if len(receiver_locations) < 2:
        return 0.0  # Not enough locations to compute clustering
    
    # Filter to only include nodes that exist in the network
    valid_locations = [loc for loc in receiver_locations if loc in network.nodes()]
    
    if len(valid_locations) < 2:
        return 0.0
    
    if network_based_dist:
        # Compute shortest path lengths using Dijkstra's algorithm
        # Use 'weight' attribute if available, otherwise use 'length'
        weight_attr = 'weight'
        if not any('weight' in network.edges[e] for e in list(network.edges())[:1] if network.edges()):
            weight_attr = 'length' if any('length' in network.edges[e] for e in list(network.edges())[:1] if network.edges()) else None
        
        # Compute shortest paths only for valid locations
        nearest_neighbor_distances = []
        
        for source in valid_locations:
            try:
                # Get shortest path lengths from source to all other nodes
                path_lengths = nx.single_source_dijkstra_path_length(network, source, weight=weight_attr)
                
                # Find minimum distance to other receiver locations
                min_dist = float('inf')
                for target in valid_locations:
                    if target != source and target in path_lengths:
                        dist = path_lengths[target]
                        if dist < min_dist:
                            min_dist = dist
                
                if min_dist < float('inf'):
                    nearest_neighbor_distances.append(min_dist)
            except nx.NetworkXError:
                # Skip if no path exists
                continue
    else:
        # Use Euclidean distance based on node coordinates
        # Nodes should have 'x' and 'y' attributes
        coords = {}
        for node in valid_locations:
            node_data = network.nodes[node]
            if 'x' in node_data and 'y' in node_data:
                coords[node] = (float(node_data['x']), float(node_data['y']))
            elif 'pos' in node_data:
                coords[node] = node_data['pos']
        
        if len(coords) < 2:
            raise ValueError("Nodes must have 'x' and 'y' attributes or 'pos' attribute for Euclidean distance")
        
        valid_locations = list(coords.keys())
        nearest_neighbor_distances = []
        
        for source in valid_locations:
            src_coord = coords[source]
            min_dist = float('inf')
            
            for target in valid_locations:
                if target != source:
                    tgt_coord = coords[target]
                    # Euclidean distance
                    dist = np.sqrt((src_coord[0] - tgt_coord[0])**2 + (src_coord[1] - tgt_coord[1])**2)
                    if dist < min_dist:
                        min_dist = dist
            
            if min_dist < float('inf'):
                nearest_neighbor_distances.append(min_dist)
    
    # Return average nearest neighbor distance
    if len(nearest_neighbor_distances) == 0:
        return 0.0
    
    return np.mean(nearest_neighbor_distances)

def convert_link_df_to_networkx_graph(link_df: pd.DataFrame) -> nx.Graph:
    """ Convert a DataFrame of network links to a NetworkX graph.
    
    Args:
        link_df (pd.DataFrame): DataFrame containing network links with columns:
            'link_id', 'from_node', 'to_node', and optionally 'length' or 'weight'.
    
    Returns:
        nx.Graph: NetworkX graph representing the network.
    """
    G = nx.DiGraph()
    
    for _, row in link_df.iterrows():
        from_node = row['from_node']
        to_node = row['to_node']
        link_id = row['link_id']
        
        # Add nodes if they don't exist
        if not G.has_node(from_node):
            G.add_node(from_node)
        if not G.has_node(to_node):
            G.add_node(to_node)
        
        # Add edge with attributes
        edge_attrs = {'link_id': link_id}
        if 'length' in row:
            edge_attrs['length'] = row['length']
        if 'weight' in row:
            edge_attrs['weight'] = row['weight']
        
        G.add_edge(from_node, to_node, **edge_attrs)
    
    return G


def compute_nearest_neighbor_index(points: np.ndarray,
                                   study_area: float = None,
                                   study_bounds: tuple = None) -> Dict:
    """
    Calculate the Nearest Neighbor Index (NNI) for a set of points.
    
    The NNI is a measure of spatial dispersion:
    - NNI < 1: Clustered distribution
    - NNI = 1: Random distribution  
    - NNI > 1: Dispersed/uniform distribution
    
    Formula:
        NNI = D_observed / D_expected
        D_expected = 0.5 / sqrt(n / A)
        
    where n = number of points, A = study area
    
    Args:
        points: Array of shape (n, 2) containing x, y coordinates of points
        study_area: Area of the study region (optional, will be estimated from bounding box if not provided)
        study_bounds: Tuple of (x_min, y_min, x_max, y_max) for study area bounds (optional)
            If provided, used to calculate study_area
    
    Returns:
        Dict containing:
            - 'nni': Nearest Neighbor Index value
            - 'mean_observed_distance': Mean observed nearest neighbor distance
            - 'mean_expected_distance': Mean expected nearest neighbor distance under random distribution
            - 'z_score': Z-score for statistical significance test
            - 'p_value': Two-tailed p-value for the z-score
            - 'pattern': String describing the pattern ('clustered', 'random', or 'dispersed')
            - 'n_points': Number of points
            - 'study_area': Study area used in calculation
    
    Examples:
        # Using coordinates array
        coords = np.array([[1, 2], [3, 4], [5, 6], [7, 8]])
        result = compute_nearest_neighbor_index(coords)
        
        # With explicit study area
        result = compute_nearest_neighbor_index(coords, study_area=100)
        
        # With study bounds
        result = compute_nearest_neighbor_index(coords, study_bounds=(0, 0, 10, 10))
        
        # From a GeoDataFrame
        coords = np.column_stack([gdf.geometry.x, gdf.geometry.y])
        result = compute_nearest_neighbor_index(coords)
    """
    from scipy.spatial import distance
    from scipy import stats as scipy_stats
    
    points = np.asarray(points)
    n = len(points)
    
    if n < 2:
        raise ValueError("At least 2 points are required to compute NNI.")
    
    # Calculate study area if not provided
    if study_area is None:
        if study_bounds is not None:
            x_min, y_min, x_max, y_max = study_bounds
        else:
            # Estimate from bounding box of points
            x_min, y_min = points.min(axis=0)
            x_max, y_max = points.max(axis=0)
        
        # Add small buffer to avoid zero area
        width = max(x_max - x_min, 1e-10)
        height = max(y_max - y_min, 1e-10)
        study_area = width * height
    
    # Calculate pairwise distances
    dist_matrix = distance.cdist(points, points, metric='euclidean')
    
    # Set diagonal to infinity to exclude self-distances
    np.fill_diagonal(dist_matrix, np.inf)
    
    # Find nearest neighbor distance for each point
    nn_distances = dist_matrix.min(axis=1)
    
    # Mean observed nearest neighbor distance
    mean_observed = nn_distances.mean()
    
    # Expected mean nearest neighbor distance under random distribution
    # D_expected = 0.5 / sqrt(n / A) = 0.5 * sqrt(A / n)
    mean_expected = 0.5 * np.sqrt(study_area / n)
    
    # Nearest Neighbor Index
    nni = mean_observed / mean_expected
    
    # Standard error for significance testing
    # SE = 0.26136 / sqrt(n^2 / A) = 0.26136 * sqrt(A) / n
    se = 0.26136 * np.sqrt(study_area) / n
    
    # Z-score
    z_score = (mean_observed - mean_expected) / se
    
    # Two-tailed p-value
    p_value = 2 * (1 - scipy_stats.norm.cdf(abs(z_score)))
    
    # Determine pattern
    if nni < 0.8:
        pattern = 'clustered'
    elif nni > 1.2:
        pattern = 'dispersed'
    else:
        pattern = 'random'
    
    return {
        'nni': nni,
        'mean_observed_distance': mean_observed,
        'mean_expected_distance': mean_expected,
        'z_score': z_score,
        'p_value': p_value,
        'pattern': pattern,
        'n_points': n,
        'study_area': study_area
    }


def compute_ashmans_d(
    df: pd.DataFrame, 
    col_name: str, 
    n_components: int = 2,
    random_state: int = 42
) -> Dict:
    """
    Compute Ashman's D index to assess bimodality of a distribution.
    
    Ashman's D index is commonly used to determine whether a distribution 
    is truly bimodal. The formula is:
    
    D = sqrt(2) * |μ₁ - μ₂| / sqrt(σ₁² + σ₂²)
    
    Interpretation:
    - D > 2: Strong evidence of bimodality (two distinct modes)
    - D ≈ 2: Borderline bimodality
    - D < 2: Weak or no bimodality (unimodal distribution likely)
    
    Args:
        df (pd.DataFrame): Input DataFrame containing the data.
        col_name (str): Name of the column to analyze for bimodality.
        n_components (int): Number of Gaussian components to fit (default: 2).
        random_state (int): Random state for reproducibility.
    
    Returns:
        Dict: Dictionary containing:
            - 'ashmans_d': The computed Ashman's D index
            - 'mu1', 'mu2': Means of the two Gaussian components
            - 'sigma1', 'sigma2': Standard deviations of the two components
            - 'weights': Mixing weights of the components
            - 'is_bimodal': Boolean indicating if D > 2 (strong bimodality)
            - 'interpretation': Text interpretation of the result
    """
    from sklearn.mixture import GaussianMixture
    
    # Extract data and remove NaN values
    data = df[col_name].dropna().values.reshape(-1, 1)
    
    if len(data) < n_components:
        raise ValueError(f"Not enough data points ({len(data)}) for {n_components} components")
    
    # Fit Gaussian Mixture Model
    gmm = GaussianMixture(n_components=n_components, random_state=random_state)
    gmm.fit(data)
    
    # Extract parameters
    means = gmm.means_.flatten()
    variances = gmm.covariances_.flatten()
    weights = gmm.weights_
    
    # Sort by means to ensure consistent ordering
    sorted_indices = np.argsort(means)
    mu1, mu2 = means[sorted_indices[0]], means[sorted_indices[1]]
    sigma1 = np.sqrt(variances[sorted_indices[0]])
    sigma2 = np.sqrt(variances[sorted_indices[1]])
    weights = weights[sorted_indices]
    
    # Compute Ashman's D index
    # D = sqrt(2) * |μ₁ - μ₂| / sqrt(σ₁² + σ₂²)
    ashmans_d = np.sqrt(2) * np.abs(mu1 - mu2) / np.sqrt(sigma1**2 + sigma2**2)
    
    # Determine interpretation
    if ashmans_d > 2:
        interpretation = "Strong bimodality (two distinct modes)"
        is_bimodal = True
    elif ashmans_d >= 1.5:
        interpretation = "Moderate bimodality (borderline)"
        is_bimodal = False
    else:
        interpretation = "Weak or no bimodality (likely unimodal)"
        is_bimodal = False
    
    return {
        'ashmans_d': ashmans_d,
        'mu1': mu1,
        'mu2': mu2,
        'sigma1': sigma1,
        'sigma2': sigma2,
        'weights': weights.tolist(),
        'is_bimodal': is_bimodal,
        'interpretation': interpretation,
        'n_samples': len(data)
    }