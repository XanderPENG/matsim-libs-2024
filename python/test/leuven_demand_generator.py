"""
Leuven Freight Demand Generator
================================

A single-file pipeline that produces *customer* and *depot* demand inputs for
the Leuven urban-freight MATSim experiments.

Pipeline overview
-----------------

    +----------------+   +------------------+   +-----------------------+
    | FSQ retail POI |   | Leuven Ring Area |   | Car network (cropped) |
    +----------------+   +------------------+   +-----------------------+
            |                     |                          |
            +-----+---------------+                          |
                  v                                          v
     +---------------------------+        +-----------------------------+
     | sample_customers_with_    |        | generate_all_ring_sector_   |
     | _target_nni(...)          |        | depots(...)                 |
     +---------------------------+        +-----------------------------+
            |                                          |
            v                                          v
    match_points_to_links(customers, cropped_net)   (depots already
            |                                        snapped inside)
            v                                          v
       customers_matched_gdf               depots_gdf (40 candidates)
            \\___________________  _____________________/
                                \\/
                +-----------------------------------+
                | assign_customers_to_depots(...)   |
                |   - roundrobin (default)          |
                |   - nearest_balanced (Hungarian)  |
                +-----------------------------------+
                                |
                                v
                +-----------------------------------+
                | save_demand_csv  (per instance)   |
                | save_depots_csv  (per instance)   |
                | plot_instance    (per instance)   |
                +-----------------------------------+

Two-call workflow (per the plan, §4.6)
--------------------------------------

The master orchestrator ``run_instance`` is designed to be called twice:

1. **First call** (``depot_link_ids=None``):
   - Generates and writes the full 40-depot sidecar (10 rings × 4 sectors).
   - Prints the table so the user can pick a subset.
   - **Does NOT** sample customers or write a demand CSV.

2. **Second call** (``depot_link_ids=[...]``):
   - Reuses the same ``seed_depots`` so depot positions are identical.
   - Samples customers under the requested target NNI.
   - Snaps customers to the cropped network, assigns them evenly to the
     chosen depots, and writes the demand CSV.

Angle convention
----------------

Sectors are specified in **COMPASS bearings** (0° = North, clockwise positive).
Internally, conversions go through ``_compass_xy`` and
``_compass_range_to_math_range`` so the rest of the module never has to
juggle conventions.

Output schema (customer demand CSV)
-----------------------------------

Matches ``data/randomDemand60Receivers/inside_clustered/demand5.csv.gz``:

    Unnamed: 0, place_id, matched_link_id, depot_linkId
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Standard library
# ---------------------------------------------------------------------------
import logging
import os
import pathlib
from dataclasses import dataclass
from typing import Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Third-party
# ---------------------------------------------------------------------------
import geopandas as gpd
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy.optimize import linear_sum_assignment
from shapely.geometry import LineString, MultiLineString, Point, Polygon
from shapely.ops import nearest_points
from shapely.strtree import STRtree
from sklearn.neighbors import NearestNeighbors
from tqdm.auto import tqdm   # `auto` = notebook widget in Jupyter, text bar in terminals

# ---------------------------------------------------------------------------
# Module-level logger
# ---------------------------------------------------------------------------
logger = logging.getLogger(__name__)
if not logger.handlers:  # avoid duplicate handlers when re-imported in notebooks
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s"))
    logger.addHandler(_h)
    logger.setLevel(logging.INFO)

# ---------------------------------------------------------------------------
# Constants / default paths (relative to the project root)
# ---------------------------------------------------------------------------
DEFAULT_POI_PATH = "data/leuven_poi/data/poi-fsq-point.gpkg"
DEFAULT_NETWORK_XML_PATH = "data/GemeenteLeuvenWithHbefaType/GemeenteLeuvenWithHbefaType.xml.gz"
DEFAULT_CROPPED_NETWORK_PATH = "data/GemeenteLeuvenWithHbefaType/car_network_cropped.geojson"
DEFAULT_BOUNDARY_PATH = "data/CityLeuvenBoundary.geojson"

# Default cropped-network bounding box (xmin, xmax, ymin, ymax) in EPSG:31370.
# Lifted from the original `leuven_poi.ipynb` notebook.
DEFAULT_BBOX = (171161.734, 175265.856, 172412.828, 176444.093)

DEFAULT_CRS = "EPSG:31370"

# Default sector specification — COMPASS bearings (0° = North, CW positive).
DEFAULT_SECTORS_DEG: Tuple[Tuple[float, float], ...] = (
    (30.0, 60.0),    # NE
    (120.0, 150.0),  # SE
    (210.0, 240.0),  # SW
    (300.0, 330.0),  # NW
)
DEFAULT_SECTOR_NAMES: Tuple[str, ...] = ("NE", "SE", "SW", "NW")

__all__ = [
    # Data loading
    "load_leuven_ring_area",
    "load_cropped_network",
    "load_retail_pois_in_ring",
    # NNI
    "calculate_nni",
    "estimate_nni_range",
    # Customer & depot generation
    "sample_customers_with_target_nni",
    "generate_all_ring_sector_depots",
    # Matching & assignment
    "match_points_to_links",
    "assign_customers_to_depots",
    # Output
    "save_demand_csv",
    "save_depots_csv",
    # Plot & orchestrator
    "plot_instance",
    "run_instance",
    "run_instances",
]


# ===========================================================================
# 1. Geometry helpers (compass-angle utilities & polygon construction)
# ===========================================================================

def _compass_xy(center: Tuple[float, float], r: float, theta_compass_deg: float) -> Tuple[float, float]:
    """Convert ``(center, radius, compass-angle)`` to ``(x, y)`` in EPSG:31370.

    Compass convention: 0° = North (+y), clockwise positive. Therefore a unit
    vector at compass angle θ has components ``(sin θ, cos θ)`` in
    (east, north) coordinates.

    Parameters
    ----------
    center : (float, float)
        Origin in EPSG:31370 (east, north) metres.
    r : float
        Radius in metres.
    theta_compass_deg : float
        Compass bearing in degrees.

    Returns
    -------
    (float, float)
        Cartesian point in EPSG:31370.
    """
    theta_rad = np.deg2rad(theta_compass_deg)
    return (center[0] + r * np.sin(theta_rad),
            center[1] + r * np.cos(theta_rad))


def _compass_range_to_math_range(c1: float, c2: float) -> Tuple[float, float]:
    """Convert a compass angular range ``[c1, c2]`` to the matplotlib (math)
    angular range ``[m1, m2]`` suitable for ``matplotlib.patches.Wedge``.

    Math convention: 0° = +x (east), CCW positive. The two conventions are
    related by ``math_angle = (90 - compass_angle) mod 360``.

    For the four sectors used in this project (each spans 30° and lies
    strictly inside one cardinal quadrant), the returned ``(m1, m2)`` always
    satisfies ``m1 < m2``; matplotlib's Wedge will draw the correct CCW arc.

    Examples
    --------
    >>> _compass_range_to_math_range(30, 60)
    (30.0, 60.0)
    >>> _compass_range_to_math_range(120, 150)
    (300.0, 330.0)
    """
    m1 = (90.0 - c2) % 360.0
    m2 = (90.0 - c1) % 360.0
    return float(m1), float(m2)


def _annular_sector_polygon(
    center: Tuple[float, float],
    r_inner: float,
    r_outer: float,
    theta1_compass_deg: float,
    theta2_compass_deg: float,
    n_arc: int = 64,
) -> Polygon:
    """Build a shapely Polygon for the annular sector defined in COMPASS angles.

    The polygon is constructed by walking the outer arc from ``theta1`` to
    ``theta2`` (clockwise in compass = increasing θ), then walking the inner
    arc back in reverse, then closing the ring.

    Parameters
    ----------
    center : (float, float)
        Centre of the rings in EPSG:31370.
    r_inner, r_outer : float
        Inner and outer radii (metres). Must satisfy ``0 <= r_inner < r_outer``.
    theta1_compass_deg, theta2_compass_deg : float
        Compass angle bounds (degrees, ``theta1 < theta2``).
    n_arc : int, default 64
        Number of segments per arc. Higher = smoother polygon.

    Returns
    -------
    shapely.geometry.Polygon
    """
    if not (r_inner < r_outer):
        raise ValueError(f"r_inner ({r_inner}) must be less than r_outer ({r_outer}).")
    if not (theta1_compass_deg < theta2_compass_deg):
        raise ValueError(f"theta1 ({theta1_compass_deg}) must be less than theta2 ({theta2_compass_deg}).")

    angles = np.linspace(theta1_compass_deg, theta2_compass_deg, n_arc)
    # Outer arc: theta1 -> theta2 (CW in compass space)
    outer = [_compass_xy(center, r_outer, a) for a in angles]
    # Inner arc walked backwards to close the polygon
    inner = [_compass_xy(center, r_inner, a) for a in angles[::-1]]
    # If r_inner == 0 the inner "arc" degenerates to a single repeated point;
    # shapely is OK with that but we de-duplicate to keep things clean.
    coords = outer + inner
    return Polygon(coords)


# ===========================================================================
# 2. Data loading helpers
# ===========================================================================

def _resolve(path: str | os.PathLike) -> pathlib.Path:
    """Resolve a possibly-relative path against the current working directory
    and return a ``pathlib.Path``. Raises ``FileNotFoundError`` if missing.
    """
    p = pathlib.Path(path)
    if not p.is_absolute():
        p = pathlib.Path.cwd() / p
    if not p.exists():
        raise FileNotFoundError(f"Required input not found: {p}")
    return p


def load_leuven_ring_area(
    boundary_path: str | os.PathLike = DEFAULT_BOUNDARY_PATH,
    target_crs: str = DEFAULT_CRS,
) -> gpd.GeoDataFrame:
    """Load the Leuven Ring Area polygons.

    The boundary GeoJSON does not carry CRS metadata; the original notebook
    *asserts* its CRS to EPSG:31370 without reprojecting. This function does
    the same to stay byte-compatible with the existing pipeline.

    Selection rule (from the notebook):
      keep ``ogc_fid`` in ``[4040, 4075]`` and drop ``4051`` and ``4053``.

    Parameters
    ----------
    boundary_path : path-like
        Path to ``CityLeuvenBoundary.geojson``.
    target_crs : str, default EPSG:31370.
        Asserted on the loaded boundary if no CRS metadata is present.

    Returns
    -------
    GeoDataFrame
        The polygons composing the Leuven Ring Area, reset-indexed.
    """
    p = _resolve(boundary_path)
    gdf = gpd.read_file(p)
    if gdf.crs is None:
        gdf.set_crs(target_crs, inplace=True)
    elif str(gdf.crs).upper() != target_crs.upper():
        gdf = gdf.to_crs(target_crs)

    ring = gdf[gdf["ogc_fid"].between(4040, 4075, inclusive="both")]
    ring = ring[~ring["ogc_fid"].isin([4051, 4053])]
    return ring.reset_index(drop=True)


def load_cropped_network(
    cropped_geojson_path: str | os.PathLike = DEFAULT_CROPPED_NETWORK_PATH,
    target_crs: str = DEFAULT_CRS,
) -> gpd.GeoDataFrame:
    """Load the cropped car network (LineString geometries) from a cached GeoJSON.

    The cached file is produced by the upstream notebook from the MATSim XML
    network and the bbox ``DEFAULT_BBOX``. We prefer loading the cache for
    speed; the user can pass any compatible GeoJSON.

    Returns
    -------
    GeoDataFrame
        Network links, with the ``link_id`` column required by downstream
        snapping. CRS is forced to ``target_crs`` if missing.
    """
    p = _resolve(cropped_geojson_path)
    gdf = gpd.read_file(p)
    if gdf.crs is None:
        gdf.set_crs(target_crs, inplace=True)
    elif str(gdf.crs).upper() != target_crs.upper():
        gdf = gdf.to_crs(target_crs)
    if "link_id" not in gdf.columns:
        raise ValueError(f"Cropped network at {p} is missing the 'link_id' column.")
    return gdf.reset_index(drop=True)


def load_retail_pois_in_ring(
    poi_path: str | os.PathLike,
    ring_area: gpd.GeoDataFrame,
    target_crs: str = DEFAULT_CRS,
) -> gpd.GeoDataFrame:
    """Load FSQ POIs and keep only retail POIs that fall inside the ring area.

    Filtering rule:
      * ``cat1_name == 'Retail'``
      * spatial join: ``within`` ``ring_area`` (any polygon).

    Parameters
    ----------
    poi_path : path-like
        Path to ``poi-fsq-point.gpkg``.
    ring_area : GeoDataFrame
        Leuven Ring Area polygons (CRS will be reprojected to ``target_crs``).
    target_crs : str

    Returns
    -------
    GeoDataFrame
        Retail POIs inside the ring area, reset-indexed.
    """
    p = _resolve(poi_path)
    poi = gpd.read_file(p).to_crs(target_crs)
    if ring_area.crs != target_crs:
        ring_area = ring_area.to_crs(target_crs)

    if "cat1_name" not in poi.columns:
        raise ValueError(f"POI file {p} is missing 'cat1_name' (level-1 category).")
    retail = poi[poi["cat1_name"] == "Retail"]

    # `sjoin` adds 'index_right' (and possibly other ring_area columns); drop
    # the helper columns so the returned GeoDataFrame stays clean.
    joined = gpd.sjoin(retail, ring_area, how="inner", predicate="within")
    drop_cols = [c for c in joined.columns if c.startswith("index_right") or c.endswith("_right")]
    joined = joined.drop(columns=drop_cols, errors="ignore")
    # Deduplicate: a POI could match multiple ring polygons if they touch.
    if "place_id" in joined.columns:
        joined = joined.drop_duplicates(subset="place_id")
    return joined.reset_index(drop=True)


# ===========================================================================
# 3. NNI utilities
# ===========================================================================

def calculate_nni(
    points_or_gdf,
    study_area: float | None = None,
) -> Tuple[float, float, float, float]:
    """Compute the Nearest-Neighbour Index (NNI) for a set of points.

    NNI = d_obs / d_exp where:
      * ``d_obs`` is the mean nearest-neighbour distance,
      * ``d_exp = 0.5 / sqrt(n / A)`` (expected NN distance under CSR).

    Parameters
    ----------
    points_or_gdf : GeoDataFrame or array-like
        Either a GeoDataFrame of Point geometries (projected CRS expected) or
        a numpy array of shape ``(n, 2)`` already in EPSG:31370 metres.
    study_area : float, optional
        Area (m²). If ``None`` and a GeoDataFrame is given, the area of its
        unary union's convex hull is used. Always pass this explicitly for
        reproducible NNI values.

    Returns
    -------
    (nni, d_obs, d_exp, z_score)
    """
    if isinstance(points_or_gdf, gpd.GeoDataFrame):
        coords = np.array([(g.x, g.y) for g in points_or_gdf.geometry])
        if study_area is None:
            try:
                study_area = float(points_or_gdf.geometry.union_all().convex_hull.area)
            except AttributeError:  # shapely < 2.1 fallback
                study_area = float(points_or_gdf.geometry.unary_union.convex_hull.area)
    else:
        coords = np.asarray(points_or_gdf, dtype=float)
        if study_area is None:
            raise ValueError("study_area must be provided when passing a raw coords array.")

    n = len(coords)
    if n < 2:
        raise ValueError("NNI requires at least 2 points.")

    nbrs = NearestNeighbors(n_neighbors=2).fit(coords)
    dists, _ = nbrs.kneighbors(coords)
    d_obs = float(dists[:, 1].mean())  # column 0 is distance-to-self (0)
    d_exp = 0.5 / np.sqrt(n / study_area)
    nni = d_obs / d_exp
    # Standard error under CSR (Clark & Evans, 1954).
    se = 0.26136 / np.sqrt(n * n / study_area)
    z = (d_obs - d_exp) / se if se > 0 else float("nan")
    return float(nni), float(d_obs), float(d_exp), float(z)


def _greedy_clustered_sample(coords: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    """Build the most-clustered greedy sample of size n.

    Strategy: start from a random POI, then repeatedly add the *unused* POI
    that has the smallest minimum-distance to the current set. This is the
    "anti-farthest-point" heuristic and produces a sample tightly compressed
    around a local hotspot.
    """
    n_pool = len(coords)
    start = int(rng.integers(0, n_pool))
    selected = [start]
    available = np.ones(n_pool, dtype=bool)
    available[start] = False
    # Distance from every pool point to the *nearest* selected point.
    nearest_d = np.linalg.norm(coords - coords[start], axis=1)
    nearest_d[start] = np.inf  # ignore self

    for _ in range(n - 1):
        # Mask out already-selected points.
        d = np.where(available, nearest_d, np.inf)
        next_idx = int(np.argmin(d))
        selected.append(next_idx)
        available[next_idx] = False
        # Update the "min distance to any selected" for remaining points.
        d_new = np.linalg.norm(coords - coords[next_idx], axis=1)
        nearest_d = np.minimum(nearest_d, d_new)
        nearest_d[next_idx] = np.inf
    return coords[selected]


def _greedy_dispersed_sample(coords: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    """Build the most-dispersed greedy sample of size n (farthest-point sampling).

    Strategy: start from a random POI, then repeatedly add the unused POI that
    *maximises* its minimum-distance to the current set. Classic FPS / Gonzalez
    algorithm.
    """
    n_pool = len(coords)
    start = int(rng.integers(0, n_pool))
    selected = [start]
    available = np.ones(n_pool, dtype=bool)
    available[start] = False
    nearest_d = np.linalg.norm(coords - coords[start], axis=1)
    nearest_d[start] = -np.inf  # ignore self

    for _ in range(n - 1):
        d = np.where(available, nearest_d, -np.inf)
        next_idx = int(np.argmax(d))
        selected.append(next_idx)
        available[next_idx] = False
        d_new = np.linalg.norm(coords - coords[next_idx], axis=1)
        nearest_d = np.minimum(nearest_d, d_new)
        nearest_d[next_idx] = -np.inf
    return coords[selected]


def estimate_nni_range(
    poi_gdf: gpd.GeoDataFrame,
    study_area: float,
    n_customers: int,
    random_state: int = 0,
) -> Tuple[float, float]:
    """Estimate the achievable NNI range on ``poi_gdf`` for sample size ``n_customers``.

    Returns ``(min_nni_est, max_nni_est)`` from two cheap greedy bounds:

    * ``min_nni_est`` — the NNI of a *clustered* greedy sample (lower bound on
      how clustered the user can force the sample to be; the global minimum
      may be slightly lower).
    * ``max_nni_est`` — the NNI of a *dispersed* (farthest-point) greedy
      sample (upper bound on how dispersed the user can force the sample to
      be; the global maximum may be slightly higher).

    These bounds are heuristic, not exact, but are tight enough for the
    feasibility check in :func:`sample_customers_with_target_nni`.

    Parameters
    ----------
    poi_gdf : GeoDataFrame
        Candidate POIs (already filtered).
    study_area : float
        Area in m² used for ``d_exp``.
    n_customers : int
        Target sample size.
    random_state : int
        Seed for the random *starting* point of each greedy walk; the rest of
        each walk is deterministic.

    Returns
    -------
    (min_nni_est, max_nni_est)
    """
    coords = np.array([(g.x, g.y) for g in poi_gdf.geometry], dtype=float)
    if n_customers > len(coords):
        raise ValueError(f"n_customers ({n_customers}) exceeds POI pool size ({len(coords)}).")
    rng = np.random.default_rng(random_state)

    clustered = _greedy_clustered_sample(coords, n_customers, rng)
    dispersed = _greedy_dispersed_sample(coords, n_customers, rng)
    min_nni, *_ = calculate_nni(clustered, study_area=study_area)
    max_nni, *_ = calculate_nni(dispersed, study_area=study_area)
    return float(min_nni), float(max_nni)


# ===========================================================================
# 4. Customer sampling under a target NNI
# ===========================================================================

@dataclass
class _SwapStats:
    """Diagnostic accumulator for the NNI swap loop."""
    iterations: int = 0
    accepted: int = 0
    rejected: int = 0


def sample_customers_with_target_nni(
    poi_gdf: gpd.GeoDataFrame,
    study_area_geom,
    n_customers: int,
    target_nni: float,
    tolerance: float = 0.05,
    max_iter: int = 500,
    seed_customers: int = 42,
    feasibility_margin: float = 0.0,
    candidate_top_k: int = 20,
) -> Tuple[gpd.GeoDataFrame, dict]:
    """Sample ``n_customers`` POIs from ``poi_gdf`` whose spatial pattern
    achieves an NNI within ``tolerance`` of ``target_nni``.

    Algorithm — greedy first-improvement swap:

    1. Compute the study area from ``study_area_geom`` (a shapely polygon or
       a float in m²) and run :func:`estimate_nni_range`. If ``target_nni``
       lies outside ``[min_est - feasibility_margin, max_est + feasibility_margin]``,
       raise ``ValueError`` with the achievable range in the message.
    2. Draw an initial random sample of ``n_customers`` indices.
    3. At each iteration, find the point that hurts the current NNI the most
       (the most clustered one if we need more dispersion; the most isolated
       one if we need more clustering) and try to swap it with an unused
       POI drawn from the top-``candidate_top_k`` candidates ordered by
       distance-to-current-set (far candidates when we need dispersion;
       close candidates when we need clustering).
    4. Accept the swap iff it brings the NNI strictly closer to the target.
    5. Stop on convergence (``|achieved - target| <= tolerance``) or
       ``max_iter``. If the loop exhausts ``max_iter`` without converging,
       raise ``ValueError``.

    Parameters
    ----------
    poi_gdf : GeoDataFrame
        Candidate POIs (filtered to retail-inside-ring).
    study_area_geom : shapely geometry or float
        If a geometry, ``geometry.area`` is used. If a float, it is interpreted
        as the area in m².
    n_customers : int
    target_nni : float
    tolerance : float, default 0.05
        Stop the loop when ``|nni - target| <= tolerance``.
    max_iter : int, default 500
        Hard upper bound on swap attempts.
    seed_customers : int, default 42
    feasibility_margin : float, default 0.0
        Extra slack added to the achievable range before raising
        ``ValueError`` — useful if the greedy bounds are slightly pessimistic.
    candidate_top_k : int, default 20
        How many extreme candidates to consider on each swap attempt.

    Returns
    -------
    (selected_gdf, stats)
        ``selected_gdf`` — a GeoDataFrame of ``n_customers`` rows from
        ``poi_gdf`` (reset-indexed).
        ``stats`` — diagnostic dict with keys
        ``{nni, d_obs, d_exp, z_score, iterations, target_nni,
        tolerance, study_area_m2, min_nni_est, max_nni_est,
        accepted_swaps, rejected_swaps}``.

    Raises
    ------
    ValueError
        If ``target_nni`` is outside the achievable range or the swap loop
        cannot converge within ``max_iter``.
    """
    # ---- 4.1. Resolve study area -------------------------------------------------
    if hasattr(study_area_geom, "area"):
        study_area = float(study_area_geom.area)
    else:
        study_area = float(study_area_geom)
    if study_area <= 0:
        raise ValueError("study_area must be a positive number of m².")

    coords = np.array([(g.x, g.y) for g in poi_gdf.geometry], dtype=float)
    n_pool = len(coords)
    if n_customers < 2:
        raise ValueError("n_customers must be at least 2 to compute NNI.")
    if n_customers > n_pool:
        raise ValueError(
            f"n_customers ({n_customers}) exceeds the available POI pool size "
            f"({n_pool}). Lower n_customers or broaden the POI filter."
        )

    # ---- 4.2. Feasibility check --------------------------------------------------
    min_est, max_est = estimate_nni_range(
        poi_gdf, study_area=study_area, n_customers=n_customers,
        random_state=seed_customers,
    )
    lo = min_est - feasibility_margin
    hi = max_est + feasibility_margin
    if not (lo - tolerance <= target_nni <= hi + tolerance):
        raise ValueError(
            f"Target NNI {target_nni:.3f} is not achievable for this POI pool "
            f"with n_customers={n_customers}. "
            f"Estimated achievable NNI range on the current pool ≈ "
            f"[{min_est:.3f}, {max_est:.3f}] (greedy bounds). "
            f"Pick a target inside this range — keeping a small margin from "
            f"the bounds avoids slow convergence."
        )

    # ---- 4.3. Initial random sample ----------------------------------------------
    rng = np.random.default_rng(seed_customers)
    selected = list(rng.choice(n_pool, size=n_customers, replace=False))
    sel_set = set(selected)

    def _nni_of(idxs: List[int]) -> Tuple[float, float, float, float]:
        return calculate_nni(coords[idxs], study_area=study_area)

    nni, d_obs, d_exp, z = _nni_of(selected)
    diag = _SwapStats()

    # ---- 4.4. Swap loop ----------------------------------------------------------
    for it in range(max_iter):
        diag.iterations = it + 1
        if abs(nni - target_nni) <= tolerance:
            break

        sel_coords = coords[selected]

        # Per-point nearest-neighbour distance inside the current sample.
        nbrs = NearestNeighbors(n_neighbors=2).fit(sel_coords)
        nn_dists, _ = nbrs.kneighbors(sel_coords)
        per_point_nn = nn_dists[:, 1]

        # Candidates outside the current sample.
        avail_idx = np.array(sorted(set(range(n_pool)) - sel_set), dtype=int)
        avail_coords = coords[avail_idx]

        # For each available candidate, compute its distance to the *nearest*
        # currently-selected point. Used both for picking the addition and for
        # later acceptance checking.
        # Shape: (n_avail, n_sel, 2) is the broadcast; we collapse to (n_avail,).
        d_to_sel = np.min(
            np.linalg.norm(avail_coords[:, None, :] - sel_coords[None, :, :], axis=2),
            axis=1,
        )

        if nni < target_nni:
            # Need MORE dispersion: drop the most-clustered point; add a far one.
            drop_pos = int(np.argmin(per_point_nn))
            k = min(candidate_top_k, len(avail_idx))
            top_far = np.argpartition(-d_to_sel, k - 1)[:k]
            add_pos = int(rng.choice(top_far))
        else:
            # Need MORE clustering: drop the most-isolated point; add a close one.
            drop_pos = int(np.argmax(per_point_nn))
            k = min(candidate_top_k, len(avail_idx))
            top_close = np.argpartition(d_to_sel, k - 1)[:k]
            add_pos = int(rng.choice(top_close))

        # Speculatively perform the swap and re-score.
        old_idx = selected[drop_pos]
        new_idx = int(avail_idx[add_pos])
        trial = selected.copy()
        trial[drop_pos] = new_idx
        new_nni, *_ = _nni_of(trial)

        # First-improvement acceptance.
        if abs(new_nni - target_nni) < abs(nni - target_nni):
            selected = trial
            sel_set = (sel_set - {old_idx}) | {new_idx}
            nni = new_nni
            diag.accepted += 1
        else:
            diag.rejected += 1

    # ---- 4.5. Final stats / non-convergence handling ----------------------------
    if abs(nni - target_nni) > tolerance:
        raise ValueError(
            f"NNI sampling did not converge in {max_iter} iterations. "
            f"Achieved NNI = {nni:.3f}, target = {target_nni:.3f}, "
            f"tolerance = {tolerance:.3f}. "
            f"Achievable range estimate ≈ [{min_est:.3f}, {max_est:.3f}]. "
            f"Try a target closer to the middle of that range, a wider "
            f"tolerance, or a larger max_iter."
        )

    nni_final, d_obs, d_exp, z = _nni_of(selected)
    stats = {
        "nni": nni_final,
        "d_obs": d_obs,
        "d_exp": d_exp,
        "z_score": z,
        "iterations": diag.iterations,
        "accepted_swaps": diag.accepted,
        "rejected_swaps": diag.rejected,
        "target_nni": target_nni,
        "tolerance": tolerance,
        "study_area_m2": study_area,
        "min_nni_est": min_est,
        "max_nni_est": max_est,
    }
    return poi_gdf.iloc[selected].reset_index(drop=True), stats


# ===========================================================================
# 5. Depot generation — 10 rings × 4 sectors, strictly inside cropped network
# ===========================================================================

def _extract_vertices(geom) -> np.ndarray:
    """Flatten all line-string vertices in a (Multi)LineString into an ``(N, 2)``
    numpy array. Used to compute ``R_max`` in ``hull`` mode.
    """
    if isinstance(geom, LineString):
        return np.asarray(geom.coords)
    if isinstance(geom, MultiLineString):
        return np.concatenate([np.asarray(ls.coords) for ls in geom.geoms])
    # Fallback for arbitrary geometry collections.
    parts = []
    for g in getattr(geom, "geoms", [geom]):
        if hasattr(g, "coords"):
            parts.append(np.asarray(g.coords))
    if not parts:
        raise TypeError(f"Cannot extract vertices from geometry of type {type(geom).__name__}")
    return np.concatenate(parts)


def _per_sector_max_reach(
    vertices: np.ndarray,
    center: Tuple[float, float],
    sectors_deg: Sequence[Tuple[float, float]],
) -> List[float]:
    """Maximum vertex-to-centre distance restricted to each compass sector.

    A "reach" of 0 is returned for any sector with no vertices inside its
    angular range (rare in practice — would indicate the network has a hole
    in that quadrant).
    """
    dxy = vertices - np.asarray(center)
    dists = np.linalg.norm(dxy, axis=1)
    # Compass bearing of each vertex (atan2(dx, dy) with dx = east, dy = north).
    bearings = np.degrees(np.arctan2(dxy[:, 0], dxy[:, 1])) % 360.0
    reach = []
    for c1, c2 in sectors_deg:
        mask = (bearings >= c1) & (bearings <= c2)
        reach.append(float(dists[mask].max()) if mask.any() else 0.0)
    return reach


def generate_all_ring_sector_depots(
    network_gdf_cropped: gpd.GeoDataFrame,
    n_rings: int = 10,
    inner_frac: float = 0.1,
    sectors_deg: Sequence[Tuple[float, float]] = DEFAULT_SECTORS_DEG,
    sector_names: Sequence[str] = DEFAULT_SECTOR_NAMES,
    radius_mode: str = "min_sector_reach",
    center: Optional[Tuple[float, float]] = None,
    snap_tolerance: float = 20.0,
    max_attempts: int = 500,
    seed_depots: int = 42,
) -> gpd.GeoDataFrame:
    """Generate the full set of ``n_rings × len(sectors_deg)`` depots.

    The function produces one depot per ``(ring, sector)`` cell. By default
    that is 10 × 4 = 40 depots. All depots are sampled strictly inside the
    cropped network and matched against ``network_gdf_cropped`` only — never
    against the full city-wide car network.

    Ring construction
    -----------------
    * ``R_max`` controls how far the outermost ring reaches. Modes:

      - ``'min_sector_reach'`` *(default)* — for each sector, find the
        farthest cropped-network vertex inside that sector; ``R_max`` is the
        **minimum** of those four reaches. This guarantees every
        ``(ring, sector)`` cell has non-empty candidate space, at the cost of
        the outermost ring sitting slightly inside the bbox in the
        wider-reach sectors.
      - ``'hull'`` — global maximum vertex-to-centre distance. May leave some
        outer-ring cells empty in asymmetric networks (raises in that case).
      - ``'bbox'`` — corner-of-bbox distance. Same caveat as ``'hull'``.

    * Mid-band ring layout: bands tile the radial interval
      ``[inner_frac·R_max, R_max]`` cleanly without overflow::

          dr        = (R_max − inner_frac·R_max) / n_rings
          r_k       = inner_frac·R_max + (k + 0.5)·dr      for k = 0..n_rings−1
          band_k    = [r_k − dr/2, r_k + dr/2]

      With the defaults (``inner_frac=0.1``, ``n_rings=10``) the bands are
      9 % of ``R_max`` wide, the innermost band starts exactly at
      ``0.1·R_max``, and the outermost band ends exactly at ``R_max``.

    Sector convention
    -----------------
    Compass bearings, 0° = North, CW positive. Defaults: ``((30, 60),
    (120, 150), (210, 240), (300, 330))`` → NE, SE, SW, NW.

    Strict-cropped-network rule (XP Q8)
    -----------------------------------
    The candidate region for sampling is::

        annular_sector ∩ network_union.buffer(snap_tolerance)

    so every depot must be within ``snap_tolerance`` metres of a link in the
    cropped network. The nearest-link search runs only against
    ``network_gdf_cropped``.

    Returns
    -------
    GeoDataFrame
        Up to ``n_rings * len(sectors_deg)`` rows with columns:

        * ``depot_id`` — ``f"r{ring_idx}_{sector_name}"``, e.g. ``r5_NE``
        * ``ring_idx``, ``sector_idx``, ``sector_name``
        * ``ring_radius_m``
        * ``sample_x``, ``sample_y`` — the sampled point pre-snap
        * ``snap_x``, ``snap_y`` — the snapped point on the matched link
        * ``matched_link_id``
        * ``distance_to_link_m``
        * ``geometry`` — Point at ``(snap_x, snap_y)``

        The result also carries these attributes on its ``.attrs`` dict, which
        the plotter uses to draw rings/sector wedges:

        * ``center`` — ``(cx, cy)``
        * ``R_max`` — float
        * ``inner_frac`` — float
        * ``ring_radii`` — list[float]
        * ``sectors_deg`` — tuple of (c1, c2)
        * ``sector_names`` — tuple of str

    Raises
    ------
    RuntimeError
        If any ``(ring, sector)`` cell yields an empty candidate region or no
        valid sample within ``max_attempts``.
    """
    if len(sectors_deg) != len(sector_names):
        raise ValueError("sectors_deg and sector_names must have the same length.")
    if "link_id" not in network_gdf_cropped.columns:
        raise ValueError("network_gdf_cropped must contain a 'link_id' column.")

    # ---- 5.1. Centre, R_max, ring radii -----------------------------------------
    network_union = network_gdf_cropped.geometry.union_all() \
        if hasattr(network_gdf_cropped.geometry, "union_all") \
        else network_gdf_cropped.geometry.unary_union

    if center is None:
        c_geom = network_union.centroid
        center = (float(c_geom.x), float(c_geom.y))

    verts = _extract_vertices(network_union)
    if radius_mode == "min_sector_reach":
        # Use the *minimum* per-sector reach so every sector has roads at every
        # ring radius. This trims the outermost ring slightly relative to the
        # global hull but guarantees a full 40-cell grid.
        per_sector = _per_sector_max_reach(verts, center, sectors_deg)
        if min(per_sector) <= 0:
            raise RuntimeError(
                "At least one sector has no network vertices — cannot use "
                "radius_mode='min_sector_reach'. Try 'hull' or check the "
                "cropped network coverage."
            )
        R_max = float(min(per_sector))
    elif radius_mode == "hull":
        dists = np.linalg.norm(verts - np.asarray(center), axis=1)
        R_max = float(dists.max())
    elif radius_mode == "bbox":
        minx, miny, maxx, maxy = network_gdf_cropped.total_bounds
        corners = np.array([(minx, miny), (minx, maxy), (maxx, miny), (maxx, maxy)])
        R_max = float(np.linalg.norm(corners - np.asarray(center), axis=1).max())
    else:
        raise ValueError(
            f"radius_mode must be 'min_sector_reach', 'hull', or 'bbox', "
            f"got {radius_mode!r}."
        )

    # Mid-band ring layout: bands tile [inner_r, R_max] exactly without overflow.
    inner_r = inner_frac * R_max
    dr = (R_max - inner_r) / n_rings
    ring_radii = inner_r + (np.arange(n_rings) + 0.5) * dr

    # ---- 5.2. Sampling region and snap structures -------------------------------
    # Slightly buffered network footprint defines the "snap-able" zone.
    cropped_zone = network_union.buffer(snap_tolerance)
    link_geoms = list(network_gdf_cropped.geometry.values)
    link_ids = network_gdf_cropped["link_id"].astype(str).values
    tree = STRtree(link_geoms)

    # Deterministic per-cell sub-streams: same seed_depots ⇒ same depot points,
    # even if n_rings or sectors_deg are changed independently between runs.
    def _subrng(ring_i: int, sector_i: int) -> np.random.Generator:
        return np.random.default_rng((seed_depots, ring_i, sector_i))

    # ---- 5.3. Per-(ring, sector) sampling ---------------------------------------
    rows = []
    for ring_idx, r_k in enumerate(ring_radii):
        r_in = max(r_k - dr / 2.0, 0.0)
        r_out = r_k + dr / 2.0
        for sector_idx, (c1, c2) in enumerate(sectors_deg):
            sector_name = sector_names[sector_idx]
            sector_poly = _annular_sector_polygon(center, r_in, r_out, c1, c2)
            candidate = sector_poly.intersection(cropped_zone)
            if candidate.is_empty:
                raise RuntimeError(
                    f"No candidate region for ring={ring_idx} sector={sector_name} "
                    f"(annular band [{r_in:.1f}, {r_out:.1f}] m ∩ buffered cropped "
                    f"network is empty)."
                )

            sub = _subrng(ring_idx, sector_idx)
            minx, miny, maxx, maxy = candidate.bounds
            sampled: Optional[Point] = None
            for _ in range(max_attempts):
                x = float(sub.uniform(minx, maxx))
                y = float(sub.uniform(miny, maxy))
                p = Point(x, y)
                if candidate.contains(p):
                    sampled = p
                    break
            if sampled is None:
                raise RuntimeError(
                    f"Failed to sample a depot inside ring={ring_idx} "
                    f"sector={sector_name} within {max_attempts} attempts. "
                    f"The candidate region area is "
                    f"{candidate.area:.0f} m²; consider relaxing snap_tolerance."
                )

            # Snap to nearest link in the cropped network.
            nearest_link_idx = tree.nearest(sampled)
            nearest_geom = link_geoms[nearest_link_idx]
            link_id = link_ids[nearest_link_idx]
            _, snap_pt = nearest_points(sampled, nearest_geom)
            snap_dist = float(sampled.distance(nearest_geom))

            rows.append({
                "depot_id": f"r{ring_idx}_{sector_name}",
                "ring_idx": ring_idx,
                "sector_idx": sector_idx,
                "sector_name": sector_name,
                "ring_radius_m": float(r_k),
                "sample_x": float(sampled.x),
                "sample_y": float(sampled.y),
                "snap_x": float(snap_pt.x),
                "snap_y": float(snap_pt.y),
                "matched_link_id": str(link_id),
                "distance_to_link_m": snap_dist,
                "geometry": snap_pt,
            })

    depots_gdf = gpd.GeoDataFrame(rows, geometry="geometry", crs=network_gdf_cropped.crs)
    # Stash metadata for the plotter (not persisted to CSV).
    depots_gdf.attrs.update({
        "center": center,
        "R_max": R_max,
        "inner_frac": inner_frac,
        "ring_radii": ring_radii.tolist(),
        "sectors_deg": tuple(sectors_deg),
        "sector_names": tuple(sector_names),
    })
    return depots_gdf


# ===========================================================================
# 6. Network matching (customers and depots both reach this)
# ===========================================================================

def match_points_to_links(
    points_gdf: gpd.GeoDataFrame,
    network_gdf: gpd.GeoDataFrame,
    link_id_col: str = "link_id",
) -> gpd.GeoDataFrame:
    """Match each point to the nearest network link using a Shapely STRtree.

    This is a verbatim port of ``match_customers_to_links_fast`` from the
    notebook, generalised to accept any points (customers or depots).

    Parameters
    ----------
    points_gdf : GeoDataFrame
        Point geometries (must be projected; EPSG:31370 is assumed downstream).
    network_gdf : GeoDataFrame
        LineString geometries with a column matching ``link_id_col``.
    link_id_col : str, default ``'link_id'``.

    Returns
    -------
    GeoDataFrame
        Copy of ``points_gdf`` with three new columns:
        ``matched_link_id``, ``distance_to_link``, ``snap_point``.
    """
    result = points_gdf.copy()
    net = network_gdf.reset_index(drop=True)
    link_geoms = list(net.geometry.values)
    if link_id_col in net.columns:
        link_ids = net[link_id_col].astype(str).values
    else:
        link_ids = net.index.astype(str).values

    tree = STRtree(link_geoms)

    matched, distances, snaps = [], [], []
    for pt in result.geometry:
        idx = tree.nearest(pt)  # shapely ≥2 returns an integer index
        link_geom = link_geoms[idx]
        d = pt.distance(link_geom)
        _, snap_pt = nearest_points(pt, link_geom)
        matched.append(link_ids[idx])
        distances.append(float(d))
        snaps.append(snap_pt)

    result["matched_link_id"] = matched
    result["distance_to_link"] = distances
    result["snap_point"] = gpd.GeoSeries(snaps, crs=result.crs)
    return result


# ===========================================================================
# 7. Customer → depot assignment
# ===========================================================================

def assign_customers_to_depots(
    customers_df: pd.DataFrame,
    depot_link_ids: Sequence[str],
    mode: str = "roundrobin",
    depot_geoms: Optional[Sequence[Tuple[float, float]]] = None,
    seed_assignment: int = 42,
) -> pd.DataFrame:
    """Assign customers evenly to a user-supplied list of depots.

    Each depot receives ``floor(N/D)`` customers; the first ``N mod D``
    depots receive one extra so the total is exactly ``N``.

    Modes
    -----
    ``'roundrobin'`` (default)
        Shuffle customers with ``seed_assignment``, then deal them to depots
        in turn. Equal counts, no geographic bias — useful when you want a
        truly demand-balanced baseline.

    ``'nearest_balanced'``
        Solve a balanced assignment via the Hungarian algorithm
        (``scipy.optimize.linear_sum_assignment``) on an expanded cost matrix
        where each depot is duplicated by its capacity. Minimises total
        Euclidean customer-to-depot distance subject to the capacity
        constraint. Requires ``depot_geoms``.

    Parameters
    ----------
    customers_df : DataFrame or GeoDataFrame
        Must contain a geometry column when ``mode='nearest_balanced'``.
    depot_link_ids : sequence of str
        Length ``D`` (any ``D ≥ 1``).
    mode : {'roundrobin', 'nearest_balanced'}
    depot_geoms : sequence of (x, y), optional
        Required for ``nearest_balanced``. Length must equal
        ``len(depot_link_ids)``; order must match.
    seed_assignment : int

    Returns
    -------
    DataFrame
        Copy of ``customers_df`` with a new ``depot_linkId`` column.
    """
    if len(depot_link_ids) == 0:
        raise ValueError("depot_link_ids must be non-empty.")

    df = customers_df.copy().reset_index(drop=True)
    N = len(df)
    D = len(depot_link_ids)

    # Capacities: floor(N/D) + 1 for the first (N mod D) depots.
    base = N // D
    rem = N % D
    capacities = [base + (1 if i < rem else 0) for i in range(D)]
    assert sum(capacities) == N

    if mode == "roundrobin":
        # Stable seeded shuffle, then deal customers in order.
        rng = np.random.default_rng(seed_assignment)
        order = np.arange(N)
        rng.shuffle(order)
        depot_for_order = np.empty(N, dtype=object)
        for slot, cust_idx in enumerate(order):
            depot_for_order[cust_idx] = depot_link_ids[slot % D]
        df["depot_linkId"] = depot_for_order

    elif mode == "nearest_balanced":
        if depot_geoms is None:
            raise ValueError("nearest_balanced mode requires depot_geoms.")
        if len(depot_geoms) != D:
            raise ValueError(
                f"len(depot_geoms) ({len(depot_geoms)}) must equal "
                f"len(depot_link_ids) ({D})."
            )
        if "geometry" not in df.columns:
            raise ValueError("nearest_balanced requires customers_df to have a geometry column.")

        # Customer coordinates.
        cust_xy = np.array([(g.x, g.y) for g in df.geometry], dtype=float)
        depot_xy = np.asarray(depot_geoms, dtype=float)

        # Expand depots according to their capacities — one column per "slot".
        slot_to_depot: List[int] = []
        for j, cap in enumerate(capacities):
            slot_to_depot.extend([j] * cap)
        # Sanity: total slots = N because sum(capacities) = N.
        assert len(slot_to_depot) == N
        slot_coords = depot_xy[slot_to_depot]  # shape (N, 2)

        # (N customers) × (N slots) cost matrix of Euclidean distances.
        cost = np.linalg.norm(cust_xy[:, None, :] - slot_coords[None, :, :], axis=2)
        row_ind, col_ind = linear_sum_assignment(cost)

        assignment = [None] * N
        for r, c in zip(row_ind, col_ind):
            assignment[r] = depot_link_ids[slot_to_depot[c]]
        df["depot_linkId"] = assignment

    else:
        raise ValueError(f"mode must be 'roundrobin' or 'nearest_balanced', got {mode!r}.")

    return df


# ===========================================================================
# 8. Output helpers
# ===========================================================================

def save_demand_csv(customers_df: pd.DataFrame, out_path: str | os.PathLike) -> str:
    """Save the customer demand CSV in the exact schema used by
    ``data/randomDemand60Receivers/inside_clustered/demand5.csv.gz``.

    Output columns
    --------------
    ``Unnamed: 0, place_id, matched_link_id, depot_linkId``

    The leading ``Unnamed: 0`` column comes from pandas' default index
    writing; we deliberately do **not** pass ``index=False`` to match the
    existing files byte-for-byte (column-wise).

    Parameters
    ----------
    customers_df : DataFrame
        Must carry ``place_id``, ``matched_link_id``, ``depot_linkId``.
    out_path : path-like
        Path ending in ``.csv.gz``.

    Returns
    -------
    str
        The resolved absolute path that was written.
    """
    required = ["place_id", "matched_link_id", "depot_linkId"]
    missing = [c for c in required if c not in customers_df.columns]
    if missing:
        raise ValueError(f"customers_df is missing required columns: {missing}")

    out = pathlib.Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    customers_df[required].reset_index(drop=True).to_csv(out, compression="gzip")
    return str(out.resolve())


def save_depots_csv(depots_gdf: gpd.GeoDataFrame, out_path: str | os.PathLike) -> str:
    """Save the depot sidecar CSV.

    Output columns (geometry is dropped)
    ------------------------------------
    ``depot_id, ring_idx, sector_idx, sector_name, ring_radius_m,
    sample_x, sample_y, snap_x, snap_y, matched_link_id,
    distance_to_link_m``

    Returns the resolved absolute path written.
    """
    cols = [
        "depot_id", "ring_idx", "sector_idx", "sector_name", "ring_radius_m",
        "sample_x", "sample_y", "snap_x", "snap_y",
        "matched_link_id", "distance_to_link_m",
    ]
    missing = [c for c in cols if c not in depots_gdf.columns]
    if missing:
        raise ValueError(f"depots_gdf is missing required columns: {missing}")

    out = pathlib.Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    depots_gdf[cols].to_csv(out, compression="gzip", index=False)
    return str(out.resolve())


# ===========================================================================
# 9. Plotting
# ===========================================================================

def plot_instance(
    network_gdf_cropped: gpd.GeoDataFrame,
    ring_area: gpd.GeoDataFrame,
    customers_gdf: Optional[gpd.GeoDataFrame],
    depots_gdf: gpd.GeoDataFrame,
    selected_depot_link_ids: Optional[Sequence[str]] = None,
    pois_gdf: Optional[gpd.GeoDataFrame] = None,
    show_rings: bool = True,
    show_sectors: bool = True,
    show_pois: bool = False,
    title: Optional[str] = None,
    figsize: Tuple[float, float] = (10.0, 10.0),
) -> plt.Figure:
    """Render an inspection plot of one instance.

    Layers
    ------
    1. Cropped network (light grey).
    2. Leuven Ring Area outline (thin black).
    3. Optional candidate POIs (faint dots).
    4. Optional ring circles and sector wedges (helpful for visual checks).
    5. Unselected depots (open grey stars).
    6. Customers, coloured by their ``depot_linkId``.
    7. Selected depots (filled coloured stars, annotated with ``depot_id``).

    The colour palette is matplotlib's ``tab20``, applied to selected depots
    in the order they appear in ``selected_depot_link_ids``.

    Returns
    -------
    matplotlib.figure.Figure
        The constructed figure (caller may save / close it).
    """
    fig, ax = plt.subplots(figsize=figsize)

    # Layer 1 — background network.
    network_gdf_cropped.plot(ax=ax, color="lightgrey", linewidth=0.4)

    # Layer 2 — ring-area outline.
    ring_area.boundary.plot(ax=ax, color="black", linewidth=0.6)

    # Layer 3 — optional candidate POIs.
    if show_pois and pois_gdf is not None and len(pois_gdf) > 0:
        pois_gdf.plot(ax=ax, color="lightblue", markersize=1, alpha=0.3)

    # Pull geometric metadata stored on depots_gdf.attrs.
    center = depots_gdf.attrs.get("center")
    ring_radii = depots_gdf.attrs.get("ring_radii", [])
    sectors_deg = depots_gdf.attrs.get("sectors_deg", ())
    R_max = depots_gdf.attrs.get("R_max", max(ring_radii) if ring_radii else 0.0)

    # Layer 4a — ring circles.
    if show_rings and center is not None and ring_radii:
        for r in ring_radii:
            circ = mpatches.Circle(center, r, fill=False, linestyle="--",
                                   linewidth=0.4, edgecolor="gray", alpha=0.6)
            ax.add_patch(circ)

    # Layer 4b — sector wedges (drawn in math angles).
    if show_sectors and center is not None and sectors_deg and R_max > 0:
        for c1, c2 in sectors_deg:
            m1, m2 = _compass_range_to_math_range(c1, c2)
            wedge = mpatches.Wedge(center, R_max, m1, m2,
                                   facecolor="gold", alpha=0.06,
                                   edgecolor="goldenrod", linewidth=0.4)
            ax.add_patch(wedge)

    # Build a colour mapping for the *selected* depots, in input order.
    sel_ids: List[str] = list(selected_depot_link_ids or [])
    cmap = plt.get_cmap("tab20")
    color_for_link: dict = {lid: cmap(i % 20) for i, lid in enumerate(sel_ids)}

    sel_mask = depots_gdf["matched_link_id"].astype(str).isin(sel_ids)
    sel_depots = depots_gdf[sel_mask]
    other_depots = depots_gdf[~sel_mask]

    # Layer 5 — unselected depots.
    if len(other_depots) > 0:
        ax.scatter(other_depots["snap_x"], other_depots["snap_y"],
                   marker="*", s=80, facecolors="none",
                   edgecolors="gray", linewidths=0.5, zorder=5)

    # Layer 6 — customers, coloured by depot.
    if customers_gdf is not None and len(customers_gdf) > 0 and "depot_linkId" in customers_gdf.columns:
        for lid, group in customers_gdf.groupby("depot_linkId"):
            colour = color_for_link.get(str(lid), "red")
            ax.scatter([g.x for g in group.geometry],
                       [g.y for g in group.geometry],
                       color=[colour], s=22, edgecolor="black", linewidth=0.3,
                       label=f"{lid} (n={len(group)})", zorder=7)

    # Layer 7 — selected depots on top.
    for _, row in sel_depots.iterrows():
        colour = color_for_link.get(str(row["matched_link_id"]), "red")
        ax.scatter(row["snap_x"], row["snap_y"], marker="*", s=320,
                   color=[colour], edgecolor="black", linewidth=1.0, zorder=10)
        ax.annotate(row["depot_id"], (row["snap_x"], row["snap_y"]),
                    fontsize=8, ha="center", va="bottom",
                    xytext=(0, 10), textcoords="offset points", zorder=11)

    if title:
        ax.set_title(title, fontsize=10)
    ax.set_xlabel("X (m, EPSG:31370)")
    ax.set_ylabel("Y (m, EPSG:31370)")
    ax.set_aspect("equal")
    if customers_gdf is not None and "depot_linkId" in (customers_gdf.columns if customers_gdf is not None else []):
        ax.legend(loc="upper right", fontsize=7, framealpha=0.8)
    fig.tight_layout()
    return fig


# ===========================================================================
# 10. Master orchestrator — the two-call workflow
# ===========================================================================

def run_instance(
    *,
    instance_id: int | str,
    out_dir: str | os.PathLike,
    n_customers: int = 60,
    target_nni: float = 0.8,
    depot_link_ids: Optional[Sequence[str]] = None,
    seed_customers: int = 42,
    seed_depots: int = 42,
    seed_assignment: int = 42,
    assignment_mode: str = "roundrobin",
    nni_tolerance: float = 0.05,
    nni_max_iter: int = 500,
    n_rings: int = 10,
    inner_frac: float = 0.1,
    sectors_deg: Sequence[Tuple[float, float]] = DEFAULT_SECTORS_DEG,
    sector_names: Sequence[str] = DEFAULT_SECTOR_NAMES,
    show_plot: bool = True,
    save_plot: bool = True,
    poi_path: Optional[str] = None,
    boundary_path: Optional[str] = None,
    cropped_network_path: Optional[str] = None,
) -> dict:
    """One-stop orchestrator for a single demand-generation instance.

    Two-call workflow
    -----------------

    **Call 1** — ``depot_link_ids=None``:
        Generates the full ``n_rings × len(sectors_deg)`` depot set, writes
        ``depots{instance_id}.csv.gz``, prints the table, and returns. **No
        customer sampling / no demand CSV.** Use this to inspect the
        candidate depots.

    **Call 2** — ``depot_link_ids=[...]``:
        Re-generates the same depots (same ``seed_depots``), then samples
        customers under the target NNI, snaps them to the cropped network,
        assigns them to the supplied depots, writes
        ``demand{instance_id}.csv.gz``, and renders the plot.

    Parameters
    ----------
    instance_id : int or str
        Used in the output filenames.
    out_dir : path-like
        Output directory; created if missing.
    n_customers : int
    target_nni : float
        Target Nearest-Neighbour Index for the customer sample.
    depot_link_ids : list[str], optional
        If ``None`` (default), only the depot generation step runs.
    seed_customers, seed_depots, seed_assignment : int
        Independent seeds for the three randomised steps.
    assignment_mode : {'roundrobin', 'nearest_balanced'}
    nni_tolerance, nni_max_iter : float, int
        Forwarded to :func:`sample_customers_with_target_nni`.
    n_rings, inner_frac, sectors_deg, sector_names :
        Forwarded to :func:`generate_all_ring_sector_depots`.
    show_plot, save_plot : bool
    poi_path, boundary_path, cropped_network_path : str, optional
        Path overrides. Default to the module-level constants.

    Returns
    -------
    dict
        Keys: ``depots_csv``, ``demand_csv``, ``plot_path``, ``n_customers``,
        ``achieved_nni``, ``nni_stats``, ``depot_link_ids_used``, ``depots_df``.
        Entries that don't apply to the current call (e.g. ``demand_csv`` on
        Call 1) are ``None``.
    """
    out_dir_p = pathlib.Path(out_dir)
    out_dir_p.mkdir(parents=True, exist_ok=True)

    # ---- 10.1. Load inputs -------------------------------------------------------
    network_gdf_cropped = load_cropped_network(
        cropped_network_path or DEFAULT_CROPPED_NETWORK_PATH
    )
    ring_area = load_leuven_ring_area(boundary_path or DEFAULT_BOUNDARY_PATH)

    # ---- 10.2. Generate all candidate depots & save the sidecar -----------------
    depots_gdf = generate_all_ring_sector_depots(
        network_gdf_cropped,
        n_rings=n_rings,
        inner_frac=inner_frac,
        sectors_deg=sectors_deg,
        sector_names=sector_names,
        seed_depots=seed_depots,
    )
    depots_csv_path = out_dir_p / f"depots{instance_id}.csv.gz"
    save_depots_csv(depots_gdf, depots_csv_path)
    logger.info(
        "Generated %d depot candidates and wrote %s.", len(depots_gdf), depots_csv_path
    )

    # ---- 10.3. Call-1 short-circuit ---------------------------------------------
    if depot_link_ids is None:
        preview_cols = ["depot_id", "ring_idx", "sector_name",
                        "ring_radius_m", "matched_link_id"]
        print("=" * 78)
        print(f"[Call 1] Wrote depot sidecar: {depots_csv_path}")
        print(f"Generated {len(depots_gdf)} candidate depots "
              f"({n_rings} rings × {len(sectors_deg)} sectors).")
        print()
        # `to_string()` keeps everything on screen even for 40 rows.
        print(depots_gdf[preview_cols].to_string(index=False))
        print()
        print("To proceed, call run_instance(...) again with the chosen subset:")
        print("    depot_link_ids=['<link_id_1>', '<link_id_2>', ...]")
        print("=" * 78)
        return {
            "depots_csv": str(depots_csv_path),
            "demand_csv": None,
            "plot_path": None,
            "n_customers": 0,
            "achieved_nni": None,
            "nni_stats": None,
            "depot_link_ids_used": None,
            "depots_df": depots_gdf,
        }

    # ---- 10.4. Customer sampling -------------------------------------------------
    pois_in_ring = load_retail_pois_in_ring(
        poi_path or DEFAULT_POI_PATH, ring_area
    )
    if len(pois_in_ring) == 0:
        raise RuntimeError(
            "No retail POIs found inside the Leuven Ring Area — check the "
            "POI source path and CRS."
        )

    # Use the unary union of the ring polygons as the study area for NNI.
    try:
        study_geom = ring_area.geometry.union_all()
    except AttributeError:
        study_geom = ring_area.geometry.unary_union

    customers_gdf, nni_stats = sample_customers_with_target_nni(
        pois_in_ring,
        study_area_geom=study_geom,
        n_customers=n_customers,
        target_nni=target_nni,
        tolerance=nni_tolerance,
        max_iter=nni_max_iter,
        seed_customers=seed_customers,
    )
    logger.info(
        "Sampled %d customers; achieved NNI = %.3f (target %.3f, tol %.3f, iters %d).",
        len(customers_gdf), nni_stats["nni"], target_nni, nni_tolerance,
        nni_stats["iterations"],
    )

    # ---- 10.5. Snap customers to the cropped network ----------------------------
    customers_matched = match_points_to_links(
        customers_gdf, network_gdf_cropped, link_id_col="link_id"
    )

    # ---- 10.6. Assign customers to the user-chosen depots -----------------------
    depot_link_ids = [str(x) for x in depot_link_ids]
    if assignment_mode == "nearest_balanced":
        # Look up depot snap coordinates from the freshly generated table.
        lookup = {str(row["matched_link_id"]): (row["snap_x"], row["snap_y"])
                  for _, row in depots_gdf.iterrows()}
        try:
            depot_geoms = [lookup[lid] for lid in depot_link_ids]
        except KeyError as exc:
            raise ValueError(
                f"depot_link_id {exc.args[0]!r} is not present in the generated "
                f"depot set. Run Call 1 first to inspect available link_ids."
            ) from exc
    else:
        depot_geoms = None

    customers_assigned = assign_customers_to_depots(
        customers_matched,
        depot_link_ids=depot_link_ids,
        mode=assignment_mode,
        depot_geoms=depot_geoms,
        seed_assignment=seed_assignment,
    )

    # ---- 10.7. Write demand CSV -------------------------------------------------
    demand_csv_path = out_dir_p / f"demand{instance_id}.csv.gz"
    save_demand_csv(customers_assigned, demand_csv_path)
    logger.info("Wrote demand CSV: %s", demand_csv_path)

    # ---- 10.8. Plot -------------------------------------------------------------
    plot_path: Optional[str] = None
    if show_plot or save_plot:
        title = (
            f"instance={instance_id} | n_customers={n_customers} | "
            f"target_nni={target_nni:.2f} → achieved={nni_stats['nni']:.3f} | "
            f"seeds c/d/a = {seed_customers}/{seed_depots}/{seed_assignment} | "
            f"depots used = {len(depot_link_ids)}/{len(depots_gdf)}"
        )
        fig = plot_instance(
            network_gdf_cropped=network_gdf_cropped,
            ring_area=ring_area,
            customers_gdf=customers_assigned,
            depots_gdf=depots_gdf,
            selected_depot_link_ids=depot_link_ids,
            pois_gdf=pois_in_ring,
            show_pois=False,
            title=title,
        )
        if save_plot:
            plot_path = str(out_dir_p / f"instance{instance_id}.png")
            fig.savefig(plot_path, dpi=150, bbox_inches="tight")
        if show_plot:
            plt.show()
        else:
            plt.close(fig)

    return {
        "depots_csv": str(depots_csv_path),
        "demand_csv": str(demand_csv_path),
        "plot_path": plot_path,
        "n_customers": int(len(customers_assigned)),
        "achieved_nni": float(nni_stats["nni"]),
        "nni_stats": nni_stats,
        "depot_link_ids_used": list(depot_link_ids),
        "depots_df": depots_gdf,
    }


def run_instances(specs: Iterable[dict]) -> List[dict]:
    """Run several ``run_instance`` calls in sequence.

    Each spec is a ``dict`` of keyword arguments forwarded verbatim to
    :func:`run_instance`. Useful for sweeping seeds or target NNIs::

        results = run_instances([
            dict(instance_id=i, out_dir="data/randomDemand60Receivers_nni/nni08",
                 target_nni=0.8, n_customers=60,
                 depot_link_ids=picked,
                 seed_customers=42+i, seed_depots=42, seed_assignment=42+i,
                 show_plot=False)
            for i in range(10)
        ])

    Returns
    -------
    list[dict]
        The :func:`run_instance` result dicts in input order.
    """
    return [run_instance(**spec) for spec in specs]


def run_example_call_one(path_out=r"data/randomDemand20Receivers_nni"):
    """Example Call 1: generate candidate depots only.
    # Example: Call 1 only — list candidate depots, no customers yet.
    # The user should then pick a subset of matched_link_id values and re-run
    # with depot_link_ids=[...] for Call 2.
    """
    result = run_instance(
        instance_id=0,
        out_dir=path_out,
        seed_depots=42,
    )
    return result

def run_example_call_two(path_out=r"data/randomDemand20Receivers_nni"):
    """Example Call 2: generate customers for a chosen subset of depots.

    The user should first run Call 1 to get the list of candidate depots, then
    pick some (e.g. 1-3) by their ``matched_link_id`` and pass them as
    ``depot_link_ids=[...]`` here.
    """ 

    picked_depots = ["3991683_6"]
    result = run_instance(
        instance_id=0,
        out_dir=path_out,
        n_customers=20,
        target_nni=0.8,
        depot_link_ids=picked_depots,
        seed_depots=42,  # must match Call 1 to get the same depots
        seed_customers=42,
        seed_assignment=42,
        assignment_mode="roundrobin",
        nni_tolerance=0.05,
        nni_max_iter=500,
        n_rings=10,
        inner_frac=0.1,
        sectors_deg=DEFAULT_SECTORS_DEG,
        sector_names=DEFAULT_SECTOR_NAMES,
        show_plot=True,
        save_plot=True,
    )
    return result

# ===========================================================================
# 11. Optional: a minimal usage example when run as a script
# ===========================================================================

if __name__ == "__main__":
    
    path_out = r"data/randomDemand15Receivers_nni"
    seed_depots = 42  # fixed seed to get the same depots across runs

    ''' Check if there is depot CSV already, if not run Call 1 to generate depots. '''
    depot_csv_path = pathlib.Path(path_out) / "depots0.csv.gz"
    if not depot_csv_path.exists():
        logger.info("Depot CSV not found at %s. Running Call 1 to generate depots.",
                    depot_csv_path)
        run_example_call_one(path_out=path_out)
    else:
        logger.info("Found existing depot CSV at %s. Skipping Call 1.", depot_csv_path)

    ''' Run and generate 10 instances of customer distributions with different seeds across 40 depots, for a small NNI (0.8). '''
    # Read the depot csv
    depots_df = pd.read_csv(path_out + "/depots0.csv.gz")

    # Outer bar: one tick per depot, stays visible the whole run.
    outer = tqdm(depots_df.iterrows(), total=len(depots_df),
                 desc="depots", unit="depot")
    for idx, row in outer:
        depot_id = row["depot_id"]
        depot_link_id = row["matched_link_id"]
        outer.set_postfix(depot=depot_id)

        # Inner bar: advances ONLY when an instance succeeds, so the bar
        # reaches 10/10 iff we actually wrote 10 valid demand CSVs for this
        # depot. `leave=False` auto-clears the bar when this depot finishes.
        N_VALID = 10           # how many valid instances we want per depot
        MAX_ATTEMPTS = 100     # safety cap to avoid an infinite retry loop
        success = 0            # number of valid instances written so far
        attempt = 0            # total attempts (success + retries)
        inner = tqdm(total=N_VALID, desc=f"{depot_id}", unit="inst", leave=False)
        while success < N_VALID and attempt < MAX_ATTEMPTS:
            # Use a fresh seed for every attempt so retries explore new POI samples.
            # A monotonically increasing offset ensures no seed is reused across
            # attempts within the same depot.
            seed_customers = seed_depots + attempt + 1
            instance_id = f"{success}"           # 0..9 across SUCCESSFUL runs
            output_folder = f"{path_out}/{depot_id}/ins{success}"
            attempt += 1
            try:
                result = run_instance(
                    instance_id=instance_id,
                    out_dir=output_folder,
                    n_customers=15,
                    target_nni=0.8,
                    depot_link_ids=[depot_link_id],
                    seed_depots=seed_depots,  # must match Call 1 to get the same depots
                    seed_customers=seed_customers,
                    seed_assignment=seed_customers,  # align assignment seed with customer seed for consistency
                    assignment_mode="roundrobin",
                    nni_tolerance=0.1,
                    nni_max_iter=500,
                    n_rings=10,
                    inner_frac=0.1,
                    sectors_deg=DEFAULT_SECTORS_DEG,
                    sector_names=DEFAULT_SECTOR_NAMES,
                    show_plot=False,  # Set to True if you want to see the plots
                    save_plot=True,
                )
                success += 1
                inner.update(1)
                inner.set_postfix(nni=f"{result['achieved_nni']:.3f}",
                                  attempts=attempt)
            except ValueError as e:
                # Infeasible NNI for this seed — retry with a different one.
                logger.info("retrying %s ins%d (seed=%d failed: %s)",
                            depot_id, success, seed_customers, e)
                inner.set_postfix(retry=f"seed={seed_customers}",
                                  attempts=attempt)
        inner.close()
        if success < N_VALID:
            logger.warning(
                "depot %s: only %d/%d valid instances after %d attempts",
                depot_id, success, N_VALID, MAX_ATTEMPTS,
            )
