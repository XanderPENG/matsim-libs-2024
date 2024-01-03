/*
 *  *********************************************************************** *
 *  * project: org.matsim.*
 *  * *********************************************************************** *
 *  *                                                                         *
 *  * copyright       : (C) 2022 by the members listed in the COPYING,        *
 *  *                   LICENSE and WARRANTY file.                            *
 *  * email           : info at matsim dot org                                *
 *  *                                                                         *
 *  * *********************************************************************** *
 *  *                                                                         *
 *  *   This program is free software; you can redistribute it and/or modify  *
 *  *   it under the terms of the GNU General Public License as published by  *
 *  *   the Free Software Foundation; either version 2 of the License, or     *
 *  *   (at your option) any later version.                                   *
 *  *   See also COPYING, LICENSE and WARRANTY file                           *
 *  *                                                                         *
 *  * ***********************************************************************
 */

package org.matsim.freight.logistics.example.lsp.initialPlans;

import static org.junit.Assert.fail;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.Test;
import org.matsim.testcases.MatsimTestUtils;

public class ExampleTwoEchelonGridTest {
	private static final Logger log = LogManager.getLogger(ExampleTwoEchelonGridTest.class);
	@RegisterExtension
	public final MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void testForRuntimeExceptions() {
		try {
			ExampleTwoEchelonGrid.main(new String[]{
					"--config:controler.outputDirectory=" + utils.getOutputDirectory()
					, "--config:controler.lastIteration=2"
			});

		} catch (Exception ee) {
			log.fatal(ee);
			fail();
		}
	}

}
