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

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.*;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.util.BitDocIdSet;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrConstantScoreQuery;
import org.apache.solr.search.SyntaxError;
import java.io.IOException;

class ScoringParentQParser extends QParser {
  /**
   * implementation detail subject to change
   */
  public String CACHE_NAME="perSegFilter";

  protected String getParentFilterLocalParamName() {
    return "which";
  }

  ScoringParentQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  public Query parse() throws SyntaxError {
    if (localParams == null) {
      throw new SyntaxError ("join query parser must be invoked using localParams");
    }
    String filter = localParams.get(getParentFilterLocalParamName());
    QParser parentParser = subQuery(filter, null);
    Query parentQ = parentParser.getQuery();

    String queryText = localParams.get(QueryParsing.V);
    // there is no child query, return parent filter from cache
    if (queryText == null || queryText.length()==0) {
      SolrConstantScoreQuery wrapped = new SolrConstantScoreQuery(getFilter(parentQ));
      wrapped.setCache(false);
      return wrapped;
    }
    QParser childrenParser = subQuery(queryText, null);
    Query childrenQuery = childrenParser.getQuery();
    return createQuery(parentQ, childrenQuery);
  }

  protected Query createQuery(Query parentList, Query q) {
    return new SafariBlockJoinQuery(q, getFilter(parentList).filter);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  protected BitDocIdSetFilterWrapper getFilter(Query parentList) {
    SolrCache parentCache = req.getSearcher().getCache(CACHE_NAME);
    // lazily retrieve from solr cache
    Filter filter = null;
    if (parentCache != null) {
      filter = (Filter) parentCache.get(parentList);
    }
    BitDocIdSetFilterWrapper result;
    if (filter instanceof BitDocIdSetFilterWrapper) {
      result = (BitDocIdSetFilterWrapper)filter;
    } else {
      result = new BitDocIdSetFilterWrapper(createParentFilter(parentList));
      if (parentCache != null) {
        parentCache.put(parentList, result);
      }
    }
    return result;
  }

  protected QueryBitSetProducer createParentFilter(Query parentQ) {
    return new QueryBitSetProducer(parentQ);
  }

  static class BitDocIdSetFilterWrapper extends Filter {
    final QueryBitSetProducer filter;

    BitDocIdSetFilterWrapper(QueryBitSetProducer filter) {
      this.filter = filter;
    }

    @Override
    public DocIdSet getDocIdSet(LeafReaderContext context, Bits acceptDocs) throws IOException {
      BitSet set = this.filter.getBitSet(context);
      return set == null ? null : new BitDocIdSet(set);
    }

    @Override
    public String toString(String field) {
      return this.getClass().getSimpleName() + "(" + this.filter + ")";
    }

    @Override
    public boolean equals(Object obj) {
      return !super.equals(obj) ? false : this.filter.equals(((BitDocIdSetFilterWrapper)obj).filter);
    }

    @Override
    public int hashCode() {
      return 31 * super.hashCode() + this.filter.hashCode();
    }
  }
}






