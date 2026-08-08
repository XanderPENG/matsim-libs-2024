package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.core.controler.events.ReplanningEvent;
import org.matsim.core.controler.listener.ReplanningListener;

/** Activates an AF and its Receiver context before the regular carrier/receiver listeners run. */
public final class MutableAfReplanningCoordinator implements ReplanningListener {

	private final MutableAfLearningStore learningStore;

	@Inject
	public MutableAfReplanningCoordinator(MutableAfLearningStore learningStore) {
		this.learningStore = learningStore;
	}

	@Override
	public double priority() {
		return 200.0;
	}

	@Override
	public void notifyReplanning(ReplanningEvent event) {
		learningStore.prepareReplanning(event.getIteration());
	}
}
