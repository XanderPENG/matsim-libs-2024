"""
Author: Xander PENG
Date: 2026-01-26
File: matsim_output_reader.py
Description: This is a reader class that can read MATSim output files, 
    primarily used for the `receivers`, `carriers`, `lsp`, and `freight_collaboration` extensions.
"""

import os
from lxml import etree
import matsim
import pandas as pd
import geopandas as gpd
import gzip
from typing import Dict, Tuple

def read_receivers(receiver_file_path, tw=None) -> dict:
    """
    Reads MATSim receivers output file and derive its plan, score, and collaboration status.
    
    Args:
        receiver_file_path (str): Path to the receivers XML file.
        time_window (tuple, optional): A tuple specifying the start and end time for filtering plans. Defaults to None.
    Returns:
        A dict containing plans, scores, and collaboration status DataFrames.
    """
     # Lists to store receiver information
    collaborative_receivers = []
    non_collaborative_receivers = []

    if receiver_file_path.endswith('.gz') or receiver_file_path.endswith('.xml'):
        parse_filepath = receiver_file_path   
    else:
        parse_filepath = receiver_file_path + 'output_receivers.xml.gz'

    # Parse the XML file
    with gzip.open(parse_filepath, 'rb') as f:
        tree = etree.parse(f)
        root = tree.getroot()
    
    # Iterate through all receivers
    for receiver in root.findall('.//receiver'):
        receiver_id = receiver.get('id')
        
        # Find the selected plan
        selected_plan = receiver.find('.//plan[@selected="yes"]')
        
        if selected_plan is not None:
            # Get the score
            score = float(selected_plan.get('score', -9999999))
            
            # Get the time window
            time_window = selected_plan.find('timeWindow')
            
            if time_window is not None:
                # Parse start and end times (format: "HH:MM:SS")
                start_time = time_window.get('start')
                end_time = time_window.get('end')
                
                # Convert to hours for comparison
                start_hour = int(start_time.split(':')[0])
                end_hour = int(end_time.split(':')[0])
                
                receiver_info = {
                    'id': receiver_id,
                    'time_window': (start_hour, end_hour),
                    'time_window_str': f"{start_time} - {end_time}",
                    'score': score
                }
                
                # Check if time window is different from original_tw
                if (start_hour, end_hour) == tw:
                    non_collaborative_receivers.append(receiver_info)
                else:
                    collaborative_receivers.append(receiver_info)
            else:
                raise ValueError(f"No time window found for receiver {receiver_id}")
        else:
            # No selected plan found
            raise ValueError(f"No selected plan found for receiver {receiver_id}")       
    total_score = 0
    for receiver in collaborative_receivers + non_collaborative_receivers:
        total_score += receiver['score']

    return {
        'collaborative_receivers': collaborative_receivers,
        'non_collaborative_receivers': non_collaborative_receivers,
        'count_collaborative': len(collaborative_receivers),
        'count_non_collaborative': len(non_collaborative_receivers),
        'total_receivers': len(collaborative_receivers) + len(non_collaborative_receivers),
        'total_score': total_score
    }

def read_carriers(carrier_file_path, with_iter0_scores=True) -> Tuple[pd.DataFrame, pd.DataFrame]:   
    """
    Reads MATSim carriers output file and derive its shipments, vehicles, and the
    score of the selected plan.
    
    Args:
        carrier_file_path (str): Path to the carriers XML file. 
        with_iter0_scores (bool, optional): Whether to include iter0 scores. Defaults to True.
    Returns:
        A dict containing shipments DataFrame, vehicles DataFrame, and total score.
    """

    carriers_data = []
    carriers_shipments = []



    if carrier_file_path.endswith('.xml.gz'):
        parse_filepath = carrier_file_path
        with gzip.open(parse_filepath, 'rb') as f:
            tree = etree.parse(f)
            root = tree.getroot()
    elif carrier_file_path.endswith('.xml'):
        parse_filepath = carrier_file_path
        tree = etree.parse(parse_filepath)
        root = tree.getroot()
    elif os.path.isdir(carrier_file_path):
        parse_filepath = os.path.join(carrier_file_path, 'output_carriers.xml.gz')
        with gzip.open(parse_filepath, 'rb') as f:
            tree = etree.parse(f)
            root = tree.getroot()
    else:
        raise ValueError(f"Invalid carrier file path: {carrier_file_path}")

    
    # Handle namespace
    ns = {'m': 'http://www.matsim.org/files/dtd'}
    
    for carrier in root.findall('.//{http://www.matsim.org/files/dtd}carrier'):
        carrier_id = carrier.get('id')
        
        # Get depot link IDs from vehicles
        depot_links = []
        vehicle_ids = []
        for vehicle in carrier.findall('.//{http://www.matsim.org/files/dtd}vehicle'):
            depot_link_id = vehicle.get('depotLinkId')
            vehicle_id = vehicle.get('id')
            if depot_link_id:
                depot_links.append(depot_link_id)
            if vehicle_id:
                vehicle_ids.append(vehicle_id)
        
        # shipments dataframe
        shipments = carrier.findall('.//{http://www.matsim.org/files/dtd}shipment')
        num_shipments = len(shipments)
        for shipment in shipments:
            shipment_id = shipment.get('id')
            pickup_link_id = shipment.get('from')
            delivery_link_id = shipment.get('to')
            size = shipment.get('size')
            start_pickup = shipment.get('startPickup')
            end_pickup = shipment.get('endPickup')
            start_delivery = shipment.get('startDelivery')
            end_delivery = shipment.get('endDelivery')
            pickup_service_time = shipment.get('pickupServiceTime')
            delivery_service_time = shipment.get('deliveryServiceTime')
            carriers_shipments.append({
                'carrier_id': carrier_id,
                'shipment_id': shipment_id,
                'pickup_link_id': pickup_link_id,
                'delivery_link_id': delivery_link_id,
                'size': size,
                'start_pickup': start_pickup,
                'end_pickup': end_pickup,
                'start_delivery': start_delivery,
                'end_delivery': end_delivery,
                'pickup_service_time': pickup_service_time,
                'delivery_service_time': delivery_service_time
            })

        
        # Get jsprit score from selected plan
        selected_plan = carrier.find('.//{http://www.matsim.org/files/dtd}plan[@selected="true"]')

        # Get score from selected plan
        carrier_score = None
        carrier_score = float(selected_plan.get('score'))
        if carrier_score is None:
            raise ValueError(f"No score found for selected plan of carrier {carrier_id}")


        carriers_data.append({
            'carrier_id': carrier_id,
            'depot_link_ids': list(depot_links),
            'depot_link_id': list(depot_links)[0] if depot_links else None,  # Primary depot
            'vehicle_ids': vehicle_ids,
            'num_vehicles': len(vehicle_ids),
            'carrier_score': carrier_score,
            'num_shipments': num_shipments
        })

        # End of carrier loop
    carriers_df = pd.DataFrame(carriers_data)
    shipments_df = pd.DataFrame(carriers_shipments)

    #--- Read iter0 scores if required---
    is_iter0_plan = os.path.basename(parse_filepath) == '0.carrierPlans.xml'
    if with_iter0_scores and not is_iter0_plan:
        folder_path = os.path.dirname(carrier_file_path)
        iter0_carrier_df, _ = read_iter0_carriers(folder_path, only_scores=False)
        iter0_scores = iter0_carrier_df.set_index('carrier_id')['carrier_score']
        iter0_vehicles = iter0_carrier_df.set_index('carrier_id')['num_vehicles']
        carriers_df['iter0_carrier_score'] = carriers_df['carrier_id'].map(iter0_scores)
        carriers_df['iter0_num_vehicles'] = carriers_df['carrier_id'].map(iter0_vehicles)

    return carriers_df, shipments_df

def read_iter0_carriers(file_path: str, only_scores: bool = True) -> Dict[str, float]:
    """
    Reads the ITERATION_0 carriers output file to extract carrier plans 
    When there are NO collaboration happened.

    Args:
        file_path (str): Path to the output folder.
    """
    #---check if the input is a path---
    if os.path.isdir(file_path):
        iter0_carrier_filepath = os.path.join(file_path, 'ITERS', 'it.0', '0.carrierPlans.xml')
    elif os.path.isfile(file_path) and os.path.basename(file_path) == '0.carrierPlans.xml':
        iter0_carrier_filepath = file_path
    else:
        raise ValueError(f"{file_path} is not a folder path or iter0 carrier plan file")
    
    if only_scores:
        carriers_df, _ = read_carriers(iter0_carrier_filepath, with_iter0_scores=False)
        return dict(zip(carriers_df['carrier_id'], carriers_df['carrier_score']))

    return read_carriers(iter0_carrier_filepath, with_iter0_scores=False)
        
def read_matsim_events_as_df(events_file_path: str, event_types: str):
    """ Read specified MATSim events (could be several types) into a pandas DataFrame.

    Args:
        events_file_path (str): Path to the MATSim events file.
        event_types (str): e.g., "VehicleEntersTraffic,VehicleLeavesTraffic"

    Returns:
        pd.DataFrame: DataFrame containing the specified MATSim events.
    """
    events = matsim.event_reader(events_file_path, types=event_types)
    # Get event keys
    event_keys = set()
    events_list = []
    for event in events:
        events_list.append(event)
        current_keys = list(event.keys())
        event_keys.update(current_keys)
    # Store events in a dict
    events_dict = {}
    for idx, event in enumerate(events_list):
        event_dict = {}
        for key in event_keys:
            if key in event.keys():
                event_dict[key] = event[key]
            else:
                event_dict[key] = None
        events_dict[idx] = event_dict
    # Convert to DataFrame
    events_df = pd.DataFrame.from_dict(events_dict, orient='index')
    # Convert columns which is number-like str into float and ignore the rest
    for column in events_df.columns:
        try:
            events_df[column] = events_df[column].astype(float)
        except:
            pass
    return events_df


def read_collaboration_allocation_data(folder_path: str, last_iter=50) -> Dict[str, float]:
    """ Read the collaboration allocation data from the specified folder.

    Args:
        folder_path (str): Path to the output folder.
    Returns:    
        Dict[str, float]: A dictionary containing allocation data.
    """

    if os.path.isdir(folder_path):
        last_iter_filepath = os.path.join(folder_path, 'ITERS', f'it.{last_iter}', f'{last_iter}.collaboration_data.xml')
    elif os.path.isfile(folder_path) and os.path.basename(folder_path) == f'{last_iter}.collaboration_data.xml':
        last_iter_filepath = folder_path
    else:
        return {'carrier1': 0.0}
    
    # Parse the XML file
    tree = etree.parse(last_iter_filepath)
    root = tree.getroot()
    
    # Extract allocation data
    allocation_dict = {}
    for allocation in root.findall('.//allocation'):
        collaborator_id = allocation.get('collaboratorId')
        value = float(allocation.get('value'))
        allocation_dict[collaborator_id] = value
    
    return allocation_dict
