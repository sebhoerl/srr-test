package test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.Counter;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import com.fasterxml.jackson.core.exc.StreamWriteException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig.RaptorTransferCalculation;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;

public class BenchmarkSRR {
	public static void main(String[] args)
			throws ConfigurationException, InterruptedException, StreamWriteException, DatabindException, IOException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("schedule-path", "network-path", "distance", "routes", "calculation", "output-path",
						"seed", "threads") //
				.build();

		double distance = Double.parseDouble(cmd.getOptionStrict("distance"));
		int routes = Integer.parseInt(cmd.getOptionStrict("routes"));
		RaptorTransferCalculation calculation = RaptorTransferCalculation.valueOf(cmd.getOptionStrict("calculation"));
		int seed = Integer.parseInt(cmd.getOptionStrict("seed"));
		int threads = Integer.parseInt(cmd.getOptionStrict("threads"));

		Config config = ConfigUtils.createConfig(new SwissRailRaptorConfigGroup());
		Scenario scenario = ScenarioUtils.createScenario(config);

		new TransitScheduleReader(scenario).readFile(cmd.getOptionStrict("schedule-path"));
		new MatsimNetworkReader(scenario.getNetwork()).readFile(cmd.getOptionStrict("network-path"));

		RaptorStaticConfig staticConfig = new RaptorStaticConfig();
		staticConfig.setBeelineWalkSpeed(5.0 / 3.6);
		staticConfig.setBeelineWalkDistanceFactor(1.3);
		staticConfig.setBeelineWalkConnectionDistance(distance);
		staticConfig.setTransferCalculation(calculation);

		List<Item> items = new LinkedList<>();
		AtomicBoolean running = new AtomicBoolean(true);

		AtomicBoolean loading = new AtomicBoolean(true);
		AtomicBoolean routing = new AtomicBoolean(false);
		AtomicInteger finishedRoutes = new AtomicInteger();

		Thread thread = new Thread(() -> {
			long startTime = System.nanoTime();

			while (running.get()) {
				items.add(new Item((System.nanoTime() - startTime) * 1e-9,
						Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0, loading.get(), routing.get(),
						finishedRoutes.get()));
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
				}
			}
		});

		thread.start();

		loading.set(true);
		SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(), null, staticConfig,
				scenario.getNetwork(), null);
		loading.set(false);

		List<TransitStopFacility> facilities = new ArrayList<>(scenario.getTransitSchedule().getFacilities().values());
		Collections.sort(facilities,
				(a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getId().toString(), b.getId().toString()));

		Counter counter = new Counter("Processed ", " routes");
		Random random = new Random(seed);

		if (threads < 2) {
			SwissRailRaptor raptor = new SwissRailRaptor.Builder(data, config).build();

			routing.set(true);
			for (int routeIndex = 0; routeIndex < routes; routeIndex++) {
				TransitStopFacility fromFacility = facilities.get(random.nextInt(facilities.size()));
				TransitStopFacility toFacility = facilities.get(random.nextInt(facilities.size()));
				double departureTime = (6.0 + 16.0 * random.nextDouble()) * 3600.0;

				raptor.calcRoute(DefaultRoutingRequest.of(fromFacility, toFacility, departureTime, null, null));

				counter.incCounter();
				finishedRoutes.incrementAndGet();
			}
			routing.set(false);
		} else {
			LinkedList<Task> tasks = new LinkedList<>();
			for (int routeIndex = 0; routeIndex < routes; routeIndex++) {
				double departureTime = (6.0 + 16.0 * random.nextDouble()) * 3600.0;
				tasks.add(
						new Task(random.nextInt(facilities.size()), random.nextInt(facilities.size()), departureTime));
			}

			List<Thread> threadList = new LinkedList<>();
			for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
				threadList.add(new Thread(() -> {
					SwissRailRaptor raptor = new SwissRailRaptor.Builder(data, config).build();

					while (true) {
						Task task = null;

						synchronized (tasks) {
							if (tasks.size() == 0) {
								return;
							}

							task = tasks.poll();
						}

						raptor.calcRoute(DefaultRoutingRequest.of(facilities.get(task.fromFacilityIndex),
								facilities.get(task.toFacilityIndex), task.departureTime, null, null));

						synchronized (counter) {
							counter.incCounter();
						}
					}
				}));
			}

			routing.set(true);
			for (Thread instance : threadList) {
				instance.start();
			}

			for (Thread instance : threadList) {
				instance.join();
			}
			running.set(false);
		}

		thread.join();

		new ObjectMapper().writeValue(new File(cmd.getOptionStrict("output-path")), items);
	}

	record Item(double runtime_s, double memory_mb, boolean loading, boolean routing, int routes) {
	}

	record Task(int fromFacilityIndex, int toFacilityIndex, double departureTime) {
	}
}