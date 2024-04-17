package test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import com.fasterxml.jackson.core.exc.StreamWriteException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;

public class BenchmarkSRR {
	public static void main(String[] args)
			throws ConfigurationException, InterruptedException, StreamWriteException, DatabindException, IOException {
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("schedule-path", "network-path", "distance", "output-path") //
				.build();

		double distance = Double.parseDouble(cmd.getOptionStrict("distance"));

		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);

		new TransitScheduleReader(scenario).readFile(cmd.getOptionStrict("schedule-path"));
		new MatsimNetworkReader(scenario.getNetwork()).readFile(cmd.getOptionStrict("network-path"));

		RaptorStaticConfig staticConfig = new RaptorStaticConfig();
		staticConfig.setBeelineWalkSpeed(5.0 / 3.6);
		staticConfig.setBeelineWalkDistanceFactor(1.3);
		staticConfig.setBeelineWalkConnectionDistance(distance);

		List<Item> items = new LinkedList<>();
		AtomicBoolean running = new AtomicBoolean(true);

		Thread thread = new Thread(() -> {
			long startTime = System.nanoTime();

			while (running.get()) {
				items.add(new Item((System.nanoTime() - startTime) * 1e-9,
						Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0));
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
				}
			}
		});

		thread.start();

		SwissRailRaptorData.create(scenario.getTransitSchedule(), null, staticConfig, scenario.getNetwork(), null);

		running.set(false);
		thread.join();

		new ObjectMapper().writeValue(new File(cmd.getOptionStrict("output-path")), items);
	}

	record Item(double runtime_s, double memory_mb) {
	}
}