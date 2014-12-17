package org.apache.solr.search.join;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;

/**
 * Like BlockJoinParentQParser, but tracks child document scores using scoreMode=ScoreMode.MAX
 */
public class ScoringParentQParser extends BlockJoinParentQParser {

  public ScoringParentQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    super(qstr, localParams, params, req);
  }

  @Override
  protected Query createQuery(Query parentList, Query q) {
    return new ToParentBlockJoinQuery(q, getFilter(parentList), ScoreMode.Max);
  }

}
