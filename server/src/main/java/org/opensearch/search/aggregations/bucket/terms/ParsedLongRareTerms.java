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

package org.opensearch.search.aggregations.bucket.terms;


import org.opensearch.common.xcontent.ObjectParser;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;

import java.io.IOException;

public class ParsedLongRareTerms extends ParsedRareTerms {
    @Override
    public String getType() {
        return LongRareTerms.NAME;
    }

    private static final ObjectParser<ParsedLongRareTerms, Void> PARSER =
        new ObjectParser<>(ParsedLongRareTerms.class.getSimpleName(), true, ParsedLongRareTerms::new);

    static {
        declareParsedTermsFields(PARSER, ParsedBucket::fromXContent);
    }

    public static ParsedLongRareTerms fromXContent(XContentParser parser, String name) throws IOException {
        ParsedLongRareTerms aggregation = PARSER.parse(parser, null);
        aggregation.setName(name);
        return aggregation;
    }

    public static class ParsedBucket extends ParsedRareTerms.ParsedBucket {

        private Long key;

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public String getKeyAsString() {
            String keyAsString = super.getKeyAsString();
            if (keyAsString != null) {
                return keyAsString;
            }
            if (key != null) {
                return Long.toString(key);
            }
            return null;
        }

        public Number getKeyAsNumber() {
            return key;
        }

        @Override
        protected XContentBuilder keyToXContent(XContentBuilder builder) throws IOException {
            builder.field(CommonFields.KEY.getPreferredName(), key);
            if (super.getKeyAsString() != null) {
                builder.field(CommonFields.KEY_AS_STRING.getPreferredName(), getKeyAsString());
            }
            return builder;
        }

        static ParsedLongRareTerms.ParsedBucket fromXContent(XContentParser parser) throws IOException {
            return parseRareTermsBucketXContent(parser, ParsedLongRareTerms.ParsedBucket::new, (p, bucket) -> bucket.key = p.longValue());
        }
    }
}
