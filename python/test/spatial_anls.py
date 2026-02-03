"""
Author: Xander PENG
Date: 2026-02-03
File: spatial_anls.py
Description: Spatial analysis utilities for MATSim network and collaboration data.
    This module provides functions for converting network graphs to GeoDataFrames
    and spatial analysis of collaborative receivers.
"""

from typing import Dict, List, Tuple, Optional, Union
import pandas as pd
import geopandas as gpd
import numpy as np
import networkx as nx
from shapely.geometry import Point, LineString


def network_graph_to_gdf(
    network_graph: nx.DiGraph,
    boundary: Optional[Union[List[float], Tuple[float, float, float, float]]] = None,
    xmin: Optional[float] = None,
    xmax: Optional[float] = None,
    ymin: Optional[float] = None,
    ymax: Optional[float] = None,
    include_nodes: bool = False,
    crs: Optional[str] = None
) -> Union[gpd.GeoDataFrame, Tuple[gpd.GeoDataFrame, gpd.GeoDataFrame]]:
    """
    Convert a NetworkX DiGraph (MATSim network) to a GeoDataFrame of links.
    
    Optionally filter by bounding box coordinates to return only links within a region.
    
    Args:
        network_graph: NetworkX DiGraph with nodes having 'x', 'y' attributes and 
            edges having 'link_id', 'length' attributes.
        boundary: Optional list/tuple of [xmin, ymin, xmax, ymax] defining the bounding box.
            If provided, only links with BOTH endpoints within the boundary are returned.
        xmin: Minimum x coordinate (alternative to boundary).
        xmax: Maximum x coordinate (alternative to boundary).
        ymin: Minimum y coordinate (alternative to boundary).
        ymax: Maximum y coordinate (alternative to boundary).
        include_nodes: If True, also return a GeoDataFrame of nodes.
        crs: Coordinate reference system (e.g., 'EPSG:32632'). Optional.
    
    Returns:
        If include_nodes=False (default):
            GeoDataFrame with columns:
                - link_id: Link identifier
                - from_node: Source node ID
                - to_node: Target node ID
                - length: Link length
                - geometry: LineString geometry
        
        If include_nodes=True:
            Tuple of (links_gdf, nodes_gdf):
                - links_gdf: GeoDataFrame of links as described above
                - nodes_gdf: GeoDataFrame with columns:
                    - node_id: Node identifier
                    - x, y: Coordinates
                    - geometry: Point geometry
    
    Examples:
        # Convert full network to GeoDataFrame
        links_gdf = network_graph_to_gdf(network_graph)
        
        # Filter by bounding box using boundary parameter
        links_gdf = network_graph_to_gdf(network_graph, boundary=[0, 0, 1000, 1000])
        
        # Filter by individual coordinate parameters
        links_gdf = network_graph_to_gdf(network_graph, xmin=0, xmax=1000, ymin=0, ymax=1000)
        
        # Get both links and nodes
        links_gdf, nodes_gdf = network_graph_to_gdf(network_graph, include_nodes=True)
    """
    # Parse boundary parameters
    if boundary is not None:
        if len(boundary) != 4:
            raise ValueError("boundary must be a list/tuple of 4 values: [xmin, ymin, xmax, ymax]")
        xmin, ymin, xmax, ymax = boundary
    
    has_boundary = all(v is not None for v in [xmin, xmax, ymin, ymax])
    
    # Build nodes GeoDataFrame
    node_records = []
    for node_id, data in network_graph.nodes(data=True):
        x = data.get('x')
        y = data.get('y')
        if x is None or y is None:
            continue
        
        x, y = float(x), float(y)
        
        # Filter by boundary if specified
        if has_boundary:
            if not (xmin <= x <= xmax and ymin <= y <= ymax):
                # Node outside boundary, but we still need it for link geometry
                pass
        
        node_records.append({
            'node_id': node_id,
            'x': x,
            'y': y,
            'geometry': Point(x, y)
        })
    
    nodes_gdf = gpd.GeoDataFrame(node_records, geometry='geometry')
    if crs:
        nodes_gdf.set_crs(crs, inplace=True)
    
    # Create node coordinate lookup
    node_coords = {row['node_id']: (row['x'], row['y']) for _, row in nodes_gdf.iterrows()}
    
    # Build links GeoDataFrame
    link_records = []
    for from_node, to_node, data in network_graph.edges(data=True):
        link_id = data.get('link_id', f"{from_node}_{to_node}")
        length = data.get('length', data.get('weight', np.nan))
        
        # Get node coordinates
        if from_node not in node_coords or to_node not in node_coords:
            continue
        
        from_x, from_y = node_coords[from_node]
        to_x, to_y = node_coords[to_node]
        
        # Filter by boundary: both endpoints must be within bounds
        if has_boundary:
            from_in = (xmin <= from_x <= xmax and ymin <= from_y <= ymax)
            to_in = (xmin <= to_x <= xmax and ymin <= to_y <= ymax)
            if not (from_in and to_in):
                continue
        
        geometry = LineString([(from_x, from_y), (to_x, to_y)])
        
        link_records.append({
            'link_id': link_id,
            'from_node': from_node,
            'to_node': to_node,
            'length': length,
            'geometry': geometry
        })
    
    links_gdf = gpd.GeoDataFrame(link_records, geometry='geometry')
    if crs:
        links_gdf.set_crs(crs, inplace=True)
    
    # Also filter nodes if boundary is specified
    if has_boundary and include_nodes:
        nodes_gdf = nodes_gdf[
            (nodes_gdf['x'] >= xmin) & (nodes_gdf['x'] <= xmax) &
            (nodes_gdf['y'] >= ymin) & (nodes_gdf['y'] <= ymax)
        ].reset_index(drop=True)
    
    if include_nodes:
        return links_gdf, nodes_gdf
    else:
        return links_gdf


def compute_mean_collab_count_by_link(
    stat_collab_links_df: pd.DataFrame,
    network_links_gdf: gpd.GeoDataFrame,
    link_id_col: str = 'link_id'
) -> gpd.GeoDataFrame:
    """
    Compute mean collaborative receiver count per link across instances.
    
    This function takes the instance-level collaborative receiver counts 
    (from aggregate_collaborative_receivers_by_link) and joins with a network 
    GeoDataFrame to produce a spatial dataset with mean collaboration statistics.
    
    Args:
        stat_collab_links_df: DataFrame with collaborative receiver counts per instance.
            Expected columns:
                - link_id: Link identifier
                - collab_receiver_count: Number of collaborative receivers at this link
                - instance_id: Instance identifier
            This is typically filtered from the output of 
            agg_anls.aggregate_collaborative_receivers_by_link() for a specific 
            (depot_location, allocation_factor, receiver_distribution, penalty) scenario.
        network_links_gdf: GeoDataFrame of network links with 'link_id' and geometry.
        link_id_col: Name of the link ID column in network_links_gdf. Default: 'link_id'.
    
    Returns:
        GeoDataFrame with all columns from network_links_gdf plus:
            - mean_collab_count: Mean count of collaborative receivers across instances
            - std_collab_count: Standard deviation of counts
            - total_collab_count: Sum of counts across all instances
            - n_instances_with_collab: Number of instances where this link had collaborators
            - collab_probability: Probability of collaboration at this link (n_instances_with_collab / total_instances)
        
        Links with no collaborative receivers have mean_collab_count = 0.
    
    Examples:
        # Filter stat_collab_links for a specific scenario
        scenario_df = all_stat_collab_links[
            (all_stat_collab_links['depot_location'] == 'left') &
            (all_stat_collab_links['receiver_distribution'] == 'DISPERSED') &
            (all_stat_collab_links['penalty'] == 0.0056)
        ]
        
        # Get network as GeoDataFrame
        network_gdf = network_graph_to_gdf(network_graph, xmin=0, xmax=10000, ymin=0, ymax=18000)
        
        # Compute mean collab count per link
        result_gdf = compute_mean_collab_count_by_link(scenario_df, network_gdf)
    """
    if stat_collab_links_df.empty:
        # Return network_gdf with zero counts
        result = network_links_gdf.copy()
        result['mean_collab_count'] = 0.0
        result['std_collab_count'] = 0.0
        result['total_collab_count'] = 0
        result['n_instances_with_collab'] = 0
        result['collab_probability'] = 0.0
        return result
    
    # Get total number of instances
    total_instances = stat_collab_links_df['instance_id'].nunique()
    
    # Aggregate by link_id: compute mean, std, sum, count
    link_stats = stat_collab_links_df.groupby('link_id').agg(
        mean_collab_count=('collab_receiver_count', 'mean'),
        std_collab_count=('collab_receiver_count', 'std'),
        total_collab_count=('collab_receiver_count', 'sum'),
        n_instances_with_collab=('collab_receiver_count', 'count')
    ).reset_index()
    
    # Fill NaN std (when only 1 observation)
    link_stats['std_collab_count'] = link_stats['std_collab_count'].fillna(0)
    
    # Compute collaboration probability
    link_stats['collab_probability'] = link_stats['n_instances_with_collab'] / total_instances
    
    # Merge with network GeoDataFrame
    result = network_links_gdf.merge(
        link_stats,
        left_on=link_id_col,
        right_on='link_id',
        how='left',
        suffixes=('', '_stat')
    )
    
    # Drop duplicate link_id column if created
    if 'link_id_stat' in result.columns:
        result = result.drop(columns=['link_id_stat'])
    
    # Fill NaN values for links with no collaborators
    result['mean_collab_count'] = result['mean_collab_count'].fillna(0)
    result['std_collab_count'] = result['std_collab_count'].fillna(0)
    result['total_collab_count'] = result['total_collab_count'].fillna(0).astype(int)
    result['n_instances_with_collab'] = result['n_instances_with_collab'].fillna(0).astype(int)
    result['collab_probability'] = result['collab_probability'].fillna(0)
    
    return result


def network_links_df_to_gdf(
    network_links_df: pd.DataFrame,
    network_nodes_df: pd.DataFrame,
    boundary: Optional[Union[List[float], Tuple[float, float, float, float]]] = None,
    xmin: Optional[float] = None,
    xmax: Optional[float] = None,
    ymin: Optional[float] = None,
    ymax: Optional[float] = None,
    crs: Optional[str] = None
) -> gpd.GeoDataFrame:
    """
    Convert MATSim network DataFrames (links and nodes) to a GeoDataFrame of links.
    
    Alternative to network_graph_to_gdf when you have the raw DataFrames instead of a graph.
    
    Args:
        network_links_df: DataFrame with columns: link_id, from_node, to_node, length, etc.
        network_nodes_df: DataFrame with columns: node_id, x, y.
        boundary: Optional list/tuple of [xmin, ymin, xmax, ymax] defining the bounding box.
        xmin, xmax, ymin, ymax: Individual boundary coordinates (alternative to boundary).
        crs: Coordinate reference system (e.g., 'EPSG:32632'). Optional.
    
    Returns:
        GeoDataFrame of links with LineString geometries.
    
    Examples:
        # Convert network DataFrames to GeoDataFrame
        links_gdf = network_links_df_to_gdf(network_links, network_nodes)
        
        # With bounding box filter
        links_gdf = network_links_df_to_gdf(
            network_links, network_nodes,
            xmin=0, xmax=10000, ymin=0, ymax=18000
        )
    """
    # Parse boundary parameters
    if boundary is not None:
        if len(boundary) != 4:
            raise ValueError("boundary must be a list/tuple of 4 values: [xmin, ymin, xmax, ymax]")
        xmin, ymin, xmax, ymax = boundary
    
    has_boundary = all(v is not None for v in [xmin, xmax, ymin, ymax])
    
    # Create node coordinate lookup
    node_coords = {
        row['node_id']: (float(row['x']), float(row['y']))
        for _, row in network_nodes_df.iterrows()
    }
    
    # Build links GeoDataFrame
    link_records = []
    for _, row in network_links_df.iterrows():
        link_id = row['link_id']
        from_node = row['from_node']
        to_node = row['to_node']
        
        # Get node coordinates
        if from_node not in node_coords or to_node not in node_coords:
            continue
        
        from_x, from_y = node_coords[from_node]
        to_x, to_y = node_coords[to_node]
        
        # Filter by boundary: both endpoints must be within bounds
        if has_boundary:
            from_in = (xmin <= from_x <= xmax and ymin <= from_y <= ymax)
            to_in = (xmin <= to_x <= xmax and ymin <= to_y <= ymax)
            if not (from_in and to_in):
                continue
        
        geometry = LineString([(from_x, from_y), (to_x, to_y)])
        
        # Copy all columns from original row
        record = row.to_dict()
        record['geometry'] = geometry
        link_records.append(record)
    
    links_gdf = gpd.GeoDataFrame(link_records, geometry='geometry')
    if crs:
        links_gdf.set_crs(crs, inplace=True)
    
    return links_gdf


def compute_getis_ord_statistics(
    gdf: gpd.GeoDataFrame,
    col_name: str,
    weights_type: str = 'distance',
    distance_threshold: Optional[float] = None,
    k_neighbors: int = 8,
    binary: bool = False,
    row_standardize: bool = True,
    permutations: int = 999,
    plot: bool = True,
    figsize: Tuple[float, float] = (14, 6),
    cmap: str = 'RdYlBu_r',
    title_prefix: str = '',
    ax: Optional[object] = None,
    return_weights: bool = False
) -> Dict:
    """
    Compute Global and Local Getis-Ord statistics for spatial autocorrelation analysis.
    
    This function calculates:
    - Global Getis-Ord G: Tests for overall spatial clustering of high or low values
    - Local Getis-Ord Gi*: Identifies local hot spots (high value clusters) and cold spots (low value clusters)
    
    Args:
        gdf: GeoDataFrame with geometry and the attribute to analyze.
        col_name: Name of the column to analyze for spatial autocorrelation.
        weights_type: Type of spatial weights. Options:
            - 'distance': Distance-based weights (default)
            - 'knn': K-nearest neighbors weights
            - 'queen': Queen contiguity (for polygons)
            - 'rook': Rook contiguity (for polygons)
        distance_threshold: Distance threshold for distance-based weights. 
            If None, will be estimated as max nearest neighbor distance * 1.5.
        k_neighbors: Number of neighbors for KNN weights (default: 8).
        binary: If True, use binary weights (1/0). If False, use distance decay.
        row_standardize: If True, row-standardize the weights matrix.
        permutations: Number of permutations for significance testing (default: 999).
        plot: If True, create visualization of results.
        figsize: Figure size for the plot (width, height).
        cmap: Colormap for the plot.
        title_prefix: Prefix string for plot titles.
        ax: Optional matplotlib axes for plotting. If None, creates new figure.
        return_weights: If True, also return the spatial weights object.
    
    Returns:
        Dictionary containing:
            - 'global_G': Global Getis-Ord G statistic value
            - 'global_G_z': Z-score for Global G
            - 'global_G_p': P-value for Global G
            - 'global_interpretation': Text interpretation of Global G result
            - 'local_Gi_star': Array of Local Gi* statistics
            - 'local_Gi_star_z': Array of Local Gi* z-scores
            - 'local_Gi_star_p': Array of Local Gi* p-values
            - 'hotspot_count': Number of significant hot spots (z > 1.96)
            - 'coldspot_count': Number of significant cold spots (z < -1.96)
            - 'gdf_with_stats': GeoDataFrame with added Gi* statistics columns
            - 'weights' (optional): Spatial weights object if return_weights=True
    
    Notes:
        - Hot spots (Gi* > 0, significant): Clusters of high values
        - Cold spots (Gi* < 0, significant): Clusters of low values
        - Uses star variant (Gi*) which includes the focal observation
    
    Examples:
        # Basic usage
        result = compute_getis_ord_statistics(mean_collab_links_gdf, 'mean_collab_count')
        
        # With custom distance threshold
        result = compute_getis_ord_statistics(
            gdf, 'mean_collab_count',
            weights_type='distance',
            distance_threshold=1000
        )
        
        # Using KNN weights
        result = compute_getis_ord_statistics(
            gdf, 'mean_collab_count',
            weights_type='knn',
            k_neighbors=10
        )
        
        # Get the GeoDataFrame with hot/cold spot labels
        gdf_with_hotspots = result['gdf_with_stats']
    """
    from libpysal.weights import DistanceBand, KNN, Queen, Rook
    from esda.getisord import G, G_Local
    import matplotlib.pyplot as plt
    from matplotlib.colors import TwoSlopeNorm
    
    # Make a copy to avoid modifying original
    gdf = gdf.copy()
    
    # Check for valid data
    if col_name not in gdf.columns:
        raise ValueError(f"Column '{col_name}' not found in GeoDataFrame")
    
    # Remove rows with NaN values in the target column
    valid_mask = gdf[col_name].notna()
    if not valid_mask.all():
        print(f"Warning: Removing {(~valid_mask).sum()} rows with NaN values in '{col_name}'")
        gdf = gdf[valid_mask].reset_index(drop=True)
    
    if len(gdf) < 3:
        raise ValueError("Need at least 3 observations for Getis-Ord analysis")
    
    # Get centroid coordinates for distance calculations (works for both points and lines)
    centroids = gdf.geometry.centroid
    coords = np.column_stack([centroids.x, centroids.y])
    
    # Create spatial weights
    if weights_type == 'distance':
        if distance_threshold is None:
            # Estimate threshold from data: max nearest neighbor distance * 1.5
            from scipy.spatial import cKDTree
            tree = cKDTree(coords)
            nn_dists, _ = tree.query(coords, k=2)
            distance_threshold = nn_dists[:, 1].max() * 1.5
            print(f"Auto-determined distance threshold: {distance_threshold:.2f}")
        
        w = DistanceBand.from_array(coords, threshold=distance_threshold, binary=binary)
    
    elif weights_type == 'knn':
        w = KNN.from_array(coords, k=k_neighbors)
    
    elif weights_type == 'queen':
        w = Queen.from_dataframe(gdf)
    
    elif weights_type == 'rook':
        w = Rook.from_dataframe(gdf)
    
    else:
        raise ValueError(f"Unknown weights_type: {weights_type}. Choose from 'distance', 'knn', 'queen', 'rook'")
    
    # Row standardize if requested
    if row_standardize:
        w.transform = 'r'
    
    # Get the attribute values
    y = gdf[col_name].values.astype(float)
    
    # Check for islands (observations with no neighbors)
    if w.n != len(y):
        raise ValueError(f"Weights matrix size ({w.n}) doesn't match data size ({len(y)})")
    
    islands = [i for i in range(len(y)) if len(w.neighbors[i]) == 0]
    if islands:
        print(f"Warning: {len(islands)} observations have no neighbors (islands)")
    
    # ----- Compute Global Getis-Ord G -----
    try:
        global_g = G(y, w, permutations=permutations)
        global_G = global_g.G
        global_G_z = global_g.z_norm
        global_G_p = global_g.p_norm
        
        # Interpretation
        if global_G_p < 0.05:
            if global_G_z > 0:
                global_interpretation = "Significant clustering of HIGH values (hot spot clustering)"
            else:
                global_interpretation = "Significant clustering of LOW values (cold spot clustering)"
        else:
            global_interpretation = "No significant global spatial clustering"
    except Exception as e:
        print(f"Warning: Global G computation failed: {e}")
        global_G = np.nan
        global_G_z = np.nan
        global_G_p = np.nan
        global_interpretation = "Computation failed"
    
    # ----- Compute Local Getis-Ord Gi* -----
    try:
        local_g = G_Local(y, w, star=True, permutations=permutations)
        local_Gi_star = local_g.Gs
        local_Gi_star_z = local_g.Zs
        local_Gi_star_p = local_g.p_sim
    except Exception as e:
        print(f"Warning: Local Gi* computation failed: {e}")
        local_Gi_star = np.full(len(y), np.nan)
        local_Gi_star_z = np.full(len(y), np.nan)
        local_Gi_star_p = np.full(len(y), np.nan)
    
    # Add results to GeoDataFrame
    gdf['Gi_star'] = local_Gi_star
    gdf['Gi_star_z'] = local_Gi_star_z
    gdf['Gi_star_p'] = local_Gi_star_p
    
    # Classify hot spots and cold spots
    alpha = 0.05
    z_threshold = 1.96  # For 95% confidence
    
    def classify_spot(z, p):
        if pd.isna(z) or pd.isna(p):
            return 'Not Significant'
        if p < alpha:
            if z > z_threshold:
                return 'Hot Spot (99%)' if z > 2.58 else 'Hot Spot (95%)'
            elif z < -z_threshold:
                return 'Cold Spot (99%)' if z < -2.58 else 'Cold Spot (95%)'
        return 'Not Significant'
    
    gdf['spot_type'] = [classify_spot(z, p) for z, p in zip(local_Gi_star_z, local_Gi_star_p)]
    
    # Count hot spots and cold spots
    hotspot_count = sum(1 for z in local_Gi_star_z if z > z_threshold)
    coldspot_count = sum(1 for z in local_Gi_star_z if z < -z_threshold)
    
    # ----- Visualization -----
    if plot:
        if ax is None:
            fig, axes = plt.subplots(1, 2, figsize=figsize)
        else:
            axes = [ax, ax]  # Use same axis twice if single axis provided
        
        # Plot 1: Local Gi* z-scores
        ax1 = axes[0]
        
        # Create diverging colormap centered at 0
        vmin, vmax = local_Gi_star_z.min(), local_Gi_star_z.max()
        if np.isnan(vmin) or np.isnan(vmax):
            vmin, vmax = -3, 3
        
        # Ensure symmetric color range
        abs_max = max(abs(vmin), abs(vmax))
        if abs_max == 0:
            abs_max = 1
        
        try:
            norm = TwoSlopeNorm(vmin=-abs_max, vcenter=0, vmax=abs_max)
        except:
            norm = None
        
        gdf.plot(
            column='Gi_star_z',
            cmap=cmap,
            norm=norm,
            legend=True,
            ax=ax1,
            linewidth=1.5,
            legend_kwds={'label': 'Gi* z-score', 'shrink': 0.8}
        )
        ax1.set_title(f'{title_prefix}Local Getis-Ord Gi* (z-scores)')
        ax1.set_xlabel('X')
        ax1.set_ylabel('Y')
        
        # Plot 2: Hot spot / Cold spot classification
        ax2 = axes[1]
        
        # Define colors for spot types
        spot_colors = {
            'Hot Spot (99%)': '#d7191c',
            'Hot Spot (95%)': '#fdae61',
            'Not Significant': '#ffffbf',
            'Cold Spot (95%)': '#abd9e9',
            'Cold Spot (99%)': '#2c7bb6'
        }
        
        # Plot each category
        for spot_type, color in spot_colors.items():
            subset = gdf[gdf['spot_type'] == spot_type]
            if len(subset) > 0:
                subset.plot(ax=ax2, color=color, label=spot_type, linewidth=1.5)
        
        ax2.legend(loc='upper right', fontsize=8)
        ax2.set_title(f'{title_prefix}Hot Spots & Cold Spots')
        ax2.set_xlabel('X')
        ax2.set_ylabel('Y')
        
        plt.tight_layout()
        
        if ax is None:
            plt.show()
    
    # Build result dictionary
    result = {
        'global_G': global_G,
        'global_G_z': global_G_z,
        'global_G_p': global_G_p,
        'global_interpretation': global_interpretation,
        'local_Gi_star': local_Gi_star,
        'local_Gi_star_z': local_Gi_star_z,
        'local_Gi_star_p': local_Gi_star_p,
        'hotspot_count': hotspot_count,
        'coldspot_count': coldspot_count,
        'n_observations': len(gdf),
        'gdf_with_stats': gdf
    }
    
    if return_weights:
        result['weights'] = w
    
    # Print summary
    print(f"\n{'='*60}")
    print(f"Getis-Ord Statistics Summary")
    print(f"{'='*60}")
    print(f"Column analyzed: {col_name}")
    print(f"Number of observations: {len(gdf)}")
    print(f"Weights type: {weights_type}")
    if weights_type == 'distance':
        print(f"Distance threshold: {distance_threshold:.2f}")
    elif weights_type == 'knn':
        print(f"K neighbors: {k_neighbors}")
    print(f"\nGlobal Getis-Ord G:")
    print(f"  G statistic: {global_G:.6f}")
    print(f"  Z-score: {global_G_z:.4f}")
    print(f"  P-value: {global_G_p:.4f}")
    print(f"  Interpretation: {global_interpretation}")
    print(f"\nLocal Getis-Ord Gi*:")
    print(f"  Hot spots (z > 1.96): {hotspot_count}")
    print(f"  Cold spots (z < -1.96): {coldspot_count}")
    print(f"{'='*60}\n")
    
    return result


def plot_hotspot_map(
    gdf: gpd.GeoDataFrame,
    col_name: str = 'Gi_star_z',
    figsize: Tuple[float, float] = (12, 8),
    cmap: str = 'RdYlBu_r',
    title: str = 'Hot Spot Analysis',
    show_legend: bool = True,
    alpha: float = 0.8,
    linewidth: float = 1.5,
    ax: Optional[object] = None
) -> object:
    """
    Plot a hot spot map from GeoDataFrame with Getis-Ord Gi* statistics.
    
    Use this function to create custom visualizations of Gi* results 
    after running compute_getis_ord_statistics.
    
    Args:
        gdf: GeoDataFrame with Gi* statistics (from compute_getis_ord_statistics).
        col_name: Column to plot. Default 'Gi_star_z' for z-scores.
            Can also use 'spot_type' for categorical hot/cold spot classification.
        figsize: Figure size (width, height).
        cmap: Colormap for continuous data.
        title: Plot title.
        show_legend: Whether to show legend.
        alpha: Transparency level.
        linewidth: Width of lines/borders.
        ax: Optional matplotlib axes.
    
    Returns:
        Matplotlib axes object.
    
    Examples:
        # Plot z-scores
        ax = plot_hotspot_map(result_gdf, col_name='Gi_star_z', title='Gi* Z-scores')
        
        # Plot categorical spot types
        ax = plot_hotspot_map(result_gdf, col_name='spot_type', title='Hot/Cold Spots')
    """
    import matplotlib.pyplot as plt
    from matplotlib.colors import TwoSlopeNorm
    
    if ax is None:
        fig, ax = plt.subplots(1, 1, figsize=figsize)
    
    if col_name == 'spot_type':
        # Categorical plot
        spot_colors = {
            'Hot Spot (99%)': '#d7191c',
            'Hot Spot (95%)': '#fdae61',
            'Not Significant': '#ffffbf',
            'Cold Spot (95%)': '#abd9e9',
            'Cold Spot (99%)': '#2c7bb6'
        }
        
        for spot_type, color in spot_colors.items():
            subset = gdf[gdf['spot_type'] == spot_type]
            if len(subset) > 0:
                subset.plot(ax=ax, color=color, label=spot_type, 
                           alpha=alpha, linewidth=linewidth)
        
        if show_legend:
            ax.legend(loc='upper right', fontsize=9)
    
    else:
        # Continuous plot with diverging colormap
        values = gdf[col_name].values
        vmin, vmax = np.nanmin(values), np.nanmax(values)
        
        if not (np.isnan(vmin) or np.isnan(vmax)):
            abs_max = max(abs(vmin), abs(vmax))
            if abs_max > 0:
                try:
                    norm = TwoSlopeNorm(vmin=-abs_max, vcenter=0, vmax=abs_max)
                except:
                    norm = None
            else:
                norm = None
        else:
            norm = None
        
        gdf.plot(
            column=col_name,
            cmap=cmap,
            norm=norm,
            legend=show_legend,
            ax=ax,
            alpha=alpha,
            linewidth=linewidth,
            legend_kwds={'label': col_name, 'shrink': 0.8}
        )
    
    ax.set_title(title)
    ax.set_xlabel('X')
    ax.set_ylabel('Y')
    
    return ax


def compute_moran_statistics(
    gdf: gpd.GeoDataFrame,
    col_name: str,
    weights_type: str = 'distance',
    distance_threshold: Optional[float] = None,
    k_neighbors: int = 8,
    binary: bool = False,
    row_standardize: bool = True,
    permutations: int = 999,
    plot: bool = True,
    figsize: Tuple[float, float] = (14, 10),
    cmap: str = 'RdYlBu_r',
    title_prefix: str = '',
    ax: Optional[object] = None,
    return_weights: bool = False
) -> Dict:
    """
    Compute Global and Local Moran's I statistics for spatial autocorrelation analysis.
    
    This function calculates:
    - Global Moran's I: Tests for overall spatial autocorrelation (clustering of similar values)
    - Local Moran's I (LISA): Identifies local clusters and spatial outliers
    
    Moran's I differs from Getis-Ord G:
    - Moran's I detects clustering of SIMILAR values (high-high OR low-low)
    - Getis-Ord G detects clustering of HIGH or LOW values specifically
    - Moran's I also identifies spatial outliers (high-low, low-high)
    
    Args:
        gdf: GeoDataFrame with geometry and the attribute to analyze.
        col_name: Name of the column to analyze for spatial autocorrelation.
        weights_type: Type of spatial weights. Options:
            - 'distance': Distance-based weights (default)
            - 'knn': K-nearest neighbors weights
            - 'queen': Queen contiguity (for polygons)
            - 'rook': Rook contiguity (for polygons)
        distance_threshold: Distance threshold for distance-based weights. 
            If None, will be estimated as max nearest neighbor distance * 1.5.
        k_neighbors: Number of neighbors for KNN weights (default: 8).
        binary: If True, use binary weights (1/0). If False, use distance decay.
        row_standardize: If True, row-standardize the weights matrix.
        permutations: Number of permutations for significance testing (default: 999).
        plot: If True, create visualization of results (includes Moran scatterplot and LISA map).
        figsize: Figure size for the plot (width, height).
        cmap: Colormap for the plot.
        title_prefix: Prefix string for plot titles.
        ax: Optional matplotlib axes for plotting. If None, creates new figure.
        return_weights: If True, also return the spatial weights object.
    
    Returns:
        Dictionary containing:
            - 'global_I': Global Moran's I statistic value
            - 'global_I_expected': Expected value under null hypothesis
            - 'global_I_z': Z-score for Global I
            - 'global_I_p': P-value for Global I (permutation-based)
            - 'global_interpretation': Text interpretation of Global I result
            - 'local_I': Array of Local Moran's I statistics
            - 'local_I_z': Array of Local I z-scores (standardized)
            - 'local_I_p': Array of Local I p-values
            - 'local_I_q': Array of quadrant assignments (1=HH, 2=LH, 3=LL, 4=HL)
            - 'HH_count': Number of significant High-High clusters
            - 'LL_count': Number of significant Low-Low clusters
            - 'HL_count': Number of significant High-Low outliers
            - 'LH_count': Number of significant Low-High outliers
            - 'gdf_with_stats': GeoDataFrame with added LISA statistics columns
            - 'weights' (optional): Spatial weights object if return_weights=True
    
    Notes:
        LISA Cluster Types:
        - HH (High-High): High value surrounded by high values (cluster)
        - LL (Low-Low): Low value surrounded by low values (cluster)
        - HL (High-Low): High value surrounded by low values (outlier)
        - LH (Low-High): Low value surrounded by high values (outlier)
        - NS: Not Significant
    
    Examples:
        # Basic usage
        result = compute_moran_statistics(mean_collab_links_gdf, 'mean_collab_count')
        
        # With custom distance threshold
        result = compute_moran_statistics(
            gdf, 'mean_collab_count',
            weights_type='distance',
            distance_threshold=1000
        )
        
        # Using KNN weights
        result = compute_moran_statistics(
            gdf, 'mean_collab_count',
            weights_type='knn',
            k_neighbors=10
        )
        
        # Get the GeoDataFrame with LISA cluster labels
        gdf_with_lisa = result['gdf_with_stats']
    """
    from libpysal.weights import DistanceBand, KNN, Queen, Rook
    from esda.moran import Moran, Moran_Local
    import matplotlib.pyplot as plt
    from matplotlib.colors import TwoSlopeNorm
    
    # Make a copy to avoid modifying original
    gdf = gdf.copy()
    
    # Check for valid data
    if col_name not in gdf.columns:
        raise ValueError(f"Column '{col_name}' not found in GeoDataFrame")
    
    # Remove rows with NaN values in the target column
    valid_mask = gdf[col_name].notna()
    if not valid_mask.all():
        print(f"Warning: Removing {(~valid_mask).sum()} rows with NaN values in '{col_name}'")
        gdf = gdf[valid_mask].reset_index(drop=True)
    
    if len(gdf) < 3:
        raise ValueError("Need at least 3 observations for Moran's I analysis")
    
    # Get centroid coordinates for distance calculations (works for both points and lines)
    centroids = gdf.geometry.centroid
    coords = np.column_stack([centroids.x, centroids.y])
    
    # Create spatial weights
    if weights_type == 'distance':
        if distance_threshold is None:
            # Estimate threshold from data: max nearest neighbor distance * 1.5
            from scipy.spatial import cKDTree
            tree = cKDTree(coords)
            nn_dists, _ = tree.query(coords, k=2)
            distance_threshold = nn_dists[:, 1].max() * 1.5
            print(f"Auto-determined distance threshold: {distance_threshold:.2f}")
        
        w = DistanceBand.from_array(coords, threshold=distance_threshold, binary=binary)
    
    elif weights_type == 'knn':
        w = KNN.from_array(coords, k=k_neighbors)
    
    elif weights_type == 'queen':
        w = Queen.from_dataframe(gdf)
    
    elif weights_type == 'rook':
        w = Rook.from_dataframe(gdf)
    
    else:
        raise ValueError(f"Unknown weights_type: {weights_type}. Choose from 'distance', 'knn', 'queen', 'rook'")
    
    # Row standardize if requested
    if row_standardize:
        w.transform = 'r'
    
    # Get the attribute values
    y = gdf[col_name].values.astype(float)
    
    # Check for islands (observations with no neighbors)
    islands = [i for i in range(len(y)) if len(w.neighbors[i]) == 0]
    if islands:
        print(f"Warning: {len(islands)} observations have no neighbors (islands)")
    
    # ----- Compute Global Moran's I -----
    try:
        global_moran = Moran(y, w, permutations=permutations)
        global_I = global_moran.I
        global_I_expected = global_moran.EI
        global_I_z = global_moran.z_sim
        global_I_p = global_moran.p_sim
        
        # Interpretation
        if global_I_p < 0.05:
            if global_I > global_I_expected:
                global_interpretation = "Significant POSITIVE spatial autocorrelation (clustering of similar values)"
            else:
                global_interpretation = "Significant NEGATIVE spatial autocorrelation (dispersion/checkerboard pattern)"
        else:
            global_interpretation = "No significant spatial autocorrelation (random pattern)"
    except Exception as e:
        print(f"Warning: Global Moran's I computation failed: {e}")
        global_I = np.nan
        global_I_expected = np.nan
        global_I_z = np.nan
        global_I_p = np.nan
        global_interpretation = "Computation failed"
    
    # ----- Compute Local Moran's I (LISA) -----
    try:
        local_moran = Moran_Local(y, w, permutations=permutations)
        local_I = local_moran.Is
        local_I_z = local_moran.z_sim
        local_I_p = local_moran.p_sim
        local_I_q = local_moran.q  # Quadrant (1=HH, 2=LH, 3=LL, 4=HL)
    except Exception as e:
        print(f"Warning: Local Moran's I computation failed: {e}")
        local_I = np.full(len(y), np.nan)
        local_I_z = np.full(len(y), np.nan)
        local_I_p = np.full(len(y), np.nan)
        local_I_q = np.full(len(y), np.nan)
    
    # Add results to GeoDataFrame
    gdf['local_I'] = local_I
    gdf['local_I_z'] = local_I_z
    gdf['local_I_p'] = local_I_p
    gdf['local_I_q'] = local_I_q
    
    # Classify LISA clusters
    alpha = 0.05
    
    def classify_lisa(q, p):
        if pd.isna(q) or pd.isna(p):
            return 'Not Significant'
        if p >= alpha:
            return 'Not Significant'
        q = int(q)
        if q == 1:
            return 'High-High'
        elif q == 2:
            return 'Low-High'
        elif q == 3:
            return 'Low-Low'
        elif q == 4:
            return 'High-Low'
        return 'Not Significant'
    
    gdf['lisa_cluster'] = [classify_lisa(q, p) for q, p in zip(local_I_q, local_I_p)]
    
    # Count cluster types
    significant_mask = local_I_p < alpha
    HH_count = sum((local_I_q == 1) & significant_mask)
    LH_count = sum((local_I_q == 2) & significant_mask)
    LL_count = sum((local_I_q == 3) & significant_mask)
    HL_count = sum((local_I_q == 4) & significant_mask)
    
    # ----- Visualization -----
    if plot:
        fig, axes = plt.subplots(2, 2, figsize=figsize)
        
        # Plot 1: Moran Scatterplot
        ax1 = axes[0, 0]
        
        # Standardize y for scatterplot
        y_std = (y - y.mean()) / y.std()
        # Compute spatial lag
        y_lag = np.zeros(len(y))
        for i in range(len(y)):
            neighbors = list(w.neighbors[i])
            weights = list(w.weights[i])
            if neighbors:
                y_lag[i] = sum(w * y_std[n] for n, w in zip(neighbors, weights))
        
        # Color by quadrant
        colors = []
        for q, p in zip(local_I_q, local_I_p):
            if p >= alpha or pd.isna(q):
                colors.append('#cccccc')  # Not significant
            elif q == 1:
                colors.append('#d7191c')  # HH - red
            elif q == 2:
                colors.append('#abd9e9')  # LH - light blue
            elif q == 3:
                colors.append('#2c7bb6')  # LL - blue
            elif q == 4:
                colors.append('#fdae61')  # HL - orange
            else:
                colors.append('#cccccc')
        
        ax1.scatter(y_std, y_lag, c=colors, alpha=0.7, edgecolors='white', linewidth=0.5)
        ax1.axhline(0, color='black', linewidth=0.5)
        ax1.axvline(0, color='black', linewidth=0.5)
        
        # Add regression line
        slope = global_I
        x_line = np.linspace(y_std.min(), y_std.max(), 100)
        ax1.plot(x_line, slope * x_line, 'r--', linewidth=2, 
                label=f"Moran's I = {global_I:.3f}")
        
        ax1.set_xlabel(f'{col_name} (standardized)')
        ax1.set_ylabel(f'Spatial Lag of {col_name}')
        ax1.set_title(f'{title_prefix}Moran Scatterplot')
        ax1.legend(loc='upper left')
        
        # Add quadrant labels
        ax1.text(0.95, 0.95, 'HH', transform=ax1.transAxes, fontsize=12, 
                fontweight='bold', va='top', ha='right', color='#d7191c')
        ax1.text(0.05, 0.95, 'LH', transform=ax1.transAxes, fontsize=12, 
                fontweight='bold', va='top', ha='left', color='#abd9e9')
        ax1.text(0.05, 0.05, 'LL', transform=ax1.transAxes, fontsize=12, 
                fontweight='bold', va='bottom', ha='left', color='#2c7bb6')
        ax1.text(0.95, 0.05, 'HL', transform=ax1.transAxes, fontsize=12, 
                fontweight='bold', va='bottom', ha='right', color='#fdae61')
        
        # Plot 2: Local I values map
        ax2 = axes[0, 1]
        
        vmin, vmax = np.nanmin(local_I), np.nanmax(local_I)
        if not (np.isnan(vmin) or np.isnan(vmax)):
            abs_max = max(abs(vmin), abs(vmax))
            if abs_max > 0:
                try:
                    norm = TwoSlopeNorm(vmin=-abs_max, vcenter=0, vmax=abs_max)
                except:
                    norm = None
            else:
                norm = None
        else:
            norm = None
        
        gdf.plot(
            column='local_I',
            cmap=cmap,
            norm=norm,
            legend=True,
            ax=ax2,
            linewidth=1.5,
            legend_kwds={'label': 'Local Moran I', 'shrink': 0.8}
        )
        ax2.set_title(f'{title_prefix}Local Moran\'s I Values')
        ax2.set_xlabel('X')
        ax2.set_ylabel('Y')
        
        # Plot 3: LISA Cluster Map
        ax3 = axes[1, 0]
        
        # Define colors for LISA clusters
        lisa_colors = {
            'High-High': '#d7191c',
            'Low-High': '#abd9e9',
            'Low-Low': '#2c7bb6',
            'High-Low': '#fdae61',
            'Not Significant': '#cccccc'
        }
        
        for cluster_type, color in lisa_colors.items():
            subset = gdf[gdf['lisa_cluster'] == cluster_type]
            if len(subset) > 0:
                subset.plot(ax=ax3, color=color, label=cluster_type, linewidth=1.5)
        
        ax3.legend(loc='upper right', fontsize=8)
        ax3.set_title(f'{title_prefix}LISA Cluster Map')
        ax3.set_xlabel('X')
        ax3.set_ylabel('Y')
        
        # Plot 4: Significance map (p-values)
        ax4 = axes[1, 1]
        
        # Create significance categories
        def p_to_sig(p):
            if pd.isna(p):
                return 'N/A'
            elif p < 0.01:
                return 'p < 0.01'
            elif p < 0.05:
                return 'p < 0.05'
            elif p < 0.1:
                return 'p < 0.10'
            else:
                return 'Not Significant'
        
        gdf['significance'] = [p_to_sig(p) for p in local_I_p]
        
        sig_colors = {
            'p < 0.01': '#1a9641',
            'p < 0.05': '#a6d96a',
            'p < 0.10': '#ffffbf',
            'Not Significant': '#d7191c',
            'N/A': '#cccccc'
        }
        
        for sig_type, color in sig_colors.items():
            subset = gdf[gdf['significance'] == sig_type]
            if len(subset) > 0:
                subset.plot(ax=ax4, color=color, label=sig_type, linewidth=1.5)
        
        ax4.legend(loc='upper right', fontsize=8)
        ax4.set_title(f'{title_prefix}Significance Map (p-values)')
        ax4.set_xlabel('X')
        ax4.set_ylabel('Y')
        
        plt.tight_layout()
        plt.show()
    
    # Build result dictionary
    result = {
        'global_I': global_I,
        'global_I_expected': global_I_expected,
        'global_I_z': global_I_z,
        'global_I_p': global_I_p,
        'global_interpretation': global_interpretation,
        'local_I': local_I,
        'local_I_z': local_I_z,
        'local_I_p': local_I_p,
        'local_I_q': local_I_q,
        'HH_count': HH_count,
        'LL_count': LL_count,
        'HL_count': HL_count,
        'LH_count': LH_count,
        'n_observations': len(gdf),
        'gdf_with_stats': gdf
    }
    
    if return_weights:
        result['weights'] = w
    
    # Print summary
    print(f"\n{'='*60}")
    print(f"Moran's I Statistics Summary")
    print(f"{'='*60}")
    print(f"Column analyzed: {col_name}")
    print(f"Number of observations: {len(gdf)}")
    print(f"Weights type: {weights_type}")
    if weights_type == 'distance':
        print(f"Distance threshold: {distance_threshold:.2f}")
    elif weights_type == 'knn':
        print(f"K neighbors: {k_neighbors}")
    print(f"\nGlobal Moran's I:")
    print(f"  I statistic: {global_I:.6f}")
    print(f"  Expected I: {global_I_expected:.6f}")
    print(f"  Z-score: {global_I_z:.4f}")
    print(f"  P-value: {global_I_p:.4f}")
    print(f"  Interpretation: {global_interpretation}")
    print(f"\nLocal Moran's I (LISA) - Significant clusters:")
    print(f"  High-High (HH): {HH_count}")
    print(f"  Low-Low (LL): {LL_count}")
    print(f"  High-Low (HL): {HL_count}")
    print(f"  Low-High (LH): {LH_count}")
    print(f"  Not Significant: {len(gdf) - HH_count - LL_count - HL_count - LH_count}")
    print(f"{'='*60}\n")
    
    return result


def plot_lisa_map(
    gdf: gpd.GeoDataFrame,
    col_name: str = 'lisa_cluster',
    figsize: Tuple[float, float] = (12, 8),
    title: str = 'LISA Cluster Map',
    show_legend: bool = True,
    alpha: float = 0.8,
    linewidth: float = 1.5,
    ax: Optional[object] = None
) -> object:
    """
    Plot a LISA cluster map from GeoDataFrame with Moran's I statistics.
    
    Use this function to create custom visualizations of LISA results 
    after running compute_moran_statistics.
    
    Args:
        gdf: GeoDataFrame with LISA statistics (from compute_moran_statistics).
        col_name: Column to plot. Default 'lisa_cluster' for categorical clusters.
            Can also use 'local_I' for continuous Local Moran I values.
        figsize: Figure size (width, height).
        title: Plot title.
        show_legend: Whether to show legend.
        alpha: Transparency level.
        linewidth: Width of lines/borders.
        ax: Optional matplotlib axes.
    
    Returns:
        Matplotlib axes object.
    
    Examples:
        # Plot LISA clusters
        ax = plot_lisa_map(result_gdf, col_name='lisa_cluster', title='LISA Clusters')
        
        # Plot local I values
        ax = plot_lisa_map(result_gdf, col_name='local_I', title='Local Moran I')
    """
    import matplotlib.pyplot as plt
    from matplotlib.colors import TwoSlopeNorm
    
    if ax is None:
        fig, ax = plt.subplots(1, 1, figsize=figsize)
    
    if col_name == 'lisa_cluster':
        # Categorical plot
        lisa_colors = {
            'High-High': '#d7191c',
            'Low-High': '#abd9e9',
            'Low-Low': '#2c7bb6',
            'High-Low': '#fdae61',
            'Not Significant': '#cccccc'
        }
        
        for cluster_type, color in lisa_colors.items():
            subset = gdf[gdf['lisa_cluster'] == cluster_type]
            if len(subset) > 0:
                subset.plot(ax=ax, color=color, label=cluster_type, 
                           alpha=alpha, linewidth=linewidth)
        
        if show_legend:
            ax.legend(loc='upper right', fontsize=9)
    
    else:
        # Continuous plot with diverging colormap
        values = gdf[col_name].values
        vmin, vmax = np.nanmin(values), np.nanmax(values)
        
        if not (np.isnan(vmin) or np.isnan(vmax)):
            abs_max = max(abs(vmin), abs(vmax))
            if abs_max > 0:
                try:
                    norm = TwoSlopeNorm(vmin=-abs_max, vcenter=0, vmax=abs_max)
                except:
                    norm = None
            else:
                norm = None
        else:
            norm = None
        
        gdf.plot(
            column=col_name,
            cmap='RdYlBu_r',
            norm=norm,
            legend=show_legend,
            ax=ax,
            alpha=alpha,
            linewidth=linewidth,
            legend_kwds={'label': col_name, 'shrink': 0.8}
        )
    
    ax.set_title(title)
    ax.set_xlabel('X')
    ax.set_ylabel('Y')
    
    return ax
