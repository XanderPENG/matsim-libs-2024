package lsp;

import lsp.shipment.LSPShipment;
import lsp.shipment.ShipmentUtils;
import lsp.usecase.TransshipmentHub;
import lsp.usecase.UsecaseUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.freight.carrier.*;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.utils.io.MatsimXmlParser;
import org.matsim.core.utils.misc.Time;
import org.matsim.utils.objectattributes.attributable.AttributesXmlReaderDelegate;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.xml.sax.Attributes;

import java.util.Collections;
import java.util.Stack;

import static lsp.LSPConstants.*;

class LSPPlanXmlParser extends MatsimXmlParser {

	public static final Logger logger = LogManager.getLogger(LSPPlanXmlParser.class);

	private LSP currentLsp = null;
	private Carrier currentCarrier = null;
	private LSPShipment currentShipment = null;
	private final LSPs lsPs;
	private final Carriers carriers;
	private final CarrierVehicleTypes carrierVehicleTypes;
	private CarrierCapabilities.Builder capabilityBuilder;
	private TransshipmentHub hubResource;
	private String currentHubId;
	private Double currentHubFixedCost;
	private String currentHubLocation;
	private LSPPlan currentPlan;
	private String chainId;
	private Double score;

	private final AttributesXmlReaderDelegate attributesReader = new AttributesXmlReaderDelegate();
	private org.matsim.utils.objectattributes.attributable.Attributes currAttributes =
			new org.matsim.utils.objectattributes.attributable.AttributesImpl();



	public LSPPlanXmlParser(LSPs lsPs, Carriers carriers, CarrierVehicleTypes carrierVehicleTypes) {
		super();
		this.lsPs = lsPs;
		this.carriers = carriers;
		this.carrierVehicleTypes = carrierVehicleTypes;
	}

	@Override
	public void startTag(String name, Attributes atts, Stack<String> context) {
		switch (name) {
			case LSP -> {
				String lspId = atts.getValue(ID);
				Gbl.assertNotNull(lspId);
				currentLsp = LSPUtils.LSPBuilder.getInstance(Id.create(lspId, LSP.class))
						.setLogisticChainScheduler(UsecaseUtils.createDefaultSimpleForwardLogisticChainScheduler(Collections.emptyList()))
						.setInitialPlan(new LSPPlanImpl())
						.build();
				break;
			}
			case HUB -> {
				currentHubId = atts.getValue(ID);
				Gbl.assertNotNull(currentHubId);
				currentHubLocation = atts.getValue(LOCATION);
				Gbl.assertNotNull(currentHubLocation);
				currentHubFixedCost = Double.parseDouble(atts.getValue(FIXED_COST));
				Gbl.assertNotNull(currentHubFixedCost);
				break;
			}
			case SCHEDULER -> {
				Double capacityNeedFixed = Double.parseDouble(atts.getValue(CAPACITY_NEED_FIXED));
				Double capacityNeedLinear = Double.parseDouble(atts.getValue(CAPACITY_NEED_LINEAR));
				hubResource = UsecaseUtils.TransshipmentHubBuilder.newInstance(Id.create(currentHubId, LSPResource.class), Id.createLinkId(currentHubLocation), null)
						.setTransshipmentHubScheduler(UsecaseUtils.TranshipmentHubSchedulerBuilder.newInstance()
								.setCapacityNeedFixed(capacityNeedFixed) //Time needed, fixed (for Scheduler)
								.setCapacityNeedLinear(capacityNeedLinear) //additional time needed per shipmentSize (for Scheduler)
								.build())
						.build();
				break;
			}
			case CARRIER -> {
				String carrierId = atts.getValue(ID);
				Gbl.assertNotNull(carrierId);
				currentCarrier = CarrierUtils.createCarrier(Id.create(carrierId, Carrier.class));
				break;
			}
			case ATTRIBUTES -> {
				switch (context.peek()) {
					case CARRIER -> currAttributes = currentCarrier.getAttributes();
					case SHIPMENT -> currAttributes = currentShipment.getAttributes();
					case LSP -> currAttributes = currentLsp.getAttributes();
					default ->
							throw new RuntimeException("could not derive context for attributes. context=" + context.peek());
				}
				attributesReader.startTag(name, atts, context, currAttributes);
				break;
			}
			case ATTRIBUTE -> {
				currAttributes = currentCarrier.getAttributes();
				Gbl.assertNotNull(currAttributes);
				attributesReader.startTag(name, atts, context, currAttributes);
				break;
			}
			case CAPABILITIES -> {
				String fleetSize = atts.getValue(FLEET_SIZE);
				Gbl.assertNotNull(fleetSize);
				this.capabilityBuilder = CarrierCapabilities.Builder.newInstance();
				if (fleetSize.toUpperCase().equals(CarrierCapabilities.FleetSize.FINITE.toString())) {
					this.capabilityBuilder.setFleetSize(CarrierCapabilities.FleetSize.FINITE);
				} else {
					this.capabilityBuilder.setFleetSize(CarrierCapabilities.FleetSize.INFINITE);
				}
				break;
			}
			case VEHICLE -> {
				String vehicleId = atts.getValue(ID);
				Gbl.assertNotNull(vehicleId);

				String depotLinkId = atts.getValue(DEPOT_LINK_ID);
				Gbl.assertNotNull(depotLinkId);

				String typeId = atts.getValue(TYPE_ID);
				Gbl.assertNotNull(typeId);
				VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create(typeId, VehicleType.class));
				Gbl.assertNotNull(vehicleType);

				CarrierVehicle.Builder vehicleBuilder = CarrierVehicle.Builder.newInstance(Id.create(vehicleId, Vehicle.class), Id.create(depotLinkId, Link.class), vehicleType);
				String startTime = atts.getValue(VEHICLE_EARLIEST_START);
				if (startTime != null) vehicleBuilder.setEarliestStart(parseTimeToDouble(startTime));
				String endTime = atts.getValue(VEHICLE_LATEST_END);
				if (endTime != null) vehicleBuilder.setLatestEnd(parseTimeToDouble(endTime));

				CarrierVehicle vehicle = vehicleBuilder.build();
				capabilityBuilder.addVehicle(vehicle);
				break;
			}
			case SHIPMENT -> {
				String shipmentId = atts.getValue(ID);
				Gbl.assertNotNull(shipmentId);
				Id<LSPShipment> id = Id.create(shipmentId, LSPShipment.class);

				String from = atts.getValue(FROM);
				Gbl.assertNotNull(from);
				String to = atts.getValue(TO);
				Gbl.assertNotNull(to);
				String sizeString = atts.getValue(SIZE);
				Gbl.assertNotNull(sizeString);
				int size = getInt(sizeString);
				ShipmentUtils.LSPShipmentBuilder shipmentBuilder = ShipmentUtils.LSPShipmentBuilder.newInstance(id);

				shipmentBuilder.setFromLinkId(Id.createLinkId(from));
				shipmentBuilder.setToLinkId(Id.createLinkId(to));
				shipmentBuilder.setCapacityDemand(size);

				String startPickup = atts.getValue(START_PICKUP);
				String endPickup = atts.getValue(END_PICKUP);
				String startDelivery = atts.getValue(START_DELIVERY);
				String endDelivery = atts.getValue(END_DELIVERY);
				String pickupServiceTime = atts.getValue(PICKUP_SERVICE_TIME);
				String deliveryServiceTime = atts.getValue(DELIVERY_SERVICE_TIME);

				if (startPickup != null && endPickup != null)
					shipmentBuilder.setStartTimeWindow(TimeWindow.newInstance(parseTimeToDouble(startPickup), parseTimeToDouble(endPickup)));
				if (startDelivery != null && endDelivery != null)
					shipmentBuilder.setEndTimeWindow(TimeWindow.newInstance(parseTimeToDouble(startDelivery), parseTimeToDouble(endDelivery)));
				if (pickupServiceTime != null)
					shipmentBuilder.setPickupServiceTime(parseTimeToDouble(pickupServiceTime));
				if (deliveryServiceTime != null)
					shipmentBuilder.setDeliveryServiceTime(parseTimeToDouble(deliveryServiceTime));

				currentShipment = shipmentBuilder.build();
				currentLsp.getShipments().add(currentShipment);
				break;
			}

			case PLAN -> {
				score = Double.valueOf(atts.getValue(SCORE));
				Gbl.assertNotNull(score);
				chainId = atts.getValue(CHAIN_ID);
				Gbl.assertNotNull(chainId);
				String selected = atts.getValue(SELECTED);
				Gbl.assertNotNull(selected);
				break;
			}

			case RESOURCES -> {
				break;
			}

			case RESOURCE -> {
				String resourceId = atts.getValue(ID);
				Gbl.assertNotNull(resourceId);
				break;
			}

			case SHIPMENT_PLAN -> {
				String shipmentId = atts.getValue(ID);
				Gbl.assertNotNull(shipmentId);
				break;
			}

			case ELEMENT -> {
				String type = atts.getValue(TYPE);
				Gbl.assertNotNull(type);

				String startTime = atts.getValue(START_TIME);
				Gbl.assertNotNull(startTime);

				String endTime = atts.getValue(END_TIME);
				Gbl.assertNotNull(endTime);

				String resource = atts.getValue(RESOURCE);
				Gbl.assertNotNull(resource);

				break;
			}
		}
	}

	@Override
	public void endTag(String name, String content, Stack<String> context) {
		switch (name) {
			case LSP -> {
                Gbl.assertNotNull(currentLsp);
                Gbl.assertNotNull(lsPs);
                Gbl.assertNotNull(lsPs.getLSPs());
				lsPs.getLSPs().put(currentLsp.getId(), currentLsp);
				currentLsp = null;
			}
			case CARRIER -> {
                Gbl.assertNotNull(currentCarrier);
                Gbl.assertNotNull(carriers);
                Gbl.assertNotNull(carriers.getCarriers());
				carriers.getCarriers().put(currentCarrier.getId(), currentCarrier);
				LSPResource lspResource = null;
				switch (UsecaseUtils.getCarrierType(currentCarrier)) {

					case collectionCarrier -> {
						lspResource = UsecaseUtils.CollectionCarrierResourceBuilder.newInstance(Id.create(currentCarrier.getId(), LSPResource.class), null)
								.setCarrier(currentCarrier)
								.setCollectionScheduler(UsecaseUtils.createDefaultCollectionCarrierScheduler())
								.build();
					}
					case mainRunCarrier -> {
						lspResource = UsecaseUtils.MainRunCarrierResourceBuilder.newInstance(Id.create(currentCarrier.getId(), LSPResource.class), null)
								.setCarrier(currentCarrier)
								.setMainRunCarrierScheduler(UsecaseUtils.createDefaultMainRunCarrierScheduler())
								.build();


					}
					case distributionCarrier -> {
						lspResource = UsecaseUtils.DistributionCarrierResourceBuilder.newInstance(Id.create(currentCarrier.getId(), LSPResource.class), null)
								.setCarrier(currentCarrier)
								.setDistributionScheduler(UsecaseUtils.createDefaultDistributionCarrierScheduler())
								.build();

						LogisticChainElement directCarrierLSE = LSPUtils.LogisticChainElementBuilder.newInstance(Id.create("directCarrierLSE", LogisticChainElement.class))
							.setResource(lspResource)
							.build();

						LogisticChain solution_direct = LSPUtils.LogisticChainBuilder.newInstance(Id.create("id", LogisticChain.class))
								.addLogisticChainElement(directCarrierLSE)
								.build();

						final ShipmentAssigner singleSolutionShipmentAssigner = UsecaseUtils.createSingleLogisticChainShipmentAssigner();
						currentPlan = LSPUtils.createLSPPlan()
								.addLogisticChain(solution_direct)
								.setAssigner(singleSolutionShipmentAssigner);

						currentPlan.setScore(100.0);

						//List<LSPPlan> lspPlans = new ArrayList<>();


					}
					default ->
							throw new IllegalStateException("Unexpected value: " + currentCarrier.getAttributes().toString());
				}
				Gbl.assertNotNull(lspResource);
				currentLsp.getResources().add(lspResource);
				currentCarrier = null;
			}

			case HUB -> {
				currentLsp.getResources().add(hubResource);
				LSPUtils.setFixedCost(hubResource, currentHubFixedCost);
				hubResource = null;
				currentHubFixedCost = null;
				currentHubLocation = null;
				currentHubId = null;
			}

			case CAPABILITIES -> currentCarrier.setCarrierCapabilities(capabilityBuilder.build());
			case ATTRIBUTE -> attributesReader.endTag(name, content, context);
			case SHIPMENT -> this.currentShipment = null;

			case PLAN -> currentLsp.addPlan(currentPlan);
		}
	}

	private double parseTimeToDouble (String timeString){
		if (timeString.contains(":")) {
			return Time.parseTime(timeString);
		} else {
			return Double.parseDouble(timeString);
		}
	}

	private int getInt (String value){
		return Integer.parseInt(value);
	}


}
