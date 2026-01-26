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
from typing import Tuple

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

def read_carriers(carrier_file_path) -> Tuple[pd.DataFrame, pd.DataFrame]:   
    """
    Reads MATSim carriers output file and derive its shipments, vehicles, and the
    score of the selected plan.
    
    Args:
        carrier_file_path (str): Path to the carriers XML file. 
    Returns:
        A dict containing shipments DataFrame, vehicles DataFrame, and total score.
    """

    carriers_data = []
    carriers_shipments = []



    if carrier_file_path.endswith('.gz') or carrier_file_path.endswith('.xml'):
        parse_filepath = carrier_file_path
    else:
        parse_filepath = carrier_file_path + 'output_carriers.xml.gz'

    with gzip.open(parse_filepath, 'rb') as f:
        tree = etree.parse(f)
        root = tree.getroot()
    
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
    return carriers_df, shipments_df