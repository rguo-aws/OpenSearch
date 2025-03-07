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

package org.opensearch.search.aggregations.bucket.histogram;

import org.opensearch.Version;
import org.opensearch.common.ParseField;
import org.opensearch.common.Rounding;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.ObjectParser;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.aggregations.AggregationBuilder;
import org.opensearch.search.aggregations.AggregatorFactories.Builder;
import org.opensearch.search.aggregations.AggregatorFactory;
import org.opensearch.search.aggregations.MultiBucketConsumerService;
import org.opensearch.search.aggregations.support.CoreValuesSourceType;
import org.opensearch.search.aggregations.support.ValuesSourceAggregationBuilder;
import org.opensearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.opensearch.search.aggregations.support.ValuesSourceConfig;
import org.opensearch.search.aggregations.support.ValuesSourceRegistry;
import org.opensearch.search.aggregations.support.ValuesSourceType;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class AutoDateHistogramAggregationBuilder extends ValuesSourceAggregationBuilder<AutoDateHistogramAggregationBuilder> {

    public static final String NAME = "auto_date_histogram";
    public static final ValuesSourceRegistry.RegistryKey<AutoDateHistogramAggregatorSupplier> REGISTRY_KEY =
        new ValuesSourceRegistry.RegistryKey<>(NAME, AutoDateHistogramAggregatorSupplier.class);

    private static final ParseField NUM_BUCKETS_FIELD = new ParseField("buckets");
    private static final ParseField MINIMUM_INTERVAL_FIELD = new ParseField("minimum_interval");

    public static final ObjectParser<AutoDateHistogramAggregationBuilder, String> PARSER =
        ObjectParser.fromBuilder(NAME, AutoDateHistogramAggregationBuilder::new);
    static {
        ValuesSourceAggregationBuilder.declareFields(PARSER, true, true, true);
        PARSER.declareInt(AutoDateHistogramAggregationBuilder::setNumBuckets, NUM_BUCKETS_FIELD);
        PARSER.declareStringOrNull(AutoDateHistogramAggregationBuilder::setMinimumIntervalExpression, MINIMUM_INTERVAL_FIELD);
    }

    public static final Map<Rounding.DateTimeUnit, String> ALLOWED_INTERVALS = new HashMap<>();
    static {
        ALLOWED_INTERVALS.put(Rounding.DateTimeUnit.YEAR_OF_CENTURY, "year");
        ALLOWED_INTERVALS.put(Rounding.DateTimeUnit.MONTH_OF_YEAR, "month");
        ALLOWED_INTERVALS.put(Rounding.DateTimeUnit.DAY_OF_MONTH, "day");
        ALLOWED_INTERVALS.put( Rounding.DateTimeUnit.HOUR_OF_DAY, "hour");
        ALLOWED_INTERVALS.put(Rounding.DateTimeUnit.MINUTES_OF_HOUR, "minute");
        ALLOWED_INTERVALS.put(Rounding.DateTimeUnit.SECOND_OF_MINUTE, "second");
    }

    public static void registerAggregators(ValuesSourceRegistry.Builder builder) {
        AutoDateHistogramAggregatorFactory.registerAggregators(builder);
    }

    /**
     *
     * Build roundings, computed dynamically as roundings are time zone dependent.
     * The current implementation probably should not be invoked in a tight loop.
     * @return Array of RoundingInfo
     */
    static RoundingInfo[] buildRoundings(ZoneId timeZone, String minimumInterval) {

        int indexToSliceFrom = 0;

        RoundingInfo[] roundings = new RoundingInfo[6];
        roundings[0] = new RoundingInfo(Rounding.DateTimeUnit.SECOND_OF_MINUTE,
            timeZone, 1000L, "s",1, 5, 10, 30);
        roundings[1] = new RoundingInfo(Rounding.DateTimeUnit.MINUTES_OF_HOUR, timeZone,
            60 * 1000L, "m", 1, 5, 10, 30);
        roundings[2] = new RoundingInfo(Rounding.DateTimeUnit.HOUR_OF_DAY, timeZone,
            60 * 60 * 1000L, "h", 1, 3, 12);
        roundings[3] = new RoundingInfo(Rounding.DateTimeUnit.DAY_OF_MONTH, timeZone,
            24 * 60 * 60 * 1000L, "d", 1, 7);
        roundings[4] = new RoundingInfo(Rounding.DateTimeUnit.MONTH_OF_YEAR, timeZone,
            30 * 24 * 60 * 60 * 1000L, "M", 1, 3);
        roundings[5] = new RoundingInfo(Rounding.DateTimeUnit.YEAR_OF_CENTURY, timeZone,
            365 * 24 * 60 * 60 * 1000L, "y", 1, 5, 10, 20, 50, 100);

        for (int i = 0; i < roundings.length; i++) {
            RoundingInfo roundingInfo = roundings[i];
            if (roundingInfo.getDateTimeUnit().equals(minimumInterval)) {
                indexToSliceFrom = i;
                break;
            }
        }
        return Arrays.copyOfRange(roundings, indexToSliceFrom, roundings.length);
    }

    private int numBuckets = 10;

    private String minimumIntervalExpression;

    /** Create a new builder with the given name. */
    public AutoDateHistogramAggregationBuilder(String name) {
        super(name);
    }

    /** Read from a stream, for internal use only. */
    public AutoDateHistogramAggregationBuilder(StreamInput in) throws IOException {
        super(in);
        numBuckets = in.readVInt();
        if (in.getVersion().onOrAfter(Version.V_7_3_0)) {
            minimumIntervalExpression = in.readOptionalString();
        }
    }

    @Override
    protected void innerWriteTo(StreamOutput out) throws IOException {
        out.writeVInt(numBuckets);
        if (out.getVersion().onOrAfter(Version.V_7_3_0)) {
            out.writeOptionalString(minimumIntervalExpression);
        }
    }

    protected AutoDateHistogramAggregationBuilder(AutoDateHistogramAggregationBuilder clone, Builder factoriesBuilder,
            Map<String, Object> metadata) {
        super(clone, factoriesBuilder, metadata);
        this.numBuckets = clone.numBuckets;
        this.minimumIntervalExpression = clone.minimumIntervalExpression;
    }

    @Override
    protected ValuesSourceType defaultValueSourceType() {
        return CoreValuesSourceType.DATE;
    }

    @Override
    protected AggregationBuilder shallowCopy(Builder factoriesBuilder, Map<String, Object> metadata) {
        return new AutoDateHistogramAggregationBuilder(this, factoriesBuilder, metadata);
    }

    @Override
    public String getType() {
        return NAME;
    }

    @Override
    protected ValuesSourceRegistry.RegistryKey<?> getRegistryKey() {
        return REGISTRY_KEY;
    }

    public String getMinimumIntervalExpression() {
        return minimumIntervalExpression;
    }

    public AutoDateHistogramAggregationBuilder setMinimumIntervalExpression(String minimumIntervalExpression) {
        if (minimumIntervalExpression != null && !ALLOWED_INTERVALS.containsValue(minimumIntervalExpression)) {
            throw new IllegalArgumentException(MINIMUM_INTERVAL_FIELD.getPreferredName() +
                " must be one of [" + ALLOWED_INTERVALS.values().toString() + "]");
        }
        this.minimumIntervalExpression = minimumIntervalExpression;
        return this;
    }

    public AutoDateHistogramAggregationBuilder setNumBuckets(int numBuckets) {
        if (numBuckets <= 0) {
            throw new IllegalArgumentException(NUM_BUCKETS_FIELD.getPreferredName() + " must be greater than 0 for [" + name + "]");
        }
        this.numBuckets = numBuckets;
        return this;
    }

    public int getNumBuckets() {
        return numBuckets;
    }

    @Override
    public BucketCardinality bucketCardinality() {
        return BucketCardinality.MANY;
    }

    @Override
    protected ValuesSourceAggregatorFactory innerBuild(QueryShardContext queryShardContext, ValuesSourceConfig config,
                                                       AggregatorFactory parent, Builder subFactoriesBuilder) throws IOException {
        RoundingInfo[] roundings = buildRoundings(timeZone(), getMinimumIntervalExpression());
        int maxRoundingInterval = Arrays.stream(roundings,0, roundings.length-1)
            .map(rounding -> rounding.innerIntervals)
            .flatMapToInt(Arrays::stream)
            .boxed()
            .reduce(Integer::max).get();
        Settings settings = queryShardContext.getIndexSettings().getNodeSettings();
        int maxBuckets = MultiBucketConsumerService.MAX_BUCKET_SETTING.get(settings);
        int bucketCeiling = maxBuckets / maxRoundingInterval;
        if (numBuckets > bucketCeiling) {
            throw new IllegalArgumentException(NUM_BUCKETS_FIELD.getPreferredName()+
                " must be less than " + bucketCeiling);
        }
        return new AutoDateHistogramAggregatorFactory(name, config, numBuckets, roundings, queryShardContext, parent,
            subFactoriesBuilder,
            metadata);
    }

    static Rounding createRounding(Rounding.DateTimeUnit interval, ZoneId timeZone) {
        Rounding.Builder tzRoundingBuilder = Rounding.builder(interval);
        if (timeZone != null) {
            tzRoundingBuilder.timeZone(timeZone);
        }
        Rounding rounding = tzRoundingBuilder.build();
        return rounding;
    }

    @Override
    protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.field(NUM_BUCKETS_FIELD.getPreferredName(), numBuckets);
        builder.field(MINIMUM_INTERVAL_FIELD.getPreferredName(), minimumIntervalExpression);
        return builder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), numBuckets, minimumIntervalExpression);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;
        AutoDateHistogramAggregationBuilder other = (AutoDateHistogramAggregationBuilder) obj;
        return Objects.equals(numBuckets, other.numBuckets) && Objects.equals(minimumIntervalExpression, other.minimumIntervalExpression);
    }

    public static class RoundingInfo implements Writeable {
        final Rounding rounding;
        final int[] innerIntervals;
        final long roughEstimateDurationMillis;
        final String unitAbbreviation;
        final String dateTimeUnit;

        public RoundingInfo(Rounding.DateTimeUnit dateTimeUnit,
                            ZoneId timeZone,
                            long roughEstimateDurationMillis,
                            String unitAbbreviation,
                            int... innerIntervals) {
            this.rounding = createRounding(dateTimeUnit, timeZone);
            this.roughEstimateDurationMillis = roughEstimateDurationMillis;
            this.unitAbbreviation = unitAbbreviation;
            this.innerIntervals = innerIntervals;
            Objects.requireNonNull(dateTimeUnit, "dateTimeUnit cannot be null");
            if (!ALLOWED_INTERVALS.containsKey(dateTimeUnit)) {
                throw new IllegalArgumentException("dateTimeUnit must be one of " + ALLOWED_INTERVALS.keySet().toString());
            }
            this.dateTimeUnit = ALLOWED_INTERVALS.get(dateTimeUnit);
        }

        public RoundingInfo(StreamInput in) throws IOException {
            rounding = Rounding.read(in);
            roughEstimateDurationMillis = in.readVLong();
            innerIntervals = in.readIntArray();
            unitAbbreviation = in.readString();
            if (in.getVersion().onOrAfter(Version.V_7_3_0)) {
                dateTimeUnit = in.readString();
            } else {
                /*
                 * This *should* be safe because we only deserialize RoundingInfo
                 * when reading result and results don't actually use this at all.
                 * We just set it to something non-null to line up with the normal
                 * ctor. "seconds" is the smallest unit anyway.
                 */
                dateTimeUnit =  "second";
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            rounding.writeTo(out);
            out.writeVLong(roughEstimateDurationMillis);
            out.writeIntArray(innerIntervals);
            out.writeString(unitAbbreviation);
            if (out.getVersion().onOrAfter(Version.V_7_3_0)) {
                out.writeString(dateTimeUnit);
            }
        }

        public int getMaximumInnerInterval() {
            return innerIntervals[innerIntervals.length - 1];
        }

        public String getDateTimeUnit() { return this.dateTimeUnit; }

        public long getRoughEstimateDurationMillis() {
            return roughEstimateDurationMillis;
        }

        public long getMaximumRoughEstimateDurationMillis() {
            return getRoughEstimateDurationMillis() * getMaximumInnerInterval();
        }

        @Override
        public int hashCode() {
            return Objects.hash(rounding, Arrays.hashCode(innerIntervals), dateTimeUnit);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj.getClass() != getClass()) {
                return false;
            }
            RoundingInfo other = (RoundingInfo) obj;
            return Objects.equals(rounding, other.rounding)
                && Objects.deepEquals(innerIntervals, other.innerIntervals)
                && Objects.equals(dateTimeUnit, other.dateTimeUnit)
                ;
        }

        @Override
        public String toString() {
            return "RoundingInfo[" + rounding + " " + Arrays.toString(innerIntervals) + "]";
        }
    }
}
