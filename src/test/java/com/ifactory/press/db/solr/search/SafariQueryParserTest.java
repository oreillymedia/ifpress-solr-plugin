package com.ifactory.press.db.solr.search;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.SyntaxError;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class SafariQueryParserTest extends SolrTest {

  private static final String A_T = "a_t";
  private static final String B_T = "b_t";
  private static final String B_T2 = B_T + "^2";

  @Test
  public void testPhraseFields () throws Exception {
    assertParse (BQ(DMQ(TQ(A_T, "hey"))), "hey", B_T2);
    assertParse (BQ(DMQ(B(PQ(B_T, "one", "two"), 2.0f))), "\"one two\"", B_T2);
    assertParse (BQW(BQ(DMQ(TQ(A_T, "hey")), DMQ(B(PQ(B_T, "one", "two"), 2.0f)), TQ("c_t", "ho"))), "+hey +\"one two\" +c_t:ho", B_T2);
  }
  
  @Test
  public void testNoPhraseFields () throws Exception {
    assertParse (BQ(DMQ(TQ(A_T, "hey"))), "hey", "");
    assertParse (BQW(BQ(DMQ(TQ(A_T, "one")),DMQ(TQ(A_T, "two")))), "+one +two", "");
    assertParse (BQ(DMQ(PQ(A_T, "one", "two"))), "\"one two\"", "");
  }
  
  private TermQuery TQ(String f, String v) {
    return new TermQuery(new Term(f, v));
  }

  private Term T(String f, String v) {
    return new Term(f, v);
  }
  
  private Query B(Query q, float boost) {
    return new BoostQuery(q, boost);
  }
  
  private PhraseQuery PQ(String f, String... vals) {
    PhraseQuery.Builder pqBuilder = new PhraseQuery.Builder();
    for (String v : vals) {
      pqBuilder.add(T(f, v));
    }
    return pqBuilder.build();
  }
  
  private BooleanQuery BQ(Query ... clauses) {
    BooleanQuery.Builder bqBuilder = new BooleanQuery.Builder();

    for (Query q : clauses) {
      bqBuilder.add(q, Occur.MUST);
    }
    return bqBuilder.build();
  }

  /*
    BooleanQuery Wrapper - wraps BooleanQuery with an extra Occur.MUST boolean clause
    due to a change in Lucene's QueryParser.java for SOLR-9185
    that makes ExtendedDismaxQParser's parsed queries be wrapped in this extra clause.
   */
  private BooleanQuery BQW(BooleanQuery bq) {
    BooleanQuery.Builder bqBuilder = new BooleanQuery.Builder();
    bqBuilder.add(bq, Occur.MUST);
    return bqBuilder.build();
  }
  
  private DisjunctionMaxQuery DMQ(Query ... clauses) {
    DisjunctionMaxQuery dmq = new DisjunctionMaxQuery(Arrays.asList(clauses), 0.0f);
    return dmq;
  }
  
  private void assertParse (Query expected, String query, String phraseFields) throws SyntaxError {
    ModifiableSolrParams localParams = new ModifiableSolrParams();
    ModifiableSolrParams params = new ModifiableSolrParams(); 
    params.add("qf", A_T);
    params.add(SafariQueryParser.PQF, phraseFields);
    SolrCore core = getDefaultCore();
    try {
      SolrQueryRequest req = new LocalSolrQueryRequest(core, localParams);
    
      SafariQueryParser parser = new SafariQueryParser(query, localParams, params, req);
      Query parsed = parser.parse();
      assertEquals(expected, parsed);
    } finally {
      core.close();
    }
  }
  
}
