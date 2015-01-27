package com.ifactory.press.db.solr.highlight;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import com.ifactory.press.db.solr.HeronSolrTest;

public class SolrPostingsHighlighterTest extends HeronSolrTest {
  
  @Test
  public void testHighlightChapter5() throws SolrServerException, IOException {
    // searching for "gas" didn't work on the Safari site
    
    InputStream ch5stream = getClass().getResourceAsStream("ch5.txt");
    String ch5 = IOUtils.toString(ch5stream);

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", "ch5");
    doc.addField("text", ch5);
    solr.add(doc);
    solr.commit();
    
    // retrieve highlights at query time 
    SolrQuery query = new SolrQuery("gas");
    query.setHighlight(true);
    // query.set("hl.maxAnalyzedChars", 250000);
    QueryResponse resp = solr.query(query);
    SolrDocumentList results = resp.getResults();
    assertEquals (1, results.getNumFound());
    assertNotNull ("PH returns null highlight", resp.getHighlighting());
    String snippet = resp.getHighlighting().get("ch5").get("text").get(0);
    assertTrue (snippet + " \n does not contain <b class=\"highlight\">gas</b>", 
        snippet.contains("<b class=\"highlight\">gas</b>"));
    
    // verify that < and & are encoded, but not spaces and other punctuation:
    assertTrue ("snippet not encoded properly?\n\n" + snippet,
        snippet.contains("Electric-Powered Vehicles:<b class=\"highlight\">gas</b> What Technologies &lt; &amp; by 2015?"));
  }

}
