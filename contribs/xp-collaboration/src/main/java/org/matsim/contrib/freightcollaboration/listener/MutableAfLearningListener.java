package org.matsim.contrib.freightcollaboration.listener;

import com.google.inject.Inject;
import org.matsim.contrib.freightcollaboration.learning.MutableAfLearningStore;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

/** Captures baseline and factor-conditioned observations after all scoring has completed. */
public final class MutableAfLearningListener implements StartupListener, IterationEndsListener {

	private final MutableAfLearningStore learningStore;

	@Inject
	public MutableAfLearningListener(MutableAfLearningStore learningStore) {
		this.learningStore = learningStore;
	}

	@Override
	public double priority() {
		return 100.0;
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		learningStore.initialize();
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		learningStore.observeIterationEnd(event.getIteration());
	}
}
