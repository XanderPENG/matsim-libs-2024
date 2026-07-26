"""
Generic, DataFrame-oriented readers for MATSim freight output.

The public readers accept either ``.xml`` or ``.xml.gz`` files.  The individual
XML readers return dictionaries whose values are pandas DataFrames, so both the
normalised tables and the original MATSim attributes remain available.

The event reader deliberately uses :func:`matsim.event_reader`.  A convenient
high-level call is::

    result = read_freight_events(
        "output_events.xml.gz",
        network="output_network.xml.gz",
    )
    result["travel_chains"]     # every freight-vehicle event, in order
    result["link_traversals"]   # one row per traversed link
    result["vehicle_summary"]   # VKT, transit time, tour time, ...

For a complete output directory, use :func:`read_scenario_output`.
Use :func:`discover_output_scenarios` to index a parent folder containing many
scenario directories without loading all event files into memory.
"""

from __future__ import annotations

import gzip
import re
from pathlib import Path
from typing import Any, Iterable, Mapping, Optional, Sequence, Union

import pandas as pd
from lxml import etree

try:
    import matsim
except ImportError as exc:  # pragma: no cover - depends on the user's environment
    matsim = None
    _MATSIM_IMPORT_ERROR = exc
else:
    _MATSIM_IMPORT_ERROR = None


PathLike = Union[str, Path]
XmlTables = dict[str, pd.DataFrame]

_TRUE_VALUES = {"true", "yes", "1"}
_FALSE_VALUES = {"false", "no", "0"}
_ID_LIKE_NAMES = {
    "id",
    "person",
    "vehicle",
    "link",
    "from",
    "to",
    "type",
    "acttype",
    "legmode",
    "networkmode",
    "modes",
    "oneway",
    "class",
    "name",
    "role",
}
_NUMERIC_EVENT_COLUMNS = {
    "time",
    "relativePosition",
    "capacityDemand",
    "pickupDuration",
    "dropoffDuration",
    "amount",
    "delay",
    "distance",
}
_MOVEMENT_EVENT_TYPES = {
    "entered link",
    "left link",
    "vehicle enters traffic",
    "vehicle leaves traffic",
}
_TOUR_START = "freight tour starts"
_TOUR_END = "freight tour ends"


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------


def _require_matsim_tools() -> Any:
    if matsim is None or not hasattr(matsim, "event_reader"):
        detail = (
            f" Original import error: {_MATSIM_IMPORT_ERROR}"
            if _MATSIM_IMPORT_ERROR is not None
            else ""
        )
        raise ImportError(
            "The Python package 'matsim-tools' is required. The imported "
            "'matsim' module does not expose event_reader/read_network."
            f"{detail}"
        )
    return matsim


def _local_name(tag: str) -> str:
    """Return an XML tag without its namespace."""
    return tag.rsplit("}", 1)[-1]


def _direct_children(element: Optional[etree._Element], name: str) -> list[etree._Element]:
    if element is None:
        return []
    return [child for child in element if _local_name(child.tag) == name]


def _direct_child(
    element: Optional[etree._Element], name: str
) -> Optional[etree._Element]:
    children = _direct_children(element, name)
    return children[0] if children else None


def _descendants(element: etree._Element, name: str) -> list[etree._Element]:
    return [item for item in element.iter() if _local_name(item.tag) == name]


def _open_xml(path: PathLike) -> etree._ElementTree:
    file_path = Path(path).expanduser()
    if not file_path.is_file():
        raise FileNotFoundError(f"MATSim XML file not found: {file_path}")

    parser = etree.XMLParser(
        load_dtd=False,
        no_network=True,
        resolve_entities=False,
        huge_tree=True,
        remove_comments=True,
    )
    if file_path.suffix.lower() == ".gz":
        with gzip.open(file_path, "rb") as stream:
            return etree.parse(stream, parser)
    return etree.parse(str(file_path), parser)


def _is_id_like(name: str) -> bool:
    lower = name.lower()
    return lower in _ID_LIKE_NAMES or lower.endswith("id") or lower.endswith("_id")


def _coerce_scalar(
    value: Any, *, name: str = "", java_class: Optional[str] = None
) -> Any:
    """Convert booleans and numbers while protecting identifiers and time strings."""
    if value is None or not isinstance(value, str):
        return value
    text = value.strip()
    if text == "":
        return ""

    java_type = (java_class or "").rsplit(".", 1)[-1].lower()
    if java_type == "boolean":
        return text.lower() in _TRUE_VALUES
    if java_type in {"integer", "long", "short", "byte"}:
        try:
            return int(text)
        except ValueError:
            return text
    if java_type in {"double", "float", "bigdecimal"}:
        try:
            return float(text)
        except ValueError:
            return text

    lower = text.lower()
    if lower in _TRUE_VALUES | _FALSE_VALUES and not _is_id_like(name):
        return lower in _TRUE_VALUES
    if _is_id_like(name) or ":" in text:
        return text
    try:
        number = float(text)
    except ValueError:
        return text
    return int(number) if number.is_integer() and "." not in text.lower() else number


def _coerce_attributes(attributes: Mapping[str, Any]) -> dict[str, Any]:
    return {
        _local_name(key): _coerce_scalar(value, name=_local_name(key))
        for key, value in attributes.items()
    }


def _time_to_seconds(value: Any) -> Optional[float]:
    """Convert MATSim numeric or HH:MM[:SS] time to seconds."""
    if value is None or value is pd.NA:
        return None
    if isinstance(value, (int, float)) and not pd.isna(value):
        return float(value)
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        pass
    parts = text.split(":")
    try:
        if len(parts) == 3:
            hours, minutes, seconds = map(float, parts)
            return hours * 3600.0 + minutes * 60.0 + seconds
        if len(parts) == 2:
            minutes, seconds = map(float, parts)
            return minutes * 60.0 + seconds
    except ValueError:
        return None
    return None


def _is_selected(value: Any) -> bool:
    return str(value).strip().lower() in _TRUE_VALUES


def _first_non_null(series: pd.Series, default: Any = None) -> Any:
    valid = series.dropna()
    return valid.iloc[0] if not valid.empty else default


def _sum_numeric_or_nan(series: pd.Series) -> float:
    """Sum known numeric values, retaining NaN when no value is available."""
    return pd.to_numeric(series, errors="coerce").sum(min_count=1)


def _records_frame(
    records: Sequence[Mapping[str, Any]], columns: Optional[Sequence[str]] = None
) -> pd.DataFrame:
    if records:
        frame = pd.DataFrame.from_records(records)
        if columns:
            leading = [column for column in columns if column in frame.columns]
            frame = frame[leading + [c for c in frame.columns if c not in leading]]
        return frame
    return pd.DataFrame(columns=list(columns or []))


def _java_attributes(
    parent: etree._Element,
    *,
    context: str,
    owner_fields: Mapping[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Read a direct ``<attributes>`` container in both wide and long form."""
    values: dict[str, Any] = {}
    rows: list[dict[str, Any]] = []
    container = _direct_child(parent, "attributes")
    for attribute in _direct_children(container, "attribute"):
        name = attribute.get("name")
        if not name:
            continue
        java_class = attribute.get("class")
        value = _coerce_scalar(
            (attribute.text or "").strip(), name=name, java_class=java_class
        )
        values[name] = value
        rows.append(
            {
                **owner_fields,
                "context": context,
                "name": name,
                "java_class": java_class,
                "value": value,
            }
        )
    return values, rows


def _add_time_seconds(record: dict[str, Any], fields: Iterable[str]) -> None:
    for field in fields:
        if field in record:
            record[f"{field}_s"] = _time_to_seconds(record[field])


def _resolve_file(
    path: PathLike, candidate_names: Sequence[str], *, description: str
) -> Path:
    value = Path(path).expanduser()
    if value.is_file():
        return value
    if not value.is_dir():
        raise FileNotFoundError(f"{description} path not found: {value}")
    for name in candidate_names:
        candidate = value / name
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(
        f"No {description} file found in {value}; tried: "
        + ", ".join(candidate_names)
    )


# ---------------------------------------------------------------------------
# region A. Events and travel chains
# ---------------------------------------------------------------------------


def read_events(
    events_path: PathLike,
    event_types: Optional[Union[str, Iterable[str]]] = None,
    *,
    freight_only: bool = False,
    freight_vehicle_ids: Optional[Iterable[str]] = None,
    freight_id_pattern: Optional[str] = r"freight",
) -> pd.DataFrame:
    """Read a MATSim events file into a detailed DataFrame.

    All original event attributes are retained. ``event_index`` preserves file
    order (or filtered-reader order when ``event_types`` is supplied), and
    ``vehicle_id`` fills freight person events that do not carry ``vehicle``.

    Freight filtering first learns vehicle IDs from ``Freight ...`` events, so
    it does not rely only on a particular vehicle-ID naming convention.
    """
    matsim_tools = _require_matsim_tools()
    file_path = Path(events_path).expanduser()
    if not file_path.is_file():
        raise FileNotFoundError(f"MATSim events file not found: {file_path}")

    known_freight_ids = {str(item) for item in (freight_vehicle_ids or [])}
    if freight_only:
        # MATSim events can contain millions of passenger events.  A cheap first
        # streaming pass learns all freight vehicle IDs; the second pass stores
        # only relevant rows.  This trades XML parse time for bounded memory.
        known_freight_ids.update(
            _discover_freight_vehicle_ids(
                matsim_tools,
                file_path,
                freight_id_pattern=freight_id_pattern,
            )
        )

    records: list[dict[str, Any]] = []
    for event_index, event in enumerate(
        matsim_tools.event_reader(str(file_path), types=event_types)
    ):
        if freight_only and not _event_matches_freight(
            event,
            known_freight_ids=known_freight_ids,
            freight_id_pattern=freight_id_pattern,
        ):
            continue
        record = dict(event)
        record["event_index"] = event_index
        records.append(record)

    events = _records_frame(records, ["event_index", "time", "type"])
    if events.empty:
        events["vehicle_id"] = pd.Series(dtype="object")
        return events

    for column in _NUMERIC_EVENT_COLUMNS & set(events.columns):
        events[column] = pd.to_numeric(events[column], errors="coerce")

    events = _identify_freight_vehicle_events(
        events,
        freight_vehicle_ids=known_freight_ids,
        freight_id_pattern=freight_id_pattern,
        filter_rows=freight_only,
    )
    return events.sort_values(["time", "event_index"], kind="stable").reset_index(drop=True)


def _discover_freight_vehicle_ids(
    matsim_tools: Any,
    file_path: Path,
    *,
    freight_id_pattern: Optional[str],
) -> set[str]:
    known_ids: set[str] = set()
    expression = (
        re.compile(freight_id_pattern, flags=re.IGNORECASE)
        if freight_id_pattern
        else None
    )
    for event in matsim_tools.event_reader(str(file_path)):
        event_type = str(event.get("type", ""))
        vehicle = event.get("vehicle")
        person = event.get("person")
        is_freight_type = event_type.lower().startswith("freight ")
        vehicle_matches = (
            expression.search(str(vehicle)) is not None
            if expression is not None and vehicle is not None
            else False
        )
        person_matches = (
            expression.search(str(person)) is not None
            if expression is not None and person is not None
            else False
        )
        if vehicle is not None and (is_freight_type or vehicle_matches):
            known_ids.add(str(vehicle))
        if person is not None and person_matches:
            known_ids.add(str(person))
    return known_ids


def _event_matches_freight(
    event: Mapping[str, Any],
    *,
    known_freight_ids: set[str],
    freight_id_pattern: Optional[str],
) -> bool:
    event_type = str(event.get("type", ""))
    if event_type.lower().startswith("freight "):
        return True
    vehicle = event.get("vehicle")
    person = event.get("person")
    if (
        vehicle is not None
        and str(vehicle) in known_freight_ids
        or person is not None
        and str(person) in known_freight_ids
    ):
        return True
    if freight_id_pattern:
        return (
            vehicle is not None
            and re.search(freight_id_pattern, str(vehicle), flags=re.IGNORECASE)
            is not None
        ) or (
            person is not None
            and re.search(freight_id_pattern, str(person), flags=re.IGNORECASE)
            is not None
        )
    return False


def _identify_freight_vehicle_events(
    events: pd.DataFrame,
    *,
    freight_vehicle_ids: Optional[Iterable[str]],
    freight_id_pattern: Optional[str],
    filter_rows: bool,
) -> pd.DataFrame:
    result = events.copy()
    types = result.get("type", pd.Series("", index=result.index)).fillna("").astype(str)
    freight_event_mask = types.str.lower().str.startswith("freight ")

    known_ids = {str(item) for item in (freight_vehicle_ids or [])}
    if "vehicle" in result:
        known_ids.update(
            result.loc[freight_event_mask, "vehicle"].dropna().astype(str).tolist()
        )

    vehicle = result.get("vehicle", pd.Series(pd.NA, index=result.index, dtype="object"))
    person = result.get("person", pd.Series(pd.NA, index=result.index, dtype="object"))
    vehicle_text = vehicle.fillna("").astype(str)
    person_text = person.fillna("").astype(str)

    named_mask = pd.Series(False, index=result.index)
    if freight_id_pattern:
        named_mask = vehicle_text.str.contains(
            freight_id_pattern, case=False, regex=True, na=False
        ) | person_text.str.contains(
            freight_id_pattern, case=False, regex=True, na=False
        )
        known_ids.update(vehicle_text[named_mask & vehicle.notna()].tolist())
        known_ids.update(person_text[named_mask & person.notna()].tolist())

    known_mask = vehicle_text.isin(known_ids) | person_text.isin(known_ids)
    freight_mask = freight_event_mask | named_mask | known_mask

    result["is_freight_event"] = freight_mask
    inferred_vehicle = vehicle.where(vehicle.notna(), person.where(person_text.isin(known_ids)))
    result["vehicle_id"] = inferred_vehicle
    if filter_rows:
        result = result.loc[freight_mask].copy()
    return result


def build_vehicle_travel_chains(
    events: Union[PathLike, pd.DataFrame],
    *,
    freight_only: bool = True,
    freight_vehicle_ids: Optional[Iterable[str]] = None,
    freight_id_pattern: Optional[str] = r"freight",
    network: Optional[Union[PathLike, pd.DataFrame, Mapping[str, pd.DataFrame]]] = None,
) -> pd.DataFrame:
    """Build a long-format, event-by-event travel chain for each vehicle."""
    if isinstance(events, (str, Path)):
        event_frame = read_events(
            events,
            freight_only=freight_only,
            freight_vehicle_ids=freight_vehicle_ids,
            freight_id_pattern=freight_id_pattern,
        )
    else:
        event_frame = events.copy()
        if "event_index" not in event_frame:
            event_frame["event_index"] = range(len(event_frame))
        if "vehicle_id" not in event_frame or "is_freight_event" not in event_frame:
            event_frame = _identify_freight_vehicle_events(
                event_frame,
                freight_vehicle_ids=freight_vehicle_ids,
                freight_id_pattern=freight_id_pattern,
                filter_rows=freight_only,
            )
        elif freight_only:
            event_frame = event_frame.loc[event_frame["is_freight_event"]].copy()

    if event_frame.empty:
        return _records_frame(
            [],
            [
                "vehicle_id",
                "chain_position",
                "event_index",
                "time",
                "type",
                "link",
                "actType",
            ],
        )

    chains = event_frame.loc[event_frame["vehicle_id"].notna()].copy()
    chains = chains.sort_values(
        ["vehicle_id", "time", "event_index"], kind="stable"
    ).reset_index(drop=True)
    chains["chain_position"] = chains.groupby("vehicle_id", sort=False).cumcount()
    chains["elapsed_since_previous_event_s"] = chains.groupby(
        "vehicle_id", sort=False
    )["time"].diff()

    raw_type = chains["type"].fillna("").astype(str)
    lower_type = raw_type.str.lower()
    raw_act_type = chains.get(
        "actType", pd.Series(pd.NA, index=chains.index, dtype="object")
    )
    chains["act_type"] = raw_act_type
    chains.loc[
        chains["act_type"].isna()
        & lower_type.str.startswith("freight shipment pickup"),
        "act_type",
    ] = "pickup"
    chains.loc[
        chains["act_type"].isna()
        & lower_type.str.startswith("freight shipment delivered"),
        "act_type",
    ] = "delivery"
    chains.loc[
        chains["act_type"].isna() & lower_type.eq(_TOUR_START), "act_type"
    ] = "tour_start"
    chains.loc[
        chains["act_type"].isna() & lower_type.eq(_TOUR_END), "act_type"
    ] = "tour_end"
    chains["event_category"] = "other"
    chains.loc[lower_type.isin(_MOVEMENT_EVENT_TYPES), "event_category"] = "movement"
    chains.loc[
        lower_type.isin({"actstart", "actend"}) | raw_act_type.notna(),
        "event_category",
    ] = "activity"
    chains.loc[lower_type.str.startswith("freight shipment"), "event_category"] = (
        "shipment"
    )
    chains.loc[lower_type.str.startswith("freight tour"), "event_category"] = "tour"
    chains.loc[
        lower_type.str.startswith("personentersvehicle")
        | lower_type.str.startswith("personleavesvehicle"),
        "event_category",
    ] = "vehicle_occupancy"

    chains["tour_id"] = pd.NA
    chains["tour_sequence"] = pd.Series(pd.NA, index=chains.index, dtype="Int64")
    for _, indices in chains.groupby("vehicle_id", sort=False).groups.items():
        active_tour: Optional[str] = None
        tour_sequence = 0
        seen_indices: list[int] = []
        for index in indices:
            event_type = lower_type.loc[index]
            raw_tour_id = chains.at[index, "tourId"] if "tourId" in chains else None
            if event_type == _TOUR_START:
                tour_sequence += 1
                active_tour = (
                    str(raw_tour_id)
                    if raw_tour_id is not None and not pd.isna(raw_tour_id)
                    else str(tour_sequence)
                )
                # MATSim commonly emits actend/departure/vehicle-enters-traffic
                # immediately before "Freight tour starts" at the same timestamp.
                # Backfill those rows so per-tour VKT/time totals equal the vehicle
                # totals without changing event order.
                for prior_index in reversed(seen_indices):
                    if chains.at[prior_index, "time"] != chains.at[index, "time"]:
                        break
                    if pd.isna(chains.at[prior_index, "tour_id"]):
                        chains.at[prior_index, "tour_id"] = active_tour
                        chains.at[prior_index, "tour_sequence"] = tour_sequence
            if raw_tour_id is not None and not pd.isna(raw_tour_id):
                chains.at[index, "tour_id"] = str(raw_tour_id)
            elif active_tour is not None:
                chains.at[index, "tour_id"] = active_tour
            if active_tour is not None:
                chains.at[index, "tour_sequence"] = tour_sequence
            if event_type == _TOUR_END:
                active_tour = None
            seen_indices.append(index)

    chains["is_link_entry"] = False
    chains["link_sequence"] = pd.Series(pd.NA, index=chains.index, dtype="Int64")
    for _, indices in chains.groupby("vehicle_id", sort=False).groups.items():
        last_chain_link: Optional[str] = None
        link_sequence = 0
        for index in indices:
            event_type = lower_type.loc[index]
            link = chains.at[index, "link"] if "link" in chains else None
            if link is None or pd.isna(link):
                continue
            link = str(link)
            is_entry = event_type == "entered link" or (
                event_type == "vehicle enters traffic" and link != last_chain_link
            )
            if is_entry:
                chains.at[index, "is_link_entry"] = True
                chains.at[index, "link_sequence"] = link_sequence
                link_sequence += 1
                last_chain_link = link

    link_lengths = _link_length_lookup(network)
    if link_lengths is not None and "link" in chains:
        chains["link_length_m"] = chains["link"].map(link_lengths)

    leading = [
        "vehicle_id",
        "chain_position",
        "event_index",
        "time",
        "type",
        "event_category",
        "link",
        "actType",
        "act_type",
        "tour_id",
        "tour_sequence",
        "is_link_entry",
        "link_sequence",
        "elapsed_since_previous_event_s",
    ]
    return chains[[c for c in leading if c in chains] + [c for c in chains if c not in leading]]


def _link_length_lookup(
    network: Optional[Union[PathLike, pd.DataFrame, Mapping[str, pd.DataFrame]]]
) -> Optional[dict[str, float]]:
    if network is None:
        return None
    if isinstance(network, (str, Path)):
        links = read_network(network)["links"]
    elif isinstance(network, pd.DataFrame):
        links = network
    else:
        links = network.get("links")
        if links is None:
            raise ValueError("Network mapping must contain a 'links' DataFrame")
    if "link_id" not in links or "length" not in links:
        raise ValueError("Network links must contain 'link_id' and 'length' columns")
    return dict(
        zip(links["link_id"].astype(str), pd.to_numeric(links["length"], errors="coerce"))
    )


def derive_link_traversals(
    travel_chains: pd.DataFrame,
    network: Optional[Union[PathLike, pd.DataFrame, Mapping[str, pd.DataFrame]]] = None,
) -> pd.DataFrame:
    """Return the ordered link chain used for VKT calculation.

    The chain contains every ``entered link`` event and a
    ``vehicle enters traffic`` link when it differs from the preceding chain
    link.  This matches the convention already used by this project's freight
    metrics and avoids counting the same activity link again after every stop.
    """
    if travel_chains.empty or "is_link_entry" not in travel_chains:
        return _records_frame(
            [],
            [
                "vehicle_id",
                "traversal_index",
                "time",
                "link",
                "link_length_m",
                "distance_m",
                "distance_km",
            ],
        )
    result = travel_chains.loc[travel_chains["is_link_entry"]].copy()
    result["traversal_index"] = result.groupby("vehicle_id", sort=False).cumcount()

    link_lengths = _link_length_lookup(network)
    if link_lengths is not None:
        result["link_length_m"] = result["link"].map(link_lengths)
    if "link_length_m" in result:
        result["distance_m"] = pd.to_numeric(
            result["link_length_m"], errors="coerce"
        )
        result["distance_km"] = result["distance_m"] / 1000.0
    else:
        result["distance_m"] = pd.NA
        result["distance_km"] = pd.NA

    leading = [
        "vehicle_id",
        "traversal_index",
        "tour_id",
        "event_index",
        "time",
        "type",
        "link",
        "link_length_m",
        "distance_m",
        "distance_km",
    ]
    return result[[c for c in leading if c in result] + [c for c in result if c not in leading]]


def derive_traffic_legs(travel_chains: pd.DataFrame) -> pd.DataFrame:
    """Pair vehicle-enters/leaves-traffic events and calculate transit time."""
    rows: list[dict[str, Any]] = []
    if travel_chains.empty:
        return _records_frame(
            rows,
            [
                "vehicle_id",
                "traffic_leg_index",
                "departure_time",
                "arrival_time",
                "transit_time_s",
            ],
        )

    for vehicle_id, vehicle_events in travel_chains.groupby("vehicle_id", sort=False):
        active: Optional[pd.Series] = None
        leg_index = 0
        for _, event in vehicle_events.iterrows():
            event_type = str(event.get("type", "")).lower()
            if event_type == "vehicle enters traffic":
                if active is not None:
                    rows.append(
                        _traffic_leg_record(
                            vehicle_id, leg_index, active, None
                        )
                    )
                    leg_index += 1
                active = event
            elif event_type == "vehicle leaves traffic" and active is not None:
                rows.append(
                    _traffic_leg_record(vehicle_id, leg_index, active, event)
                )
                leg_index += 1
                active = None
        if active is not None:
            rows.append(_traffic_leg_record(vehicle_id, leg_index, active, None))
    return _records_frame(
        rows,
        [
            "vehicle_id",
            "traffic_leg_index",
            "tour_id",
            "departure_time",
            "arrival_time",
            "transit_time_s",
            "from_link",
            "to_link",
            "is_complete",
        ],
    )


def _traffic_leg_record(
    vehicle_id: Any,
    leg_index: int,
    enter_event: pd.Series,
    leave_event: Optional[pd.Series],
) -> dict[str, Any]:
    departure = enter_event.get("time")
    arrival = leave_event.get("time") if leave_event is not None else None
    return {
        "vehicle_id": vehicle_id,
        "traffic_leg_index": leg_index,
        "tour_id": enter_event.get("tour_id"),
        "enter_event_index": enter_event.get("event_index"),
        "leave_event_index": (
            leave_event.get("event_index") if leave_event is not None else None
        ),
        "departure_time": departure,
        "arrival_time": arrival,
        "transit_time_s": (
            float(arrival) - float(departure)
            if arrival is not None and departure is not None
            else None
        ),
        "from_link": enter_event.get("link"),
        "to_link": leave_event.get("link") if leave_event is not None else None,
        "network_mode": enter_event.get("networkMode"),
        "start_relative_position": enter_event.get("relativePosition"),
        "end_relative_position": (
            leave_event.get("relativePosition") if leave_event is not None else None
        ),
        "is_complete": leave_event is not None,
    }


def derive_tour_summary(
    travel_chains: pd.DataFrame,
    link_traversals: Optional[pd.DataFrame] = None,
    traffic_legs: Optional[pd.DataFrame] = None,
) -> pd.DataFrame:
    """Summarise Freight-tour start/end pairs."""
    columns = [
        "vehicle_id",
        "tour_id",
        "carrier_id",
        "tour_start_time",
        "tour_end_time",
        "tour_duration_s",
        "transit_time_s",
        "vkt_m",
        "vkt_km",
    ]
    if travel_chains.empty or "tour_id" not in travel_chains:
        return _records_frame([], columns)

    rows: list[dict[str, Any]] = []
    grouped = travel_chains.loc[travel_chains["tour_id"].notna()].groupby(
        ["vehicle_id", "tour_id"], sort=False, dropna=False
    )
    for (vehicle_id, tour_id), group in grouped:
        lower_types = group["type"].fillna("").astype(str).str.lower()
        starts = group.loc[lower_types == _TOUR_START]
        ends = group.loc[lower_types == _TOUR_END]
        if starts.empty and ends.empty:
            continue
        start_time = starts["time"].min() if not starts.empty else None
        end_time = ends["time"].max() if not ends.empty else None
        rows.append(
            {
                "vehicle_id": vehicle_id,
                "tour_id": tour_id,
                "carrier_id": (
                    _first_non_null(group["carrierId"])
                    if "carrierId" in group
                    else None
                ),
                "tour_start_time": start_time,
                "tour_end_time": end_time,
                "tour_duration_s": (
                    float(end_time) - float(start_time)
                    if start_time is not None and end_time is not None
                    else None
                ),
                "start_link": (
                    _first_non_null(starts["link"]) if "link" in starts else None
                ),
                "end_link": (
                    _first_non_null(ends["link"]) if "link" in ends else None
                ),
                "event_count": len(group),
                "pickup_count": int(
                    lower_types.eq("freight shipment pickup starts").sum()
                ),
                "delivery_count": int(
                    lower_types.eq("freight shipment delivered starts").sum()
                ),
                "is_complete": not starts.empty and not ends.empty,
            }
        )
    tours = _records_frame(rows, columns)

    if traffic_legs is not None and not traffic_legs.empty:
        leg_totals = (
            traffic_legs.groupby(["vehicle_id", "tour_id"], dropna=False)
            .agg(
                transit_time_s=("transit_time_s", _sum_numeric_or_nan),
                traffic_leg_count=("traffic_leg_index", "count"),
            )
            .reset_index()
        )
        tours = tours.merge(leg_totals, on=["vehicle_id", "tour_id"], how="left")
    else:
        tours["transit_time_s"] = pd.NA

    if link_traversals is not None and not link_traversals.empty:
        traversal_totals = (
            link_traversals.groupby(["vehicle_id", "tour_id"], dropna=False)
            .agg(
                link_count=("link", "count"),
                vkt_m=("distance_m", _sum_numeric_or_nan),
                vkt_km=("distance_km", _sum_numeric_or_nan),
            )
            .reset_index()
        )
        tours = tours.merge(
            traversal_totals, on=["vehicle_id", "tour_id"], how="left"
        )
    else:
        tours["link_count"] = pd.NA
        tours["vkt_m"] = pd.NA
        tours["vkt_km"] = pd.NA
    return tours


def derive_shipment_summary(travel_chains: pd.DataFrame) -> pd.DataFrame:
    """Create one lifecycle row per freight shipment."""
    columns = [
        "shipment_id",
        "carrier_id",
        "vehicle_id",
        "pickup_start_time",
        "pickup_end_time",
        "delivery_start_time",
        "delivery_end_time",
        "shipment_transit_time_s",
    ]
    if travel_chains.empty or "shipmentId" not in travel_chains:
        return _records_frame([], columns)
    shipment_events = travel_chains.loc[travel_chains["shipmentId"].notna()].copy()
    rows: list[dict[str, Any]] = []
    for shipment_id, group in shipment_events.groupby("shipmentId", sort=False):
        lower_types = group["type"].fillna("").astype(str).str.lower()

        def event_time(event_type: str, method: str = "min") -> Optional[float]:
            values = group.loc[lower_types == event_type, "time"]
            if values.empty:
                return None
            return float(values.min() if method == "min" else values.max())

        pickup_start = event_time("freight shipment pickup starts")
        pickup_end = event_time("freight shipment pickup ends", "max")
        delivery_start = event_time("freight shipment delivered starts")
        delivery_end = event_time("freight shipment delivered ends", "max")
        rows.append(
            {
                "shipment_id": shipment_id,
                "carrier_id": (
                    _first_non_null(group["carrierId"])
                    if "carrierId" in group
                    else None
                ),
                "vehicle_id": _first_non_null(group["vehicle_id"]),
                "tour_id": _first_non_null(group["tour_id"]),
                "pickup_link": (
                    _first_non_null(
                        group.loc[
                            lower_types == "freight shipment pickup starts", "link"
                        ]
                    )
                    if "link" in group
                    else None
                ),
                "delivery_link": (
                    _first_non_null(
                        group.loc[
                            lower_types == "freight shipment delivered starts", "link"
                        ]
                    )
                    if "link" in group
                    else None
                ),
                "pickup_start_time": pickup_start,
                "pickup_end_time": pickup_end,
                "delivery_start_time": delivery_start,
                "delivery_end_time": delivery_end,
                "pickup_service_time_s": (
                    pickup_end - pickup_start
                    if pickup_start is not None and pickup_end is not None
                    else None
                ),
                "shipment_transit_time_s": (
                    delivery_start - pickup_end
                    if pickup_end is not None and delivery_start is not None
                    else None
                ),
                "delivery_service_time_s": (
                    delivery_end - delivery_start
                    if delivery_start is not None and delivery_end is not None
                    else None
                ),
                "fulfilment_time_s": (
                    delivery_end - pickup_start
                    if pickup_start is not None and delivery_end is not None
                    else None
                ),
                "capacity_demand": (
                    _first_non_null(group["capacityDemand"])
                    if "capacityDemand" in group
                    else None
                ),
                "is_complete": all(
                    value is not None
                    for value in [
                        pickup_start,
                        pickup_end,
                        delivery_start,
                        delivery_end,
                    ]
                ),
            }
        )
    return _records_frame(rows, columns)


def derive_vehicle_summary(
    travel_chains: pd.DataFrame,
    link_traversals: pd.DataFrame,
    traffic_legs: pd.DataFrame,
    tours: pd.DataFrame,
) -> pd.DataFrame:
    """Aggregate chain tables to one row per freight vehicle."""
    columns = [
        "vehicle_id",
        "event_count",
        "first_event_time",
        "last_event_time",
        "tour_count",
        "tour_time_s",
        "transit_time_s",
        "vkt_m",
        "vkt_km",
    ]
    if travel_chains.empty:
        return _records_frame([], columns)

    base = (
        travel_chains.groupby("vehicle_id", sort=False)
        .agg(
            event_count=("event_index", "count"),
            first_event_time=("time", "min"),
            last_event_time=("time", "max"),
        )
        .reset_index()
    )
    base["simulation_span_s"] = base["last_event_time"] - base["first_event_time"]
    event_types = travel_chains["type"].fillna("").astype(str).str.lower()
    counts = (
        travel_chains.assign(_type=event_types)
        .groupby("vehicle_id", sort=False)
        .agg(
            pickup_count=(
                "_type",
                lambda values: int(
                    (values == "freight shipment pickup starts").sum()
                ),
            ),
            delivery_count=(
                "_type",
                lambda values: int(
                    (values == "freight shipment delivered starts").sum()
                ),
            ),
            activity_event_count=(
                "event_category",
                lambda values: int((values == "activity").sum()),
            ),
        )
        .reset_index()
    )
    base = base.merge(counts, on="vehicle_id", how="left")

    if not tours.empty:
        tour_totals = (
            tours.groupby("vehicle_id", sort=False)
            .agg(
                tour_count=("tour_id", "nunique"),
                tour_time_s=("tour_duration_s", _sum_numeric_or_nan),
            )
            .reset_index()
        )
        base = base.merge(tour_totals, on="vehicle_id", how="left")
    else:
        base["tour_count"] = 0
        base["tour_time_s"] = pd.NA

    if not traffic_legs.empty:
        leg_totals = (
            traffic_legs.groupby("vehicle_id", sort=False)
            .agg(
                traffic_leg_count=("traffic_leg_index", "count"),
                transit_time_s=("transit_time_s", _sum_numeric_or_nan),
            )
            .reset_index()
        )
        base = base.merge(leg_totals, on="vehicle_id", how="left")
    else:
        base["traffic_leg_count"] = 0
        base["transit_time_s"] = pd.NA

    if not link_traversals.empty:
        link_totals = (
            link_traversals.groupby("vehicle_id", sort=False)
            .agg(
                link_count=("link", "count"),
                unique_link_count=("link", "nunique"),
                vkt_m=("distance_m", _sum_numeric_or_nan),
                vkt_km=("distance_km", _sum_numeric_or_nan),
            )
            .reset_index()
        )
        base = base.merge(link_totals, on="vehicle_id", how="left")
    else:
        base["link_count"] = 0
        base["unique_link_count"] = 0
        base["vkt_m"] = pd.NA
        base["vkt_km"] = pd.NA

    base["non_transit_tour_time_s"] = base["tour_time_s"] - base["transit_time_s"]
    return base


def read_freight_events(
    events_path: PathLike,
    *,
    network: Optional[Union[PathLike, pd.DataFrame, Mapping[str, pd.DataFrame]]] = None,
    event_types: Optional[Union[str, Iterable[str]]] = None,
    freight_vehicle_ids: Optional[Iterable[str]] = None,
    freight_id_pattern: Optional[str] = r"freight",
) -> XmlTables:
    """Read freight events and return analysis-ready event/chain tables."""
    events = read_events(
        events_path,
        event_types=event_types,
        freight_only=True,
        freight_vehicle_ids=freight_vehicle_ids,
        freight_id_pattern=freight_id_pattern,
    )
    chains = build_vehicle_travel_chains(
        events,
        freight_only=True,
        freight_vehicle_ids=freight_vehicle_ids,
        freight_id_pattern=freight_id_pattern,
        network=network,
    )
    traversals = derive_link_traversals(chains, network=network)
    traffic_legs = derive_traffic_legs(chains)
    tours = derive_tour_summary(chains, traversals, traffic_legs)
    shipments = derive_shipment_summary(chains)
    vehicles = derive_vehicle_summary(chains, traversals, traffic_legs, tours)
    activity_mask = (
        chains["event_category"].isin(["activity", "shipment"])
        if not chains.empty
        else pd.Series(dtype=bool)
    )
    activities = chains.loc[activity_mask].copy() if not chains.empty else chains.copy()
    metadata = _records_frame(
        [
            {
                "source_path": str(Path(events_path).expanduser()),
                "event_count": len(events),
                "vehicle_count": chains["vehicle_id"].nunique() if not chains.empty else 0,
                "link_traversal_count": len(traversals),
                "first_event_time": events["time"].min() if "time" in events else None,
                "last_event_time": events["time"].max() if "time" in events else None,
                "network_provided": network is not None,
            }
        ]
    )
    return {
        "metadata": metadata,
        "events": events,
        "travel_chains": chains,
        "link_traversals": traversals,
        "traffic_legs": traffic_legs,
        "activities": activities,
        "tours": tours,
        "shipments": shipments,
        "vehicle_summary": vehicles,
    }


# ============================================================================
# B. Receiver XML
# ============================================================================


def read_receivers(receiver_path: PathLike) -> XmlTables:
    """Read freight receivers, plans, orders, products, and attributes."""
    file_path = _resolve_file(
        receiver_path,
        [
            "output_receivers.xml.gz",
            "receivers.xml.gz",
            "output_receivers.xml",
            "receivers.xml",
        ],
        description="receivers",
    )
    root = _open_xml(file_path).getroot()

    receiver_rows: list[dict[str, Any]] = []
    plan_rows: list[dict[str, Any]] = []
    order_rows: list[dict[str, Any]] = []
    item_rows: list[dict[str, Any]] = []
    product_rows: list[dict[str, Any]] = []
    product_type_rows: list[dict[str, Any]] = []
    attribute_rows: list[dict[str, Any]] = []

    product_types = _direct_child(root, "productTypes")
    for product_type in _direct_children(product_types, "productType"):
        product_type_rows.append(_coerce_attributes(product_type.attrib))

    for receiver in _descendants(root, "receiver"):
        receiver_record = _coerce_attributes(receiver.attrib)
        receiver_id = receiver_record.get("id")
        owner = {"receiver_id": receiver_id}
        receiver_attributes, long_attributes = _java_attributes(
            receiver, context="receiver", owner_fields=owner
        )
        attribute_rows.extend(long_attributes)
        receiver_record.update(
            {f"attribute_{name}": value for name, value in receiver_attributes.items()}
        )
        receiver_record["receiver_id"] = receiver_id

        plans = _direct_children(receiver, "plan")
        selected_plan_record: Optional[dict[str, Any]] = None
        for plan_index, plan in enumerate(plans):
            plan_record = {
                "receiver_id": receiver_id,
                "plan_index": plan_index,
                **_coerce_attributes(plan.attrib),
            }
            plan_record["selected"] = _is_selected(plan.get("selected"))
            time_window = _direct_child(plan, "timeWindow")
            if time_window is not None:
                for key, value in _coerce_attributes(time_window.attrib).items():
                    plan_record[f"time_window_{key}"] = value
                _add_time_seconds(
                    plan_record, ["time_window_start", "time_window_end"]
                )
            plan_attributes, long_plan_attributes = _java_attributes(
                plan,
                context="receiver_plan",
                owner_fields={**owner, "plan_index": plan_index},
            )
            attribute_rows.extend(long_plan_attributes)
            plan_record.update(
                {f"attribute_{name}": value for name, value in plan_attributes.items()}
            )

            for order_index, order in enumerate(_direct_children(plan, "order")):
                order_record = {
                    "receiver_id": receiver_id,
                    "plan_index": plan_index,
                    "order_index": order_index,
                    "plan_selected": plan_record["selected"],
                    **_coerce_attributes(order.attrib),
                }
                order_rows.append(order_record)
                for item_index, item in enumerate(_direct_children(order, "item")):
                    item_record = {
                        "receiver_id": receiver_id,
                        "plan_index": plan_index,
                        "order_index": order_index,
                        "item_index": item_index,
                        "plan_selected": plan_record["selected"],
                        **_coerce_attributes(item.attrib),
                    }
                    _add_time_seconds(item_record, ["serviceTime"])
                    item_rows.append(item_record)

            plan_record["order_count"] = len(_direct_children(plan, "order"))
            plan_rows.append(plan_record)
            if plan_record["selected"]:
                selected_plan_record = plan_record

        for product_index, product in enumerate(_direct_children(receiver, "product")):
            product_record = {
                "receiver_id": receiver_id,
                "product_index": product_index,
                **_coerce_attributes(product.attrib),
            }
            product_attributes, long_product_attributes = _java_attributes(
                product,
                context="receiver_product",
                owner_fields={**owner, "product_index": product_index},
            )
            attribute_rows.extend(long_product_attributes)
            product_record.update(
                {f"attribute_{name}": value for name, value in product_attributes.items()}
            )
            reorder_policy = _direct_child(product, "reorderPolicy")
            if reorder_policy is not None:
                product_record.update(
                    {
                        f"reorder_{key}": value
                        for key, value in _coerce_attributes(
                            reorder_policy.attrib
                        ).items()
                    }
                )
                reorder_attributes, long_reorder_attributes = _java_attributes(
                    reorder_policy,
                    context="reorder_policy",
                    owner_fields={**owner, "product_index": product_index},
                )
                attribute_rows.extend(long_reorder_attributes)
                product_record.update(
                    {
                        f"reorder_attribute_{name}": value
                        for name, value in reorder_attributes.items()
                    }
                )
            product_rows.append(product_record)

        receiver_record["plan_count"] = len(plans)
        receiver_record["selected_plan_index"] = (
            selected_plan_record.get("plan_index")
            if selected_plan_record is not None
            else None
        )
        receiver_record["selected_plan_score"] = (
            selected_plan_record.get("score")
            if selected_plan_record is not None
            else None
        )
        for key in [
            "time_window_start",
            "time_window_end",
            "time_window_start_s",
            "time_window_end_s",
        ]:
            receiver_record[f"selected_{key}"] = (
                selected_plan_record.get(key)
                if selected_plan_record is not None
                else None
            )
        receiver_rows.append(receiver_record)

    return {
        "metadata": _records_frame(
            [
                {
                    "source_path": str(file_path),
                    "root_element": _local_name(root.tag),
                    "receiver_count": len(receiver_rows),
                    "plan_count": len(plan_rows),
                    "order_count": len(order_rows),
                    "item_count": len(item_rows),
                }
            ]
        ),
        "receivers": _records_frame(
            receiver_rows, ["receiver_id", "id", "linkId", "selected_plan_score"]
        ),
        "plans": _records_frame(
            plan_rows, ["receiver_id", "plan_index", "selected", "score"]
        ),
        "orders": _records_frame(
            order_rows, ["receiver_id", "plan_index", "order_index", "carrierId"]
        ),
        "items": _records_frame(
            item_rows,
            ["receiver_id", "plan_index", "order_index", "item_index", "id"],
        ),
        "products": _records_frame(
            product_rows, ["receiver_id", "product_index", "id"]
        ),
        "product_types": _records_frame(product_type_rows, ["id"]),
        "attributes": _records_frame(
            attribute_rows,
            ["receiver_id", "context", "plan_index", "product_index", "name", "value"],
        ),
    }


# ---------------------------------------------------------------------------
# Carrier XML
# ---------------------------------------------------------------------------


def read_carriers(carrier_path: PathLike) -> XmlTables:
    """Read carrier capabilities, demand, plans, tours, acts, legs, and routes."""
    file_path = _resolve_file(
        carrier_path,
        [
            "output_carriers.xml.gz",
            "output_carriers.xml",
            "carriers.xml.gz",
            "carriers.xml",
        ],
        description="carriers",
    )
    root = _open_xml(file_path).getroot()

    carrier_rows: list[dict[str, Any]] = []
    vehicle_rows: list[dict[str, Any]] = []
    shipment_rows: list[dict[str, Any]] = []
    service_rows: list[dict[str, Any]] = []
    plan_rows: list[dict[str, Any]] = []
    tour_rows: list[dict[str, Any]] = []
    element_rows: list[dict[str, Any]] = []
    activity_rows: list[dict[str, Any]] = []
    leg_rows: list[dict[str, Any]] = []
    attribute_rows: list[dict[str, Any]] = []

    for carrier in _descendants(root, "carrier"):
        carrier_record = _coerce_attributes(carrier.attrib)
        carrier_id = carrier_record.get("id")
        carrier_record["carrier_id"] = carrier_id
        owner = {"carrier_id": carrier_id}
        carrier_attributes, long_carrier_attributes = _java_attributes(
            carrier, context="carrier", owner_fields=owner
        )
        attribute_rows.extend(long_carrier_attributes)
        carrier_record.update(
            {f"attribute_{name}": value for name, value in carrier_attributes.items()}
        )

        capabilities = _direct_child(carrier, "capabilities")
        if capabilities is not None:
            carrier_record.update(
                {
                    f"capabilities_{key}": value
                    for key, value in _coerce_attributes(capabilities.attrib).items()
                }
            )
            vehicles_container = _direct_child(capabilities, "vehicles")
            for vehicle_index, vehicle in enumerate(
                _direct_children(vehicles_container, "vehicle")
            ):
                vehicle_record = {
                    "carrier_id": carrier_id,
                    "vehicle_index": vehicle_index,
                    **_coerce_attributes(vehicle.attrib),
                }
                _add_time_seconds(vehicle_record, ["earliestStart", "latestEnd"])
                vehicle_attributes, long_vehicle_attributes = _java_attributes(
                    vehicle,
                    context="carrier_vehicle",
                    owner_fields={
                        **owner,
                        "vehicle_id": vehicle_record.get("id"),
                    },
                )
                attribute_rows.extend(long_vehicle_attributes)
                vehicle_record.update(
                    {
                        f"attribute_{name}": value
                        for name, value in vehicle_attributes.items()
                    }
                )
                vehicle_rows.append(vehicle_record)

        shipments_container = _direct_child(carrier, "shipments")
        for shipment_index, shipment in enumerate(
            _direct_children(shipments_container, "shipment")
        ):
            shipment_record = {
                "carrier_id": carrier_id,
                "shipment_index": shipment_index,
                **_coerce_attributes(shipment.attrib),
            }
            _add_time_seconds(
                shipment_record,
                [
                    "startPickup",
                    "endPickup",
                    "startDelivery",
                    "endDelivery",
                    "pickupServiceTime",
                    "deliveryServiceTime",
                ],
            )
            shipment_attributes, long_shipment_attributes = _java_attributes(
                shipment,
                context="carrier_shipment",
                owner_fields={
                    **owner,
                    "shipment_id": shipment_record.get("id"),
                },
            )
            attribute_rows.extend(long_shipment_attributes)
            shipment_record.update(
                {
                    f"attribute_{name}": value
                    for name, value in shipment_attributes.items()
                }
            )
            shipment_rows.append(shipment_record)

        services_container = _direct_child(carrier, "services")
        for service_index, service in enumerate(
            _direct_children(services_container, "service")
        ):
            service_record = {
                "carrier_id": carrier_id,
                "service_index": service_index,
                **_coerce_attributes(service.attrib),
            }
            _add_time_seconds(
                service_record,
                [
                    "serviceDuration",
                    "duration",
                    "startTimeWindow",
                    "endTimeWindow",
                    "start",
                    "end",
                ],
            )
            time_window = _direct_child(service, "timeWindow")
            if time_window is not None:
                service_record.update(
                    {
                        f"time_window_{key}": value
                        for key, value in _coerce_attributes(
                            time_window.attrib
                        ).items()
                    }
                )
                _add_time_seconds(
                    service_record, ["time_window_start", "time_window_end"]
                )
            service_rows.append(service_record)

        plans_container = _direct_child(carrier, "plans")
        plans = _direct_children(plans_container, "plan")
        selected_plan_record: Optional[dict[str, Any]] = None
        for plan_index, plan in enumerate(plans):
            plan_record = {
                "carrier_id": carrier_id,
                "plan_index": plan_index,
                **_coerce_attributes(plan.attrib),
            }
            plan_record["selected"] = _is_selected(plan.get("selected"))
            plan_attributes, long_plan_attributes = _java_attributes(
                plan,
                context="carrier_plan",
                owner_fields={**owner, "plan_index": plan_index},
            )
            attribute_rows.extend(long_plan_attributes)
            plan_record.update(
                {f"attribute_{name}": value for name, value in plan_attributes.items()}
            )

            tours = _direct_children(plan, "tour")
            for tour_index, tour in enumerate(tours):
                tour_record = {
                    "carrier_id": carrier_id,
                    "plan_index": plan_index,
                    "plan_selected": plan_record["selected"],
                    "tour_index": tour_index,
                    **_coerce_attributes(tour.attrib),
                }
                planned_transport_time = 0.0
                act_count = 0
                leg_count = 0
                for sequence, element in enumerate(tour):
                    element_type = _local_name(element.tag)
                    if element_type not in {"act", "leg"}:
                        continue
                    element_record = {
                        "carrier_id": carrier_id,
                        "plan_index": plan_index,
                        "plan_selected": plan_record["selected"],
                        "tour_index": tour_index,
                        "tourId": tour_record.get("tourId"),
                        "vehicleId": tour_record.get("vehicleId"),
                        "sequence": sequence,
                        "element_type": element_type,
                        **_coerce_attributes(element.attrib),
                    }
                    _add_time_seconds(
                        element_record,
                        [
                            "end_time",
                            "expected_dep_time",
                            "expected_transp_time",
                            "max_dur",
                        ],
                    )
                    if element_type == "act":
                        element_record["act_index"] = act_count
                        act_count += 1
                        activity_rows.append(element_record.copy())
                    else:
                        element_record["leg_index"] = leg_count
                        leg_count += 1
                        route = _direct_child(element, "route")
                        route_text = (
                            " ".join((route.text or "").split())
                            if route is not None
                            else ""
                        )
                        if route is not None:
                            element_record.update(
                                {
                                    f"route_{key}": value
                                    for key, value in _coerce_attributes(
                                        route.attrib
                                    ).items()
                                }
                            )
                        route_ids = tuple(route_text.split()) if route_text else tuple()
                        element_record["route"] = route_text
                        element_record["route_link_ids"] = route_ids
                        element_record["route_link_count"] = len(route_ids)
                        planned_transport_time += (
                            _time_to_seconds(
                                element_record.get("expected_transp_time")
                            )
                            or 0.0
                        )
                        leg_rows.append(element_record.copy())
                    element_rows.append(element_record)
                tour_record["act_count"] = act_count
                tour_record["leg_count"] = leg_count
                tour_record["planned_transport_time_s"] = planned_transport_time
                tour_rows.append(tour_record)

            plan_record["tour_count"] = len(tours)
            plan_rows.append(plan_record)
            if plan_record["selected"]:
                selected_plan_record = plan_record

        carrier_record["vehicle_count"] = sum(
            row["carrier_id"] == carrier_id for row in vehicle_rows
        )
        carrier_record["shipment_count"] = sum(
            row["carrier_id"] == carrier_id for row in shipment_rows
        )
        carrier_record["service_count"] = sum(
            row["carrier_id"] == carrier_id for row in service_rows
        )
        carrier_record["plan_count"] = len(plans)
        carrier_record["selected_plan_index"] = (
            selected_plan_record.get("plan_index")
            if selected_plan_record is not None
            else None
        )
        carrier_record["selected_plan_score"] = (
            selected_plan_record.get("score")
            if selected_plan_record is not None
            else None
        )
        if selected_plan_record is not None:
            for key, value in selected_plan_record.items():
                if key.startswith("attribute_"):
                    carrier_record[f"selected_plan_{key}"] = value
        carrier_rows.append(carrier_record)

    return {
        "metadata": _records_frame(
            [
                {
                    "source_path": str(file_path),
                    "root_element": _local_name(root.tag),
                    "carrier_count": len(carrier_rows),
                    "vehicle_count": len(vehicle_rows),
                    "shipment_count": len(shipment_rows),
                    "service_count": len(service_rows),
                    "plan_count": len(plan_rows),
                    "tour_count": len(tour_rows),
                }
            ]
        ),
        "carriers": _records_frame(
            carrier_rows, ["carrier_id", "id", "selected_plan_score"]
        ),
        "vehicles": _records_frame(
            vehicle_rows, ["carrier_id", "vehicle_index", "id", "depotLinkId", "typeId"]
        ),
        "shipments": _records_frame(
            shipment_rows, ["carrier_id", "shipment_index", "id", "from", "to"]
        ),
        "services": _records_frame(
            service_rows, ["carrier_id", "service_index", "id"]
        ),
        "plans": _records_frame(
            plan_rows, ["carrier_id", "plan_index", "selected", "score"]
        ),
        "tours": _records_frame(
            tour_rows,
            ["carrier_id", "plan_index", "tour_index", "tourId", "vehicleId"],
        ),
        "tour_elements": _records_frame(
            element_rows,
            [
                "carrier_id",
                "plan_index",
                "tour_index",
                "sequence",
                "element_type",
            ],
        ),
        "tour_activities": _records_frame(
            activity_rows,
            ["carrier_id", "plan_index", "tour_index", "act_index", "type"],
        ),
        "tour_legs": _records_frame(
            leg_rows,
            ["carrier_id", "plan_index", "tour_index", "leg_index", "route"],
        ),
        "attributes": _records_frame(
            attribute_rows,
            ["carrier_id", "context", "plan_index", "name", "value"],
        ),
    }


# ---------------------------------------------------------------------------
# Collaboration XML
# ---------------------------------------------------------------------------


def read_collaboration(collaboration_path: PathLike) -> XmlTables:
    """Read collaboration coalitions, subcoalitions, members, and allocations."""
    file_path = Path(collaboration_path).expanduser()
    if file_path.is_dir():
        discovered = _latest_iteration_file(
            file_path, "*.collaboration_data.xml*"
        )
        if discovered is None:
            raise FileNotFoundError(
                f"No collaboration_data XML found below {file_path}/ITERS"
            )
        file_path = discovered
    root = _open_xml(file_path).getroot()

    metadata = {"source_path": str(file_path), **_coerce_attributes(root.attrib)}
    coalition_rows: list[dict[str, Any]] = []
    coalition_member_rows: list[dict[str, Any]] = []
    subcoalition_rows: list[dict[str, Any]] = []
    subcoalition_member_rows: list[dict[str, Any]] = []
    allocation_rows: list[dict[str, Any]] = []

    for coalition_index, coalition in enumerate(_descendants(root, "coalition")):
        coalition_record = {
            "coalition_index": coalition_index,
            **_coerce_attributes(coalition.attrib),
        }
        coalition_id = coalition_record.get("id")
        members_container = _direct_child(coalition, "members")
        members = _direct_children(members_container, "member")
        for member_index, member in enumerate(members):
            coalition_member_rows.append(
                {
                    "coalition_index": coalition_index,
                    "coalition_id": coalition_id,
                    "member_index": member_index,
                    **_coerce_attributes(member.attrib),
                }
            )

        scores_container = _direct_child(coalition, "subCoalitionScores")
        subcoalitions = _direct_children(scores_container, "subCoalition")
        for subcoalition_index, subcoalition in enumerate(subcoalitions):
            member_ids = [
                (member_id.text or "").strip()
                for member_id in _direct_children(subcoalition, "memberId")
                if (member_id.text or "").strip()
            ]
            subcoalition_record = {
                "coalition_index": coalition_index,
                "coalition_id": coalition_id,
                "subcoalition_index": subcoalition_index,
                **_coerce_attributes(subcoalition.attrib),
                "member_ids": tuple(member_ids),
                "member_count": len(member_ids),
            }
            subcoalition_rows.append(subcoalition_record)
            for member_index, member_id in enumerate(member_ids):
                subcoalition_member_rows.append(
                    {
                        "coalition_index": coalition_index,
                        "coalition_id": coalition_id,
                        "subcoalition_index": subcoalition_index,
                        "member_index": member_index,
                        "member_id": member_id,
                    }
                )
        coalition_record["member_count"] = len(members)
        coalition_record["subcoalition_count"] = len(subcoalitions)
        coalition_rows.append(coalition_record)

    for allocation_index, allocation in enumerate(_descendants(root, "allocation")):
        allocation_rows.append(
            {
                "allocation_index": allocation_index,
                **_coerce_attributes(allocation.attrib),
            }
        )

    return {
        "metadata": _records_frame([metadata], ["source_path", "iteration"]),
        "coalitions": _records_frame(
            coalition_rows, ["coalition_index", "id", "type", "size"]
        ),
        "coalition_members": _records_frame(
            coalition_member_rows,
            ["coalition_index", "coalition_id", "member_index", "id", "role"],
        ),
        "subcoalitions": _records_frame(
            subcoalition_rows,
            [
                "coalition_index",
                "coalition_id",
                "subcoalition_index",
                "size",
                "score",
                "type",
                "member_ids",
            ],
        ),
        "subcoalition_members": _records_frame(
            subcoalition_member_rows,
            [
                "coalition_index",
                "coalition_id",
                "subcoalition_index",
                "member_index",
                "member_id",
            ],
        ),
        "allocations": _records_frame(
            allocation_rows,
            ["allocation_index", "collaboratorId", "value"],
        ),
    }


# ---------------------------------------------------------------------------
# Network XML
# ---------------------------------------------------------------------------


def read_network(network_path: PathLike) -> XmlTables:
    """Read a MATSim network with matsim-tools and enrich links with coordinates."""
    matsim_tools = _require_matsim_tools()
    file_path = _resolve_file(
        network_path,
        ["output_network.xml.gz", "output_network.xml", "network.xml.gz", "network.xml"],
        description="network",
    )
    network_object = matsim_tools.read_network(str(file_path))
    nodes = network_object.nodes.copy()
    links = network_object.links.copy()
    node_attributes = getattr(
        network_object,
        "node_attrs",
        getattr(network_object, "node_attributes", pd.DataFrame()),
    ).copy()
    link_attributes = getattr(
        network_object,
        "link_attrs",
        getattr(network_object, "link_attributes", pd.DataFrame()),
    ).copy()
    network_attributes = dict(getattr(network_object, "network_attrs", {}) or {})

    if {"node_id", "x", "y"}.issubset(nodes.columns) and {
        "from_node",
        "to_node",
    }.issubset(links.columns):
        from_nodes = nodes[["node_id", "x", "y"]].rename(
            columns={
                "node_id": "from_node",
                "x": "from_x",
                "y": "from_y",
            }
        )
        to_nodes = nodes[["node_id", "x", "y"]].rename(
            columns={"node_id": "to_node", "x": "to_x", "y": "to_y"}
        )
        links = links.merge(from_nodes, on="from_node", how="left")
        links = links.merge(to_nodes, on="to_node", how="left")
        links["euclidean_length_m"] = (
            (links["to_x"] - links["from_x"]) ** 2
            + (links["to_y"] - links["from_y"]) ** 2
        ) ** 0.5
    if {"length", "freespeed"}.issubset(links.columns):
        speed = pd.to_numeric(links["freespeed"], errors="coerce")
        links["free_flow_travel_time_s"] = pd.to_numeric(
            links["length"], errors="coerce"
        ) / speed.where(speed != 0)
    if "modes" in links:
        links["modes_list"] = links["modes"].fillna("").map(
            lambda value: tuple(
                mode.strip() for mode in str(value).split(",") if mode.strip()
            )
        )

    nodes = _join_wide_attributes(nodes, node_attributes, "node_id")
    links = _join_wide_attributes(links, link_attributes, "link_id")
    metadata = {
        "source_path": str(file_path),
        "node_count": len(nodes),
        "link_count": len(links),
        **network_attributes,
    }
    return {
        "nodes": nodes,
        "links": links,
        "node_attributes": node_attributes,
        "link_attributes": link_attributes,
        "metadata": _records_frame([metadata], ["source_path", "node_count", "link_count"]),
    }


def _join_wide_attributes(
    entities: pd.DataFrame, attributes: pd.DataFrame, id_column: str
) -> pd.DataFrame:
    if (
        attributes.empty
        or id_column not in attributes
        or "name" not in attributes
        or "value" not in attributes
    ):
        return entities
    wide = attributes.pivot_table(
        index=id_column, columns="name", values="value", aggfunc="first"
    )
    wide.columns = [f"attribute_{column}" for column in wide.columns]
    return entities.merge(wide.reset_index(), on=id_column, how="left")


# ---------------------------------------------------------------------------
# Whole-scenario convenience API
# ---------------------------------------------------------------------------


def discover_output_scenarios(
    output_root: PathLike,
    *,
    recursive: bool = True,
    iteration: Optional[int] = None,
) -> pd.DataFrame:
    """Index all MATSim scenario folders below an output collection.

    A scenario folder is identified by ``output_events.xml[.gz]``.  The result
    is a lightweight DataFrame of resolved paths; it does not read the large
    events/network contents.
    """
    root = Path(output_root).expanduser()
    if not root.is_dir():
        raise NotADirectoryError(f"MATSim output root not found: {root}")
    iterator = root.rglob("output_events.xml*") if recursive else root.glob("output_events.xml*")
    scenario_paths = sorted({path.parent for path in iterator if path.is_file()})
    rows: list[dict[str, Any]] = []
    for scenario_path in scenario_paths:
        paths = discover_scenario_files(scenario_path, iteration=iteration)
        rows.append(
            {
                "scenario_name": scenario_path.name,
                "scenario_path": str(scenario_path),
                **{
                    f"{key}_path": str(value) if value is not None else None
                    for key, value in paths.items()
                    if key != "scenario"
                },
            }
        )
    return _records_frame(
        rows,
        [
            "scenario_name",
            "scenario_path",
            "events_path",
            "receivers_path",
            "carriers_path",
            "collaboration_path",
            "network_path",
        ],
    )


def _iteration_number(path: Path) -> int:
    matches = re.findall(r"(?:it\.|/)(\d+)(?:\.|/)", path.as_posix())
    return int(matches[-1]) if matches else -1


def _available_iterations(scenario_path: Path) -> list[int]:
    """Return sorted numeric iteration directories below ``ITERS``."""
    iters = scenario_path / "ITERS"
    if not iters.is_dir():
        return []
    numbers: list[int] = []
    for directory in iters.iterdir():
        if not directory.is_dir():
            continue
        match = re.fullmatch(r"it\.(\d+)", directory.name)
        if match:
            numbers.append(int(match.group(1)))
    return sorted(set(numbers))


def _latest_iteration_file(
    scenario_path: Path, pattern: str, iteration: Optional[int] = None
) -> Optional[Path]:
    iters = scenario_path / "ITERS"
    if not iters.is_dir():
        return None
    if iteration is not None:
        candidates = sorted((iters / f"it.{iteration}").glob(pattern))
    else:
        candidates = sorted(
            iters.glob(f"it.*/{pattern}"),
            key=lambda item: (_iteration_number(item), item.name),
        )
    return candidates[-1] if candidates else None


def _network_from_config(scenario_path: Path) -> Optional[Path]:
    config_candidates = [
        scenario_path / "output_config_reduced.xml",
        scenario_path / "output_config.xml",
        scenario_path / "config.xml",
    ]
    config_path = next((path for path in config_candidates if path.is_file()), None)
    if config_path is None:
        return None
    root = _open_xml(config_path).getroot()
    configured_value: Optional[str] = None
    for module in _descendants(root, "module"):
        if module.get("name") != "network":
            continue
        for parameter in _direct_children(module, "param"):
            if parameter.get("name") == "inputNetworkFile":
                configured_value = parameter.get("value")
                break
    if not configured_value:
        return None

    configured_path = Path(configured_value).expanduser()
    if configured_path.is_absolute() and configured_path.is_file():
        return configured_path
    candidates = [Path.cwd() / configured_path, config_path.parent / configured_path]
    candidates.extend(parent / configured_path for parent in scenario_path.parents)
    return next((candidate.resolve() for candidate in candidates if candidate.is_file()), None)


def discover_scenario_files(
    scenario_path: PathLike,
    *,
    iteration: Optional[int] = None,
    network_path: Optional[PathLike] = None,
    collaboration_path: Optional[PathLike] = None,
) -> dict[str, Optional[Path]]:
    """Find standard MATSim files without hard-coding a particular experiment."""
    scenario = Path(scenario_path).expanduser()
    if not scenario.is_dir():
        raise NotADirectoryError(f"MATSim scenario directory not found: {scenario}")

    def first_file(names: Sequence[str]) -> Optional[Path]:
        return next(
            (scenario / name for name in names if (scenario / name).is_file()),
            None,
        )

    events = (
        _latest_iteration_file(scenario, f"{iteration}.events.xml*", iteration)
        if iteration is not None
        else None
    )
    if events is None:
        events = first_file(["output_events.xml.gz", "output_events.xml"])
    if events is None:
        events = _latest_iteration_file(scenario, "*.events.xml*")
    receivers = (
        _latest_iteration_file(scenario, f"{iteration}.receivers.xml*", iteration)
        if iteration is not None
        else None
    )
    if receivers is None:
        receivers = first_file(
            [
                "output_receivers.xml.gz",
                "receivers.xml.gz",
                "output_receivers.xml",
                "receivers.xml",
            ]
        )
    if receivers is None:
        receivers = _latest_iteration_file(scenario, "*.receivers.xml*")
    carriers = (
        _latest_iteration_file(scenario, f"{iteration}.carrierPlans.xml*", iteration)
        if iteration is not None
        else None
    )
    if carriers is None:
        carriers = first_file(
            [
                "output_carriers.xml.gz",
                "output_carriers.xml",
                "carriers.xml.gz",
                "carriers.xml",
            ]
        )
    if carriers is None:
        carriers = _latest_iteration_file(scenario, "*.carrierPlans.xml*")
    collaboration = (
        Path(collaboration_path).expanduser()
        if collaboration_path is not None
        else _latest_iteration_file(scenario, "*.collaboration_data.xml*", iteration)
    )
    network = (
        Path(network_path).expanduser()
        if network_path is not None
        else first_file(["output_network.xml.gz", "output_network.xml"])
    )
    if network is None:
        network = _network_from_config(scenario)
    return {
        "scenario": scenario,
        "events": events,
        "receivers": receivers,
        "carriers": carriers,
        "collaboration": collaboration,
        "network": network,
    }


def read_scenario_output(
    scenario_path: PathLike,
    *,
    iteration: Optional[int] = None,
    network_path: Optional[PathLike] = None,
    collaboration_path: Optional[PathLike] = None,
    freight_id_pattern: Optional[str] = r"freight",
    include_iter0_carriers: bool = True,
    include_last_iteration: bool = True,
) -> dict[str, Any]:
    """Read all available freight-related tables from one MATSim output folder.

    Parameters
    ----------
    include_iter0_carriers
        When true (the default), additionally read
        ``ITERS/it.0/0.carrierPlans.xml[.gz]`` into ``iter0_carriers``.
    include_last_iteration
        When true (the default), detect the largest ``ITERS/it.N`` directory and
        additionally read its carrier plans and receivers into
        ``last_iter_carriers`` and ``last_iter_receivers``.

    The iteration-specific files use separate result keys and therefore never
    overwrite the main ``carriers`` and ``receivers`` tables.
    """
    paths = discover_scenario_files(
        scenario_path,
        iteration=iteration,
        network_path=network_path,
        collaboration_path=collaboration_path,
    )
    scenario = paths["scenario"]
    if scenario is None:  # Defensive; discover_scenario_files always sets it.
        raise RuntimeError("Scenario path resolution unexpectedly returned None")

    iteration_numbers = _available_iterations(scenario)
    last_iteration = iteration_numbers[-1] if iteration_numbers else None
    iter0_carriers_path = (
        _latest_iteration_file(scenario, "0.carrierPlans.xml*", iteration=0)
        if include_iter0_carriers
        else None
    )
    last_iter_carriers_path = (
        _latest_iteration_file(
            scenario,
            f"{last_iteration}.carrierPlans.xml*",
            iteration=last_iteration,
        )
        if include_last_iteration and last_iteration is not None
        else None
    )
    last_iter_receivers_path = (
        _latest_iteration_file(
            scenario,
            f"{last_iteration}.receivers.xml*",
            iteration=last_iteration,
        )
        if include_last_iteration and last_iteration is not None
        else None
    )

    path_values: dict[str, Any] = {
        **paths,
        "iter0_carriers": iter0_carriers_path,
        "last_iteration": last_iteration,
        "last_iter_carriers": last_iter_carriers_path,
        "last_iter_receivers": last_iter_receivers_path,
    }
    path_frame = _records_frame(
        [
            {
                key: (
                    str(value)
                    if isinstance(value, Path)
                    else value
                )
                for key, value in path_values.items()
            }
        ]
    )
    result: dict[str, Any] = {"paths": path_frame}
    network_tables: Optional[XmlTables] = None
    if paths["network"] is not None:
        network_tables = read_network(paths["network"])
        result["network"] = network_tables
    if paths["events"] is not None:
        result["events"] = read_freight_events(
            paths["events"],
            network=network_tables,
            freight_id_pattern=freight_id_pattern,
        )
    if paths["receivers"] is not None:
        result["receivers"] = read_receivers(paths["receivers"])
    if paths["carriers"] is not None:
        result["carriers"] = read_carriers(paths["carriers"])
    if paths["collaboration"] is not None:
        result["collaboration"] = read_collaboration(paths["collaboration"])
    if iter0_carriers_path is not None:
        result["iter0_carriers"] = read_carriers(iter0_carriers_path)
    if last_iter_carriers_path is not None:
        result["last_iter_carriers"] = read_carriers(last_iter_carriers_path)
    if last_iter_receivers_path is not None:
        result["last_iter_receivers"] = read_receivers(last_iter_receivers_path)
    return result


# Backwards-friendly aliases with explicit names.
read_matsim_events_as_df = read_events
read_collaboration_data = read_collaboration


__all__ = [
    "build_vehicle_travel_chains",
    "derive_link_traversals",
    "derive_shipment_summary",
    "derive_tour_summary",
    "derive_traffic_legs",
    "derive_vehicle_summary",
    "discover_output_scenarios",
    "discover_scenario_files",
    "read_carriers",
    "read_collaboration",
    "read_collaboration_data",
    "read_events",
    "read_freight_events",
    "read_matsim_events_as_df",
    "read_network",
    "read_receivers",
    "read_scenario_output",
]
