package com.ifactory.press.db.solr.highlight;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Before;
import org.junit.Test;

import com.ifactory.press.db.solr.HeronSolrTest;

public class SolrUnifiedHighlighterTest extends HeronSolrTest {
  
  @Before
  public void setup () throws SolrServerException, IOException {
    solr.deleteByQuery("*:*");
    solr.commit();
  }

  @Test
  public void testHighlightChapter5() throws SolrServerException, IOException {
    // searching for "gas" didn't work on the Safari site
    indexDocument ("ch5.txt");
    assertTextSnippet (
            "gas",
            "450",
            "Chapter 5\n Prospects and Evolutions of Electric-Powered Vehicles:<b>gas</b> What Technologies &lt; &amp; by 2015?"
    );
  }

  @Test
  public void testPreservedFieldsReturnsMultiValued() throws SolrServerException, IOException {
    // Tests that preservedFields param preserves multiValued fields when only one item is highlighted
    Map<String, Object> extraParams = new HashMap<>();
    String[] fakeNames = {"Another Person", "Robert Gas", "Jane Doe"};
    String[] expectedHighlight = {"Another Person", "Robert <b>Gas</b>", "Jane Doe"};
    extraParams.put("author", fakeNames);
    extraParams.put("publisher", fakeNames);

    indexDocument ("ch5.txt", extraParams);
    QueryResponse resp = getHighlightedResults("gas", "450");
    assertMultiValuedSnippet(resp, "ch5.txt", expectedHighlight, "author");
    assertMultiValuedSnippet(resp, "ch5.txt", expectedHighlight, "publisher");
  }

//  @Test
//  public void testSnippetsSortedByScore() throws Exception {
//    indexDocument ("daly-web-framework.txt");
//    assertTextSnippet (
//            "who had fond memories",
//            "300",
//            "Ruby's status as a next-generation scripting language inspired programmers <b>who</b> <b>had</b> " +
//                    "<b>fond</b> <b>memories</b> of quick Perl projects but did not want to trade flexibility for readability."
//    );
//  }

//  @Test
//  public void testSnippetWithLargerFragSize() throws Exception {
//    indexDocument ("daly-web-framework.txt");
//    assertTextSnippet (
//            "who had fond memories",
//            "450",
//            "Its emphasis on \"convention over configuration\" was a breath of fresh air to developers " +
//                    "<b>who</b> <b>had</b> struggled with configuration-heavy Java frameworks such as Struts. " +
//                    "Ruby's status as a next-generation scripting language inspired programmers <b>who</b> <b>had</b> " +
//                    "<b>fond</b> <b>memories</b> of quick Perl projects but did not want to trade flexibility for readability."
//    );
//  }

  // TODO - randomized testing -- search for phrases and/or words drawn from sentences and
  // expect those same sentences to be returned.

  private QueryResponse getHighlightedResults(String q, String fragSize) throws SolrServerException, IOException {
    SolrQuery query = new SolrQuery(q);
    query.setHighlight(true);
    query.set("hl.fragsize", fragSize);
    query.set("hl.tag.pre", "<b>");
    query.set("hl.tag.ellipsis", "¦");
    query.set("f.text.hl.snippets", 3); // override value of 3 specified in solrconfig.xml
    // query.set("hl.maxAnalyzedChars", 250000);
    QueryResponse resp = solr.query(query);
    SolrDocumentList results = resp.getResults();
    assertEquals (1, results.getNumFound());
    assertNotNull ("PH returns null highlight", resp.getHighlighting());
    return resp;
  }

  private void assertTextSnippet(String q, String fragSize, String expectedSnippet) throws IOException, SolrServerException {
    QueryResponse resp = getHighlightedResults(q, fragSize);
    String snippet = resp.getHighlighting().values().iterator().next().get("text").get(0).split("¦")[0];

    // verify the snippet starts with the expected snippet text; it might be followed
    // by another contiguous snippet with no delimiter (hl.tag.ellipsis above) if there was a
    // match in the following sentence
    int len = expectedSnippet.length();
    assertEquals (expectedSnippet, snippet.trim().substring(0, len));
  }

  private void assertMultiValuedSnippet(QueryResponse resp, String id, String[] expectedValues, String fieldName) throws SolrServerException, IOException {
    Map<String, List<String>> highlightedResults = resp.getHighlighting().get(id);
    List<String> multiValues = highlightedResults.get(fieldName);
    assertEquals(Arrays.asList(expectedValues), multiValues);
  }

  private SolrInputDocument retrieveDocumentFromFile(String filename) throws IOException, SolrServerException {
    InputStream in = getClass().getResourceAsStream(filename);
    String text = IOUtils.toString(in);

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("id", filename);
    doc.addField("text", text);
    in.close();
    return doc;
  }

  private void indexSolrDoc(SolrInputDocument doc) throws IOException, SolrServerException {
    solr.add(doc);
    solr.commit();
  }

  private void indexDocument(String filename) throws IOException, SolrServerException {
    indexSolrDoc(retrieveDocumentFromFile(filename));
  }

  private void indexDocument(String filename, Map<String, Object> extraParams) throws IOException, SolrServerException {
    SolrInputDocument solrDoc = retrieveDocumentFromFile(filename);
    for(String param : extraParams.keySet()) {
      if(extraParams.get(param) != null) {
        solrDoc.addField(param, extraParams.get(param));
      }
    }
    indexSolrDoc(solrDoc);
  }
}
