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

package org.opensearch.client.core;

import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.opensearch.test.AbstractXContentTestCase.xContentTester;

public class MultiTermVectorsResponseTests extends OpenSearchTestCase {

    public void testFromXContent() throws IOException {
        xContentTester(
            this::createParser,
            this::createTestInstance,
            this::toXContent,
            MultiTermVectorsResponse::fromXContent)
            .supportsUnknownFields(true)
            .randomFieldsExcludeFilter(field ->
                field.endsWith("term_vectors") || field.endsWith("terms") || field.endsWith("tokens"))
            .test();
    }

    private void toXContent(MultiTermVectorsResponse response, XContentBuilder builder) throws IOException {
        builder.startObject();
        List<TermVectorsResponse> termVectorsResponseList = response.getTermVectorsResponses();
        if (termVectorsResponseList != null) {
            builder.startArray("docs");
            for (TermVectorsResponse tvr : termVectorsResponseList) {
                TermVectorsResponseTests.toXContent(tvr, builder);
            }
            builder.endArray();
        }
        builder.endObject();
    }

    protected MultiTermVectorsResponse createTestInstance() {
        int numberOfResponses = randomIntBetween(0, 5);
        List<TermVectorsResponse> responses = new ArrayList<>(numberOfResponses);
        for (int i = 0; i < numberOfResponses; i++) {
            TermVectorsResponse tvResponse = TermVectorsResponseTests.createTestInstance();
            responses.add(tvResponse);
        }
        return new MultiTermVectorsResponse(responses);
    }
}
