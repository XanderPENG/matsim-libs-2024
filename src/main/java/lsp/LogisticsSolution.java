package lsp;

import java.util.Collection;

import org.matsim.api.core.v01.Id;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.handler.EventHandler;

import lsp.functions.LSPInfo;
import lsp.shipment.LSPShipment;
import lsp.controler.LSPSimulationTracker;


/**
 * A LogisticsSolution can be seen as a representative of a
 * transport chain. It consists of several chain links that implement the interface
 * LogisticsSolutionElement. The latter is more a logical than a physical entity.
 * Physical entities, in turn, are housed inside classes that implement the interface
 * Resource. This introduction of an intermediate layer allows physical Resources
 * to be used by several LogisticsSolutions and thus transport chains.
 */
public interface LogisticsSolution {

	Id<LogisticsSolution> getId();
	
	void setLSP(LSP lsp);
	
	LSP getLSP();
	
	Collection<LogisticsSolutionElement> getSolutionElements();
	
	Collection<LSPShipment> getShipments();
	
	void assignShipment(LSPShipment shipment);
	
	Collection<LSPInfo> getInfos();
	
    Collection <EventHandler> getEventHandlers();
        
    void addSimulationTracker(LSPSimulationTracker tracker);
    
    Collection<LSPSimulationTracker> getSimulationTrackers();
    
    void setEventsManager(EventsManager eventsManager);
}
