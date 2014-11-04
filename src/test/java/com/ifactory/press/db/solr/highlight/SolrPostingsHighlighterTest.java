package com.ifactory.press.db.solr.highlight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SolrPostingsHighlighterTest {
  
  private CoreContainer coreContainer;
  private SolrServer solr;
  
  @Before
  public void startup() throws IOException, SolrServerException {
    // start an embedded solr instance
    coreContainer = new CoreContainer("solr");
    coreContainer.load();
    solr = new EmbeddedSolrServer(coreContainer, "heron");
    solr.deleteByQuery("*:*");
    solr.commit();
  }
  
  @After
  public void cleanup() throws IOException {
    SolrCore core = coreContainer.getCore("heron");
    if (core != null) {
        core.close();
    }
    coreContainer.shutdown();
  }
  
  @Test
  public void testHighlightChapter5() throws SolrServerException, IOException {
    // searching for "gas" didn't work on the Safari site
    
    InputStream ch5stream = getClass().getResourceAsStream("/ch5.txt");
    String ch5 = IOUtils.toString(ch5stream);

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "ch5");
    doc.addField("text", ch5);
    solr.add(doc);
    solr.commit();
    
    // retrieve highlights at query time 
    SolrQuery query = new SolrQuery("gas");
    query.setHighlight(true);
    query.set("hl.maxAnalyzedCharacters", 250000);
    // query.s
    QueryResponse resp = solr.query(query);
    SolrDocumentList results = resp.getResults();
    assertEquals (1, results.getNumFound());
    assertNotNull ("PH returns null highlight", resp.getHighlighting());
    String snippet = resp.getHighlighting().get("ch5").get("text").get(0);
    assertTrue (snippet + " \n does not contain <b>gas</b>", snippet.contains("<b>gas</b>"));
  }

}
