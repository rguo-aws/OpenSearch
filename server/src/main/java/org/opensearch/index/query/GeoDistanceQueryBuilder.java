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

package org.opensearch.index.query;

import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.opensearch.common.ParseField;
import org.opensearch.common.ParsingException;
import org.opensearch.common.Strings;
import org.opensearch.common.geo.GeoDistance;
import org.opensearch.common.geo.GeoPoint;
import org.opensearch.common.geo.GeoUtils;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.unit.DistanceUnit;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.index.mapper.GeoPointFieldMapper.GeoPointFieldType;
import org.opensearch.index.mapper.MappedFieldType;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Filter results of a query to include only those within a specific distance to some
 * geo point.
 */
public class GeoDistanceQueryBuilder extends AbstractQueryBuilder<GeoDistanceQueryBuilder> {
    public static final String NAME = "geo_distance";

    /** Default for distance unit computation. */
    public static final DistanceUnit DEFAULT_DISTANCE_UNIT = DistanceUnit.DEFAULT;
    /** Default for geo distance computation. */
    public static final GeoDistance DEFAULT_GEO_DISTANCE = GeoDistance.ARC;

    /**
     * The default value for ignore_unmapped.
     */
    public static final boolean DEFAULT_IGNORE_UNMAPPED = false;

    private static final ParseField VALIDATION_METHOD_FIELD = new ParseField("validation_method");
    private static final ParseField DISTANCE_TYPE_FIELD = new ParseField("distance_type");
    private static final ParseField UNIT_FIELD = new ParseField("unit");
    private static final ParseField DISTANCE_FIELD = new ParseField("distance");
    private static final ParseField IGNORE_UNMAPPED_FIELD = new ParseField("ignore_unmapped");

    private final String fieldName;
    /** Distance from center to cover. */
    private double distance;
    /** Point to use as center. */
    private GeoPoint center = new GeoPoint(Double.NaN, Double.NaN);
    /** Algorithm to use for distance computation. */
    private GeoDistance geoDistance = GeoDistance.ARC;
    /** How strict should geo coordinate validation be? */
    private GeoValidationMethod validationMethod = GeoValidationMethod.DEFAULT;

    private boolean ignoreUnmapped = DEFAULT_IGNORE_UNMAPPED;

    /**
     * Construct new GeoDistanceQueryBuilder.
     * @param fieldName name of indexed geo field to operate distance computation on.
     * */
    public GeoDistanceQueryBuilder(String fieldName) {
        if (Strings.isEmpty(fieldName)) {
            throw new IllegalArgumentException("fieldName must not be null or empty");
        }
        this.fieldName = fieldName;
    }

    /**
     * Read from a stream.
     */
    public GeoDistanceQueryBuilder(StreamInput in) throws IOException {
        super(in);
        fieldName = in.readString();
        distance = in.readDouble();
        validationMethod = GeoValidationMethod.readFromStream(in);
        center = in.readGeoPoint();
        geoDistance = GeoDistance.readFromStream(in);
        ignoreUnmapped = in.readBoolean();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeDouble(distance);
        validationMethod.writeTo(out);
        out.writeGeoPoint(center);
        geoDistance.writeTo(out);
        out.writeBoolean(ignoreUnmapped);
    }

    /** Name of the field this query is operating on. */
    public String fieldName() {
        return this.fieldName;
    }

    /** Sets the center point for the query.
     * @param point the center of the query
     **/
    public GeoDistanceQueryBuilder point(GeoPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("center point must not be null");
        }
        this.center = point;
        return this;
    }

    /**
     * Sets the center point of the query.
     * @param lat latitude of center
     * @param lon longitude of center
     * */
    public GeoDistanceQueryBuilder point(double lat, double lon) {
        this.center = new GeoPoint(lat, lon);
        return this;
    }

    /** Returns the center point of the distance query. */
    public GeoPoint point() {
        return this.center;
    }

    /** Sets the distance from the center using the default distance unit.*/
    public GeoDistanceQueryBuilder distance(String distance) {
        return distance(distance, DistanceUnit.DEFAULT);
    }

    /** Sets the distance from the center for this query. */
    public GeoDistanceQueryBuilder distance(String distance, DistanceUnit unit) {
        if (Strings.isEmpty(distance)) {
            throw new IllegalArgumentException("distance must not be null or empty");
        }
        if (unit == null) {
            throw new IllegalArgumentException("distance unit must not be null");
        }
        double newDistance = DistanceUnit.parse(distance, unit, DistanceUnit.DEFAULT);
        if (newDistance <= 0.0) {
            throw new IllegalArgumentException("distance must be greater than zero");
        }
        this.distance = newDistance;
        return this;
    }

    /** Sets the distance from the center for this query. */
    public GeoDistanceQueryBuilder distance(double distance, DistanceUnit unit) {
        return distance(Double.toString(distance), unit);
    }

    /** Returns the distance configured as radius. */
    public double distance() {
        return distance;
    }

    /** Sets the center point for this query. */
    public GeoDistanceQueryBuilder geohash(String geohash) {
        if (Strings.isEmpty(geohash)) {
            throw new IllegalArgumentException("geohash must not be null or empty");
        }
        this.center.resetFromGeoHash(geohash);
        return this;
    }

    /** Which type of geo distance calculation method to use. */
    public GeoDistanceQueryBuilder geoDistance(GeoDistance geoDistance) {
        if (geoDistance == null) {
            throw new IllegalArgumentException("geoDistance must not be null");
        }
        this.geoDistance = geoDistance;
        return this;
    }

    /** Returns geo distance calculation type to use. */
    public GeoDistance geoDistance() {
        return this.geoDistance;
    }

    /** Set validation method for geo coordinates. */
    public void setValidationMethod(GeoValidationMethod method) {
        this.validationMethod = method;
    }

    /** Returns validation method for geo coordinates. */
    public GeoValidationMethod getValidationMethod() {
        return this.validationMethod;
    }

    /**
     * Sets whether the query builder should ignore unmapped fields (and run a
     * {@link MatchNoDocsQuery} in place of this query) or throw an exception if
     * the field is unmapped.
     */
    public GeoDistanceQueryBuilder ignoreUnmapped(boolean ignoreUnmapped) {
        this.ignoreUnmapped = ignoreUnmapped;
        return this;
    }

    /**
     * Gets whether the query builder will ignore unmapped fields (and run a
     * {@link MatchNoDocsQuery} in place of this query) or throw an exception if
     * the field is unmapped.
     */
    public boolean ignoreUnmapped() {
        return ignoreUnmapped;
    }

    @Override
    protected Query doToQuery(QueryShardContext shardContext) throws IOException {
        MappedFieldType fieldType = shardContext.fieldMapper(fieldName);
        if (fieldType == null) {
            if (ignoreUnmapped) {
                return new MatchNoDocsQuery();
            } else {
                throw new QueryShardException(shardContext, "failed to find geo_point field [" + fieldName + "]");
            }
        }

        if (!(fieldType instanceof GeoPointFieldType)) {
            throw new QueryShardException(shardContext, "field [" + fieldName + "] is not a geo_point field");
        }

        QueryValidationException exception = checkLatLon();
        if (exception != null) {
            throw new QueryShardException(shardContext, "couldn't validate latitude/ longitude values", exception);
        }

        if (GeoValidationMethod.isCoerce(validationMethod)) {
            GeoUtils.normalizePoint(center, true, true);
        }

        Query query = LatLonPoint.newDistanceQuery(fieldType.name(), center.lat(), center.lon(), this.distance);
        if (fieldType.hasDocValues()) {
            Query dvQuery = LatLonDocValuesField.newSlowDistanceQuery(fieldType.name(), center.lat(), center.lon(), this.distance);
            query = new IndexOrDocValuesQuery(query, dvQuery);
        }
        return query;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.startArray(fieldName).value(center.lon()).value(center.lat()).endArray();
        builder.field(DISTANCE_FIELD.getPreferredName(), distance);
        builder.field(DISTANCE_TYPE_FIELD.getPreferredName(), geoDistance.name().toLowerCase(Locale.ROOT));
        builder.field(VALIDATION_METHOD_FIELD.getPreferredName(), validationMethod);
        builder.field(IGNORE_UNMAPPED_FIELD.getPreferredName(), ignoreUnmapped);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    public static GeoDistanceQueryBuilder fromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token;

        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;
        String currentFieldName = null;
        GeoPoint point = new GeoPoint(Double.NaN, Double.NaN);
        String fieldName = null;
        Object vDistance = null;
        DistanceUnit unit = GeoDistanceQueryBuilder.DEFAULT_DISTANCE_UNIT;
        GeoDistance geoDistance = GeoDistanceQueryBuilder.DEFAULT_GEO_DISTANCE;
        GeoValidationMethod validationMethod = null;
        boolean ignoreUnmapped = DEFAULT_IGNORE_UNMAPPED;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                fieldName = currentFieldName;
                GeoUtils.parseGeoPoint(parser, point);
            } else if (token == XContentParser.Token.START_OBJECT) {
                throwParsingExceptionOnMultipleFields(NAME, parser.getTokenLocation(), fieldName, currentFieldName);
                // the json in the format of -> field : { lat : 30, lon : 12 }
                String currentName = parser.currentName();
                fieldName = currentFieldName;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentName = parser.currentName();
                    } else if (token.isValue()) {
                        if (currentName.equals("lat")) {
                            point.resetLat(parser.doubleValue());
                        } else if (currentName.equals("lon")) {
                            point.resetLon(parser.doubleValue());
                        } else if (currentName.equals("geohash")) {
                            point.resetFromGeoHash(parser.text());
                        } else {
                            throw new ParsingException(parser.getTokenLocation(),
                                    "[geo_distance] query does not support [" + currentFieldName + "]");
                        }
                    }
                }
            } else if (token.isValue()) {
                if (DISTANCE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        vDistance = parser.text(); // a String
                    } else {
                        vDistance = parser.numberValue(); // a Number
                    }
                } else if (UNIT_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    unit = DistanceUnit.fromString(parser.text());
                } else if (DISTANCE_TYPE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    geoDistance = GeoDistance.fromString(parser.text());
                } else if (currentFieldName.endsWith(".lat")) {
                    point.resetLat(parser.doubleValue());
                    fieldName = currentFieldName.substring(0, currentFieldName.length() - ".lat".length());
                } else if (currentFieldName.endsWith(".lon")) {
                    point.resetLon(parser.doubleValue());
                    fieldName = currentFieldName.substring(0, currentFieldName.length() - ".lon".length());
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    queryName = parser.text();
                } else if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    boost = parser.floatValue();
                } else if (IGNORE_UNMAPPED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    ignoreUnmapped = parser.booleanValue();
                } else if (VALIDATION_METHOD_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    validationMethod = GeoValidationMethod.fromString(parser.text());
                } else {
                    if (fieldName == null) {
                        point.resetFromString(parser.text());
                        fieldName = currentFieldName;
                    } else {
                        throw new ParsingException(parser.getTokenLocation(), "failed to parse [{}] query. unexpected field [{}]",
                            NAME, currentFieldName);
                    }
                }
            }
        }

        if (vDistance == null) {
            throw new ParsingException(parser.getTokenLocation(), "geo_distance requires 'distance' to be specified");
        }

        GeoDistanceQueryBuilder qb = new GeoDistanceQueryBuilder(fieldName);
        if (vDistance instanceof Number) {
            qb.distance(((Number) vDistance).doubleValue(), unit);
        } else {
            qb.distance((String) vDistance, unit);
        }
        qb.point(point);
        if (validationMethod != null) {
            qb.setValidationMethod(validationMethod);
        }
        qb.geoDistance(geoDistance);
        qb.boost(boost);
        qb.queryName(queryName);
        qb.ignoreUnmapped(ignoreUnmapped);
        return qb;
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(center, geoDistance, distance, validationMethod, ignoreUnmapped);
    }

    @Override
    protected boolean doEquals(GeoDistanceQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName) &&
                (distance == other.distance) &&
                Objects.equals(validationMethod, other.validationMethod) &&
                Objects.equals(center, other.center) &&
                Objects.equals(geoDistance, other.geoDistance) &&
                Objects.equals(ignoreUnmapped, other.ignoreUnmapped);
    }

    private QueryValidationException checkLatLon() {
        if (GeoValidationMethod.isIgnoreMalformed(validationMethod)) {
            return null;
        }

        QueryValidationException validationException = null;
        // For everything post 2.0, validate latitude and longitude unless validation was explicitly turned off
        if (GeoUtils.isValidLatitude(center.getLat()) == false) {
            validationException = addValidationError("center point latitude is invalid: " + center.getLat(), validationException);
        }
        if (GeoUtils.isValidLongitude(center.getLon()) == false) {
            validationException = addValidationError("center point longitude is invalid: " + center.getLon(), validationException);
        }
        return validationException;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }
}
