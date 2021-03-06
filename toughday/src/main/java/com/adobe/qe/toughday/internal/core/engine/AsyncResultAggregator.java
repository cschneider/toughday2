/*
Copyright 2015 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
*/
package com.adobe.qe.toughday.internal.core.engine;

import com.adobe.qe.toughday.api.core.AbstractTest;
import com.adobe.qe.toughday.api.core.MetricResult;
import com.adobe.qe.toughday.api.core.Publisher;
import com.adobe.qe.toughday.api.core.RunMap;
import com.adobe.qe.toughday.internal.core.RunMapImpl;
import com.adobe.qe.toughday.metrics.Metric;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker for aggregating and publishing benchmarks.
 */
public class AsyncResultAggregator extends AsyncEngineWorker {
    private final Engine engine;
    private RunMode.RunContext context;

    /**
     * Constructor.
     * @param context list of test workers from this engine.
     */
    public AsyncResultAggregator(Engine engine, RunMode.RunContext context) {
        this.engine = engine;
        this.context = context;
    }

    /**
     * Method aggregating results.
     */
    public boolean aggregateResults() {
        Collection<RunMap> localRunMaps = context.getRunMaps();
        synchronized (localRunMaps) {
            for (RunMap localRunMap : localRunMaps) {
                Map<AbstractTest, Long> counts = engine.getPublishMode().aggregateAndReinitialize(localRunMap);

                Map<AbstractTest, AtomicLong> globalCounts = engine.getCounts();
                for (Map.Entry<AbstractTest, AtomicLong> entry : globalCounts.entrySet()) {
                    globalCounts.get(entry.getKey()).addAndGet(counts.get(entry.getKey()));
                }
            }
        }
        return context.isRunFinished();
    }

    // creates a map containing the results of the metrics that are going to be published
    public Map<String, List<MetricResult>> filterResults() {
        Map<String, List<MetricResult>> results = new LinkedHashMap<>();
        RunMapImpl runMap = engine.getGlobalRunMap();
        Collection<AbstractTest> tests = runMap.getTests();
        for (AbstractTest testInstance : tests) {
            List<MetricResult> metricResults = new ArrayList<>();
            for (Metric metric : engine.getGlobalArgs().getMetrics()) {
                metricResults.add(metric.getResult(runMap.getRecord(testInstance)));
            }
            results.put(testInstance.getFullName(), metricResults);
        }

        return results;
    }

    /**
     * Implementation of the Runnable interface.
     */
    @Override
    public void run() {
        try {
            long elapsed = 0;
            while (!isFinished()) {

                long sleepMillis = Engine.RESULT_AGGREATION_DELAY - elapsed;
                if(sleepMillis > 0) {
                    Thread.sleep(sleepMillis);
                } else {
                    Engine.LOG.warn("Publishers are taking more than 1 second to complete." +
                            " This may affect the results that you are seeing.");
                }

                long start = System.nanoTime();
                boolean testsFinished = aggregateResults();
                if (testsFinished) {
                    this.finishExecution();
                }
                Map<String, List<MetricResult>> results = filterResults();
                engine.getPublishMode().publish(engine.getGlobalRunMap().getCurrentTestResults());
                engine.getPublishMode().publishIntermediateResults(results);
                ((RunMapImpl)engine.getPublishMode().getGlobalRunMap()).clearCurrentTestResults();
                elapsed = (System.nanoTime() - start) / 1000000l;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Engine.LOG.error("InterruptedException(s) should not reach this point", e);
        } catch (Throwable e) {
            Engine.LOG.error("Unexpected exception caught", e);
        } finally {
            // signal all publishers that they are stopped.
            // any local threads inside the publishers would have to be stopped
            stopPublishers();
        }
        aggregateResults();
    }

    private void stopPublishers() {
        for(Publisher publisher : engine.getGlobalArgs().getPublishers()) {
            publisher.finish();
        }
    }

}
