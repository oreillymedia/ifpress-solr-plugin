package com.ifactory.press.db.solr.search;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class SafariBlockJointest extends SolrTest {
  /*
   * This test sets up 100 documents in groups of 10; each decade (0-9, 10-19, etc)
   * has the same parent, which is the highest numbered doc in that group (the one ending with 9).
   * 
   * Then it creates texts based on the factors of the document number so we have a rich set of 
   * multiply-occurring tokens to use for tests.
   * 
   * The Idea of the SafariBlockJoin is to return the highest-scoring member of each group.
   */
  
  @Test
  public void testDocumentSetup () throws Exception {
    SolrQuery query = new SolrQuery("*:*");
    QueryResponse resp = solr.query(query);
    assertEquals (100, resp.getResults().getNumFound());

    query = new SolrQuery("uri:\"/doc/28\"");
    SolrDocument doc = solr.query(query).getResults().get(0);
    assertEquals ("A A C F M ", doc.get("text_t").toString());
    assertEquals ("child", doc.get("type_s").toString());

    query = new SolrQuery("uri:\"/doc/49\"");
    doc = solr.query(query).getResults().get(0);
    assertEquals ("F F ", doc.get("text_t").toString());
    assertEquals ("parent", doc.get("type_s").toString());
  }
  
  @Test
  public void testChildMatch () throws Exception {
    SolrQuery query = new SolrQuery("{!scoring_parent which=type_s:parent} text_t:M");
    // expect to get back docs 14, 28, 42, 56, 70, 84, 98
    QueryResponse resp = solr.query(query);
    assertEquals (7, resp.getResults().getNumFound());
    SolrDocument doc = solr.query(query).getResults().get(0);
    assertEquals ("/doc/14", doc.get("uri").toString());
  }
  
  @Test
  public void testFilterQueryInteraction() throws Exception {
    SolrQuery query = new SolrQuery("{!scoring_parent which=type_s:parent} text_t:M");
    query.setFilterQueries("-text_t:A");
    // no docs returned since 2(A) divides 14(M)
    QueryResponse resp = solr.query(query);
    assertEquals (0, resp.getResults().getNumFound());

    // expect to get back docs 14, 28, 56, 70
    // 42 and 84 are excluded because they have B (ids divisible by 3).
    // 98 is excluded because the *parent* has a B (id = 99, divisible by 3)
    query.setFilterQueries("-text_t:B");
    resp = solr.query(query);
    assertEquals (4, resp.getResults().getNumFound());
    SolrDocument doc = solr.query(query).getResults().get(2);
    assertEquals ("/doc/56", doc.get("uri").toString());
  }
  
  @Test
  public void testParentMatch () throws Exception {
    SolrQuery query = new SolrQuery("{!scoring_parent which=type_s:parent} text_t:R");
    // expect to get back docs 19, 38, 57, 76, 95
    QueryResponse resp = solr.query(query);
    assertEquals (5, resp.getResults().getNumFound());
    SolrDocument doc = solr.query(query).getResults().get(0);
    assertEquals ("/doc/19", doc.get("uri").toString());
  }
  
  @Test
  public void testGroupMatch () throws Exception {
    SolrQuery query = new SolrQuery("{!scoring_parent which=type_s:parent} text_t:A");
    // expect to get back all even-numbered docs -- grouped by parent
    QueryResponse resp = solr.query(query);
    assertEquals (10, resp.getResults().getNumFound());
    SolrDocument doc = solr.query(query).getResults().get(0);
    // top-scoring doc should have the most A's
    assertEquals ("/doc/64", doc.get("uri").toString());
    assertEquals ("/doc/32", solr.query(query).getResults().get(1).get("uri").toString());
    assertEquals ("/doc/96", solr.query(query).getResults().get(2).get("uri").toString());
    assertEquals ("/doc/16", solr.query(query).getResults().get(3).get("uri").toString());
  }
  
  @Test
  public void testParentGroupMatch () throws Exception {
    SolrQuery query = new SolrQuery("{!scoring_parent which=type_s:parent} text_t:F");
    // expect to get back all docs divisible by 7 -- grouped by parent
    // 7, 14, (21, 28), 35, (42, 49), 56, 63, (70, 77), 84, (91, 98)
    QueryResponse resp = solr.query(query);
    assertEquals (10, resp.getResults().getNumFound());
    SolrDocument doc = solr.query(query).getResults().get(0);
    // top-scoring doc should have the most F's (and then ordered by increasing docid)
    assertEquals ("/doc/49", doc.get("uri").toString());
    assertEquals ("/doc/98", solr.query(query).getResults().get(1).get("uri").toString());
    assertEquals ("/doc/7", solr.query(query).getResults().get(2).get("uri").toString());
    assertEquals ("/doc/14", solr.query(query).getResults().get(3).get("uri").toString());
    assertEquals ("/doc/21", solr.query(query).getResults().get(4).get("uri").toString());
    assertEquals ("/doc/35", solr.query(query).getResults().get(5).get("uri").toString());
  }
  
  @Before 
  public void setup () throws Exception {
    insertTestDocuments ();
  }
  
  private void insertTestDocuments () throws Exception {
    ArrayList<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 10; j++) {
          SolrInputDocument doc = new SolrInputDocument();
          int docid = i*10 + j;
          doc.addField("uri", "/doc/" + docid);
          doc.addField("block_i", i);
          if (j == 9) {
            // docs with ids having '9' as their ones digit are parents of preceding contiguous child docs
            doc.addField("type_s", "parent");
          } else {
            doc.addField("type_s", "child");
          }
          StringBuilder text = new StringBuilder();
          // create a text of one letter words; one for each factor of docid (from 2 to 27), with
          // multiple occurrences for square factors, eg:
          // 28 -> "A A C F M" since 28 has factors 1,2,4,7,14,28 and 2^2=4
          // 27 -> "B B B H"
          for (char k = 'A'; k <= 'Z'; k++) {
            int factor = k - 'A' + 2;
            for (int l = docid; l > 1 && (l % factor) == 0; l = l / factor) {
              text.append(k).append(' ');
            }
          }
          doc.addField("text_t", text.toString());
          docs.add(doc);
      }
    }
    solr.add(docs);
    solr.commit();
  }

}
