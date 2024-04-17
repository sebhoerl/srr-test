for distance in 100 200 400 800; do
  /scratch/sebastian.horl/jdk17/bin/java -cp test-0.0.1-SNAPSHOT.jar -Xmx200g test.BenchmarkSRR --schedule-path schedule.xml.gz --network-path network.xml.gz --distance ${distance} --output-path benchmark_${distance}.json
done
