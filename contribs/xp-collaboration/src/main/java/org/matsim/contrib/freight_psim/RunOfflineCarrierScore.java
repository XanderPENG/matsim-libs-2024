package org.matsim.contrib.freight_psim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.router.costcalculators.RandomizingTimeDistanceTravelDisutilityFactory;
import org.matsim.core.router.DijkstraFactory;

public class RunOfflineCarrierScore {
	public static void main(String[] args) {
		Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimNetworkReader(sc.getNetwork()).readFile("examples/scenarios/freight-chessboard-9x9/grid9x9.xml");

		EventsManager em = EventsUtils.createEventsManager();
		TravelTimeCalculatorConfigGroup ttCfg =
			ConfigUtils.addOrGetModule(sc.getConfig(), TravelTimeCalculatorConfigGroup.class);

		ttCfg.setTraveltimeBinSize(900);
		sc.getConfig().routing().setRoutingRandomness(0.0); // Disable routing randomness
		TravelTimeCalculator ttc = new TravelTimeCalculator(sc.getNetwork(), ttCfg);
		em.addHandler(ttc);
		new MatsimEventsReader(em).readFile("output/basic-carrier-receiver-example/ITERS/it.0/0.events.xml.gz");
		TravelTime tt = ttc.getLinkTravelTimes();

		// 2) 路由器（时间+距离代价）
		RandomizingTimeDistanceTravelDisutilityFactory disutilityFactory =
			new RandomizingTimeDistanceTravelDisutilityFactory("car", sc.getConfig());
		TravelDisutility disutil = disutilityFactory.createTravelDisutility(tt);
		DijkstraFactory routerFactory = new DijkstraFactory();
		LeastCostPathCalculator router = routerFactory.createPathCalculator(sc.getNetwork(), disutil, tt);

		// 3) 评估一条 OD（可按你的 carrier tour 循环多个 OD）
		double dep = 7 * 3600; // 早7点出发
		Node from = sc.getNetwork().getNodes().get(Id.createNodeId("(0,8)"));
		Node to   = sc.getNetwork().getNodes().get(Id.createNodeId("(5,5)"));
		LeastCostPathCalculator.Path path = router.calcLeastCostPath(from, to, dep, null, null);

		double t = dep, dist = 0;
		for (Link l : path.links) {
			t += tt.getLinkTravelTime(l, t, null, null);
			dist += l.getLength();
		}
		double travelTime = t - dep;

// 4) 你的评分（示例：每小时 60€, 每 km 0.6€）
		double score = - (travelTime/3600.0 * 60.0 + dist/1000.0 * 0.6);
		System.out.println("TT=" + travelTime + "s, Dist=" + dist + "m, Score=" + score);
	}
}
