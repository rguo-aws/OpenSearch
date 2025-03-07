/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.pipeline;

import org.opensearch.Version;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.script.Script;
import org.opensearch.search.DocValueFormat;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.InternalAggregations;
import org.opensearch.search.aggregations.InternalMultiBucketAggregation;
import org.opensearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.opensearch.search.aggregations.bucket.histogram.HistogramFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.opensearch.search.aggregations.pipeline.BucketHelpers.resolveBucketValue;

/**
 * This pipeline aggregation gives the user the ability to script functions that "move" across a window
 * of data, instead of single data points.  It is the scripted version of MovingAvg pipeline agg.
 *
 * Through custom script contexts, we expose a number of convenience methods:
 *
 *  - max
 *  - min
 *  - sum
 *  - unweightedAvg
 *  - linearWeightedAvg
 *  - ewma
 *  - holt
 *  - holtWintersMovAvg
 *
 *  The user can also define any arbitrary logic via their own scripting, or combine with the above methods.
 */
public class MovFnPipelineAggregator extends PipelineAggregator {
    private final DocValueFormat formatter;
    private final BucketHelpers.GapPolicy gapPolicy;
    private final Script script;
    private final String bucketsPath;
    private final int window;
    private final int shift;

    MovFnPipelineAggregator(String name, String bucketsPath, Script script, int window, int shift, DocValueFormat formatter,
                            BucketHelpers.GapPolicy gapPolicy, Map<String, Object> metadata) {
        super(name, new String[]{bucketsPath}, metadata);
        this.bucketsPath = bucketsPath;
        this.script = script;
        this.formatter = formatter;
        this.gapPolicy = gapPolicy;
        this.window = window;
        this.shift = shift;
    }

    public MovFnPipelineAggregator(StreamInput in) throws IOException {
        super(in);
        script = new Script(in);
        formatter = in.readNamedWriteable(DocValueFormat.class);
        gapPolicy = BucketHelpers.GapPolicy.readFrom(in);
        bucketsPath = in.readString();
        window = in.readInt();
        if (in.getVersion().onOrAfter(Version.V_7_4_0)) {
            shift = in.readInt();
        } else {
            shift = 0;
        }
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        script.writeTo(out);
        out.writeNamedWriteable(formatter);
        gapPolicy.writeTo(out);
        out.writeString(bucketsPath);
        out.writeInt(window);
        if (out.getVersion().onOrAfter(Version.V_7_4_0)) {
            out.writeInt(shift);
        }
    }

    @Override
    public String getWriteableName() {
        return MovFnPipelineAggregationBuilder.NAME;
    }

    @Override
    public InternalAggregation reduce(InternalAggregation aggregation, InternalAggregation.ReduceContext reduceContext) {
        InternalMultiBucketAggregation<? extends InternalMultiBucketAggregation, ? extends InternalMultiBucketAggregation.InternalBucket>
            histo = (InternalMultiBucketAggregation<? extends InternalMultiBucketAggregation, ? extends
            InternalMultiBucketAggregation.InternalBucket>) aggregation;
        List<? extends InternalMultiBucketAggregation.InternalBucket> buckets = histo.getBuckets();
        HistogramFactory factory = (HistogramFactory) histo;

        List<MultiBucketsAggregation.Bucket> newBuckets = new ArrayList<>();

        // Initialize the script
        MovingFunctionScript.Factory scriptFactory = reduceContext.scriptService().compile(script, MovingFunctionScript.CONTEXT);
        Map<String, Object> vars = new HashMap<>();
        if (script.getParams() != null) {
            vars.putAll(script.getParams());
        }

        MovingFunctionScript executableScript = scriptFactory.newInstance();

        List<Double> values = buckets.stream()
            .map(b -> resolveBucketValue(histo, b, bucketsPaths()[0], gapPolicy))
            .filter(v -> v != null && v.isNaN() == false)
            .collect(Collectors.toList());

        int index = 0;
        for (InternalMultiBucketAggregation.InternalBucket bucket : buckets) {
            Double thisBucketValue = resolveBucketValue(histo, bucket, bucketsPaths()[0], gapPolicy);

            // Default is to reuse existing bucket.  Simplifies the rest of the logic,
            // since we only change newBucket if we can add to it
            MultiBucketsAggregation.Bucket newBucket = bucket;

            if (thisBucketValue != null && thisBucketValue.isNaN() == false) {

                // The custom context mandates that the script returns a double (not Double) so we
                // don't need null checks, etc.
                int fromIndex = clamp(index - window + shift, values);
                int toIndex = clamp(index + shift, values);
                double movavg = executableScript.execute(
                    vars,
                    values.subList(fromIndex, toIndex).stream()
                        .mapToDouble(Double::doubleValue)
                        .toArray()
                );

                List<InternalAggregation> aggs = StreamSupport
                    .stream(bucket.getAggregations().spliterator(), false)
                    .map(InternalAggregation.class::cast)
                    .collect(Collectors.toList());
                aggs.add(new InternalSimpleValue(name(), movavg, formatter, metadata()));
                newBucket = factory.createBucket(factory.getKey(bucket), bucket.getDocCount(), InternalAggregations.from(aggs));
                index++;
            }
            newBuckets.add(newBucket);
        }

        return factory.createAggregation(newBuckets);
    }

    private int clamp(int index, List<Double> list) {
        if (index < 0) {
            return 0;
        }
        if (index > list.size()) {
            return list.size();
        }
        return index;
    }
}
