/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ifactory.press.db.solr.search;

import org.apache.lucene.search.Query;  // this instead of filter
import org.apache.solr.search.QueryWrapperFilter;
//import org.apache.lucene.search.join.FixedBitSetCachingWrapperQuery;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.Filter;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrConstantScoreQuery;
import org.apache.solr.search.SyntaxError;

class ScoringParentQParser extends QParser {

    /**
     * implementation detail subject to change
     */
    public String CACHE_NAME = "perSegFilter";  // rivey changed here

    protected String getParentQueryLocalParamName() {
        return "which";
    }

    ScoringParentQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
        super(qstr, localParams, params, req);
    }

    @Override
    public Query parse() throws SyntaxError {
        if (localParams == null) {
            throw new SyntaxError("join query parser must be invoked using localParams");
        }
        String filter = localParams.get(getParentQueryLocalParamName());
        QParser parentParser = subQuery(filter, null);
        Query parentQ = parentParser.getQuery();

        String queryText = localParams.get(QueryParsing.V);
        // there is no child query, return parent filter from cache
        if (queryText == null || queryText.length() == 0) {
            SolrConstantScoreQuery wrapped = new SolrConstantScoreQuery((Filter) getQuery(parentQ));  // rivey - cast to Filter
            wrapped.setCache(false);
            return wrapped;
        }
        QParser childrenParser = subQuery(queryText, null);
        Query childrenQuery = childrenParser.getQuery();
        return createQuery(parentQ, childrenQuery);
    }

    protected Query createQuery(Query parentList, Query q) {
        return new SafariBlockJoinQuery(q, (Filter) getQuery(parentList)); // rivey cast to Filter (solr) verify
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Query getQuery(Query parentList) {
        SolrCache parentCache = req.getSearcher().getCache(CACHE_NAME);
        // lazily retrieve from solr cache
        Query filter = null;   // turned into Query 
        if (parentCache != null) {
            filter = (Query) parentCache.get(parentList);
        }
        Query result;
        if (filter == null) {
            result = createParentQuery(parentList);
            if (parentCache != null) {
                parentCache.put(parentList, result);
            }
        } else {
            result = filter;
        }
        return result;
    }

    protected Query createParentQuery(Query parentQ) {
        return new QueryWrapperFilter(parentQ); // rfhi was FixedBitSetCachingWrapperQuery
    }
}
