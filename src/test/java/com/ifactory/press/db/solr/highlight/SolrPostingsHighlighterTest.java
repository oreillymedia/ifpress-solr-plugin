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
import org.junit.Before;
import org.junit.Test;

import com.ifactory.press.db.solr.HeronSolrTest;

public class SolrPostingsHighlighterTest extends HeronSolrTest {
  
  @Before
  public void setup () throws SolrServerException, IOException {
    solr.deleteByQuery("*:*");
    solr.commit();
  }
  
  @Test
  public void testHighlightChapter5() throws SolrServerException, IOException {
    // searching for "gas" didn't work on the Safari site
    indexDocument ("ch5.txt");
    assertSnippet ("gas", "Chapter 5\n Prospects and Evolutions of Electric-Powered Vehicles:<b>gas</b> What Technologies &lt; &amp; by 2015?");
  }
  
  @Test
  public void testSnippetScoring() throws Exception {
    indexDocument ("daly-web-framework.txt");
    assertSnippet ("who had fond memories", "Ruby's status as a next-generation scripting language inspired programmers <b>who</b> <b>had</b> <b>fond</b> <b>memories</b> of quick Perl projects but did not want to trade flexibility for readability.");
  }

  // TODO - randomized testing -- search for phrases and/or words drawn from sentences and
  // expect those same sentences to be returned.
  
  private void assertSnippet (String q, String expectedSnippet) throws SolrServerException, IOException {
    SolrQuery query = new SolrQuery(q);
    query.setHighlight(true);
    query.set("hl.tag.pre", "<b>");
    query.set("hl.tag.ellipsis", "¦");
    query.set("f.text.hl.snippets", 3); // override value of 3 specified in solrconfig.xml
    // query.set("hl.maxAnalyzedChars", 250000);
    QueryResponse resp = solr.query(query);
    SolrDocumentList results = resp.getResults();
    assertEquals (1, results.getNumFound());
    assertNotNull ("PH returns null highlight", resp.getHighlighting());
    String snippet = resp.getHighlighting().values().iterator().next().get("text").get(0).split("¦")[0];
    // verify the snippet starts with the expected snippet text; it might be followed
    // by another contiguous snippet with no delimiter (hl.tag.ellipsis above) if there was a 
    // match in the following sentence
    int len = expectedSnippet.length();
    assertEquals (expectedSnippet, snippet.trim().substring(0, len));
  }
  
  private void indexDocument (String filename) throws IOException, SolrServerException {
    InputStream in = getClass().getResourceAsStream(filename);
    String text = IOUtils.toString(in);

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", filename);
    doc.addField("text", text);
    solr.add(doc);
    solr.commit();
    
    in.close();
  }

}
