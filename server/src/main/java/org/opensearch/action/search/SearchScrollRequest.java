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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.action.search;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.search.Scroll;
import org.opensearch.tasks.Task;
import org.opensearch.tasks.TaskId;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import static org.opensearch.action.ValidateActions.addValidationError;

public class SearchScrollRequest extends ActionRequest implements ToXContentObject {

    private String scrollId;
    private Scroll scroll;

    public SearchScrollRequest() {
    }

    public SearchScrollRequest(String scrollId) {
        this.scrollId = scrollId;
    }

    public SearchScrollRequest(StreamInput in) throws IOException {
        super(in);
        scrollId = in.readString();
        scroll = in.readOptionalWriteable(Scroll::new);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(scrollId);
        out.writeOptionalWriteable(scroll);
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (scrollId == null) {
            validationException = addValidationError("scrollId is missing", validationException);
        }
        return validationException;
    }

    /**
     * The scroll id used to scroll the search.
     */
    public String scrollId() {
        return scrollId;
    }

    public SearchScrollRequest scrollId(String scrollId) {
        this.scrollId = scrollId;
        return this;
    }

    public ParsedScrollId parseScrollId() {
        return TransportSearchHelper.parseScrollId(scrollId);
    }

    /**
     * If set, will enable scrolling of the search request.
     */
    public Scroll scroll() {
        return scroll;
    }

    /**
     * If set, will enable scrolling of the search request.
     */
    public SearchScrollRequest scroll(Scroll scroll) {
        this.scroll = scroll;
        return this;
    }

    /**
     * If set, will enable scrolling of the search request for the specified timeout.
     */
    public SearchScrollRequest scroll(TimeValue keepAlive) {
        return scroll(new Scroll(keepAlive));
    }

    /**
     * If set, will enable scrolling of the search request for the specified timeout.
     */
    public SearchScrollRequest scroll(String keepAlive) {
        return scroll(new Scroll(TimeValue.parseTimeValue(keepAlive, null, getClass().getSimpleName() + ".keepAlive")));
    }

    @Override
    public Task createTask(long id, String type, String action, TaskId parentTaskId, Map<String, String> headers) {
        return new SearchTask(id, type, action, this::getDescription, parentTaskId, headers);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SearchScrollRequest that = (SearchScrollRequest) o;
        return Objects.equals(scrollId, that.scrollId) &&
                Objects.equals(scroll, that.scroll);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scrollId, scroll);
    }

    @Override
    public String toString() {
        return "SearchScrollRequest{" +
                "scrollId='" + scrollId + '\'' +
                ", scroll=" + scroll +
                '}';
    }

    @Override
    public String getDescription() {
        return "scrollId[" + scrollId + "], scroll[" + scroll + "]";
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("scroll_id", scrollId);
        if (scroll != null) {
            builder.field("scroll", scroll.keepAlive().getStringRep());
        }
        builder.endObject();
        return builder;
    }

    /**
     * Parse a search scroll request from a request body provided through the REST layer.
     * Values that are already be set and are also found while parsing will be overridden.
     */
    public void fromXContent(XContentParser parser) throws IOException {
        if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
            throw new IllegalArgumentException("Malformed content, must start with an object");
        } else {
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if ("scroll_id".equals(currentFieldName) && token == XContentParser.Token.VALUE_STRING) {
                    scrollId(parser.text());
                } else if ("scroll".equals(currentFieldName) && token == XContentParser.Token.VALUE_STRING) {
                    scroll(new Scroll(TimeValue.parseTimeValue(parser.text(), null, "scroll")));
                } else {
                    throw new IllegalArgumentException("Unknown parameter [" + currentFieldName
                            + "] in request body or parameter is of the wrong type[" + token + "] ");
                }
            }
        }
    }
}
