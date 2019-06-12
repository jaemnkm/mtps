package org.radixdlt.explorer.metrics;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.PublishSubject;
import org.radixdlt.explorer.metrics.model.Metrics;
import org.radixdlt.explorer.system.TestState;
import org.radixdlt.explorer.system.model.SystemInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.radixdlt.explorer.system.TestState.STARTED;
import static org.radixdlt.explorer.system.TestState.UNKNOWN;

/**
 * Enables means of getting calculated Radix network throughput metrics.
 */
class MetricsProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("org.radixdlt.explorer");

    private final PublishSubject<Metrics> subject;
    private final CompositeDisposable disposables;
    private final Object calculationLock;
    private final Path metricsDumpPath;
    private final long maxShards;

    private TestState testState;
    private boolean isStarted;

    private Metrics calculatedMetrics;
    private long peakTps;
    private long averageTps;
    private long aggregatedAverageTps;
    private long calculationIterations;


    /**
     * Creates a new instance of this class, allowing the caller to inject
     * any external dependencies and configurations.
     *
     * @param maxShards The maximum number of shards served by the entire
     *                  network.
     */
    MetricsProvider(long maxShards, Path metricsDumpPath) {
        this.subject = PublishSubject.create();
        this.disposables = new CompositeDisposable();
        this.calculationLock = new Object();
        this.maxShards = maxShards;
        this.isStarted = false;
        this.peakTps = 0L;
        this.averageTps = 0L;
        this.aggregatedAverageTps = 0L;
        this.calculationIterations = 0L;
        this.calculatedMetrics = null;
        this.testState = UNKNOWN;
        this.metricsDumpPath = metricsDumpPath;
    }

    /**
     * Returns the last calculated throughput metrics data.
     *
     * @return The calculated metrics.
     */
    Metrics getMetrics() {
        return calculatedMetrics;
    }

    /**
     * Returns an observable that asynchronously will provide updated
     * metrics calculations. This observable will be null until {@link
     * #start(Observable, Observable)} or after {@link #stop()} has been
     * called.
     *
     * @return The observable or null.
     */
    Observable<Metrics> getMetricsObserver() {
        return subject;
    }

    /**
     * Starts the calculation of throughput metrics on a periodic basis.
     *
     * @param systemInfoObserver The callback that provides information on
     *                           system info changes.
     */
    synchronized void start(Observable<Map<String, SystemInfo>> systemInfoObserver, Observable<TestState> testStateObserver) {
        if (!isStarted) {
            isStarted = true;
            disposables.add(testStateObserver.subscribe(this::maybeResetMetrics));
            disposables.add(systemInfoObserver.subscribe(this::calculateMetrics));
        }
    }

    /**
     * Stops the calculation of throughput metrics.
     */
    synchronized void stop() {
        if (isStarted) {
            isStarted = false;
            disposables.clear();
            subject.onComplete();
        }
    }

    /**
     * Resets all calculated metrics (including average and peak TPS) if
     * the test state is about to transition to a started state.
     *
     * @param newTestState The new test state.
     */
    private void maybeResetMetrics(TestState newTestState) {
        if (testState != STARTED && newTestState == STARTED) {
            synchronized (calculationLock) {
                this.peakTps = 0L;
                this.averageTps = 0L;
                this.aggregatedAverageTps = 0L;
                this.calculationIterations = 0L;
                this.calculatedMetrics = null;
            }

            if (metricsDumpPath != null) {
                try {
                    byte[] data = Metrics.DATA_HEADLINE.getBytes(UTF_8);
                    Files.write(metricsDumpPath, data, CREATE, WRITE);
                } catch (Exception e) {
                    LOGGER.info("Couldn't reset metrics dump file: " + metricsDumpPath, e);
                }
            }
        }

        testState = newTestState;
    }

    /**
     * Composes the final throughput metrics based on the provided system
     * info.
     *
     * @param info The system info to base the calculations on.
     */
    private void calculateMetrics(Map<String, SystemInfo> info) {
        if (info == null || info.isEmpty() || testState != STARTED) {
            return;
        }

        double aggregatedTps = 0.0;
        double aggregatedProgress = 0.0;
        int nodeCount = 0;

        for (Map.Entry<String, SystemInfo> entry : info.entrySet()) {
            SystemInfo current = entry.getValue();
            double range = current.getShardSize();
            if (range <= 0.0) {
                continue;
            }

            // This assumes the "storingPerShard" attribute is a TPS representation
            double storing = current.getStoringPerShard();

            // This assumes the "storedPerShard" attribute is an absolute number
            // of stored atoms.
            double stored = current.getStoredPerShard();
            double shardSpaceFractionForNode = maxShards / range;

            nodeCount++;
            aggregatedTps += (storing * shardSpaceFractionForNode);
            aggregatedProgress += (stored * shardSpaceFractionForNode);
        }

        long tps = Math.round(aggregatedTps / nodeCount);
        long progress = Math.round(aggregatedProgress / nodeCount);

        synchronized (calculationLock) {
            calculationIterations++;
            long seconds = (System.currentTimeMillis() - testState.getStartTimestamp()) / 1000 + 1;
            assert seconds > 0;
            averageTps = Math.round(progress / seconds);
            peakTps = Math.max(peakTps, tps);
            calculatedMetrics = new Metrics(tps, progress, averageTps, peakTps);
        }

        if (metricsDumpPath != null) {
            try {
                String line = calculatedMetrics.toString();
                byte[] data = line.getBytes(UTF_8);
                Files.write(metricsDumpPath, data, CREATE, WRITE, APPEND);
            } catch (Exception e) {
                LOGGER.info("Couldn't dump metrics to file: " + metricsDumpPath, e);
            }
        }

        subject.onNext(calculatedMetrics);
    }

}