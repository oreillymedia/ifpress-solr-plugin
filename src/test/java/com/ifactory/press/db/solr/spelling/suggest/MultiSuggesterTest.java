package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse.Suggestion;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CoreAdminParams.CoreAdminAction;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class MultiSuggesterTest extends SolrTest {

  private static final String TEXT_FIELD = "fulltext_t";
  private static final String TITLE_FIELD = "title_ms";
  private static final String TITLE_VALUE_FIELD = "title_t";
  private static final String FORMAT_FIELD = "format";
  private static final String TEXT = "Now is the time time for all good people to come to the aid of their dawning intentional community";
  private static final String TITLE = "The Dawning of a New Era";

  /*
    This test suite uses highlighted suggestions by default to ensure that suggestion highlighting regression is caught.
    To get non-highlighted suggestions, must add <bool name="highlight">false</bool> to the spellchecker block in solrconfig.xml.
   */

  private String unhighlight(String highlightedString) {
    return highlightedString.replaceAll("</?b>", "");
  }

  private Suggestion assertSuggestionCount(String prefix, int count, String suggester) throws SolrServerException, IOException {
    SolrQuery q = new SolrQuery(prefix);
    q.setRequestHandler("/suggest/" + suggester);
    q.set("spellcheck.q", prefix);
    q.set("spellcheck.count", 100);
    QueryResponse resp = solr.query(q);
    SpellCheckResponse scr = resp.getSpellCheckResponse();
    assertNotNull("no spell check reponse found", scr);
    Suggestion suggestion = scr.getSuggestion(prefix);
    if (count == 0) {
      assertNull("Unexpected suggestion found for " + prefix, suggestion);
      return null;
    } else {
      assertNotNull("No suggestion found for " + prefix, suggestion);
    }
    assertEquals(suggestion.getAlternatives().toString(), count, suggestion.getNumFound());
    return suggestion;
  }

  private void assertNoSuggestions() throws SolrServerException, IOException {
    assertSuggestionCount("t", 0, "all");   
    assertSuggestionCount("a", 0, "title");   
  }
  
  private void assertSuggestions() throws SolrServerException, IOException {
    Suggestion suggestion = assertSuggestionCount("t", 8, "all");   
    // TITLE occurs once in a high-weighted field; t1-t4, etc each occur twice, t5 once, their/time occur once
    // 'the' and 'to' occur too many times and get excluded
    assertEquals ("<b>T</b>he Dawning of a New Era", suggestion.getAlternatives().get(0));
    for (int i = 1; i <=5; i++) {
      String sugg = unhighlight(suggestion.getAlternatives().get(i));
      assertTrue (sugg + " does not match t[1-5]", sugg.matches("t[1-5]"));
    }
    assertTrue (unhighlight(suggestion.getAlternatives().get(6)).matches("their|time"));
    assertTrue (unhighlight(suggestion.getAlternatives().get(7)).matches("their|time"));
    assertNotEquals(suggestion.getAlternatives().get(6), suggestion.getAlternatives().get(7));
  }

  private void insertTestDocuments(String titleField) throws SolrServerException, IOException {
    insertTestDocuments(titleField, 10, true);
  }
  
  private void insertTestDocuments(String titleField, int numDocs) throws SolrServerException, IOException {
    insertTestDocuments(titleField, numDocs, true);
  }

  private void insertTestDocuments(String titleField, int numDocs, boolean commit) throws SolrServerException, IOException {
    // insert ten documents; one of them has the title TITLE
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("uri", "/doc/1");
    doc.addField(titleField, TITLE);
    doc.addField(TEXT_FIELD, TEXT);
    solr.add(doc);
    for (int i = 2; i <= numDocs; i++) {
      doc = new SolrInputDocument();
      doc.addField("uri", "/doc/" + i);
      doc.addField(titleField, String.format("a%d document ", i));
      // 'the' 'to' should get excluded from suggestions by maxWeight configured
      // to 0.3
      doc.addField(TEXT_FIELD, "the the to t" + i / 2);
      solr.add(doc);
    }
    if (commit) {
      solr.commit(false, true, true);
    }
  }

  private QueryResponse rebuildSuggester() throws SolrServerException, IOException {
    SolrQuery q = new SolrQuery("t");
    q.setRequestHandler("/suggest/title");
    q.set("spellcheck.build", "true");
    solr.query(q);
    q.setRequestHandler("/suggest/all");
    return solr.query(q);
  }


  // ********************** Tests ***************************

  /*
   * HERO-2705
   */
  @Test
  public void testSegmentLongSuggestion() throws Exception {
    // erase any lingering data
    rebuildSuggester();

    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("uri", "/doc/1");
    StringBuilder buf = new StringBuilder();
    // fill buf with 26 x 100 chars (AAAA AAAA .... BBBB BBBB ... etc)
    for (char c = 'A'; c <= 'Z'; c++) {
      for (int i = 0; i < 20; i++) {
        for (int j = 0; j < 4; j++) {
          buf.append(c);
        }
        buf.append(' ');
      }
    }
    String title = buf.toString();
    doc.addField(TITLE_VALUE_FIELD, title);
    solr.add(doc);
    solr.commit(false, false, true);

    String AAAA = title.substring(0, 100);
    assertEquals("AAAA AAAA ", AAAA.substring(0, 10));
    AAAA = AAAA.replaceAll("AAAA", "AAAA");

    // suggester is configured to segment at 100 char bounds
    SolrQuery q = new SolrQuery("AAAA");
    q.setRequestHandler("/suggest/all");
    QueryResponse resp = solr.query(q);
    SpellCheckResponse scr = resp.getSpellCheckResponse();
    assertNotNull("no spell check reponse found", scr);
    // should come first due to higher weighting of title
    Suggestion suggestion = scr.getSuggestion("AAAA");
    assertNotNull("No suggestion found for 'AAAA'", suggestion);
    // max threshold sets weight of common terms to zero but doesn't exclude
    // them
    assertEquals(1, suggestion.getNumFound());

    assertEquals(AAAA, unhighlight(suggestion.getAlternatives().get(0)));
  }

  @Test
  public void testExtendedResultFormat() throws Exception {
    rebuildSuggester();
    insertTestDocuments(TITLE_FIELD);
    String suggestQueryString = "t";

    SolrQuery q = new SolrQuery();
    q.set("spellcheck.q", suggestQueryString);
    q.setRequestHandler("/suggest/all");
    QueryResponse resp = solr.query(q);
    SpellCheckResponse scr = resp.getSpellCheckResponse();
    assertNotNull("no spell check reponse found", scr);
    Suggestion suggestion = scr.getSuggestion(suggestQueryString);

    // no extended results
    assertNull(suggestion.getAlternativeFrequencies());

    // extended results
    q.set("spellcheck.extendedResults", true);
    resp = solr.query(q);
    scr = resp.getSpellCheckResponse();
    assertNotNull("no spell check reponse found", scr);
    suggestion = scr.getSuggestion(suggestQueryString);
    assertNotNull(suggestion.getAlternativeFrequencies());
    assertEquals("<b>T</b>he Dawning of a New Era", suggestion.getAlternatives().get(0));
    // The title field is analyzed, so the weight is computed as
    // #occurrences/#docs(w/title) * field-weight
    // = 1 / 10 * 11 * 10000000 = 11000000
    assertEquals(11000000, suggestion.getAlternativeFrequencies().get(0).intValue());
    int last = suggestion.getNumFound() - 1;
    assertTrue(unhighlight(suggestion.getAlternatives().get(last)).matches("their|time"));
    assertTrue(suggestion.getAlternativeFrequencies().get(last) > 0);
  }

  @Test
  public void testMultipleTokenQuery() throws Exception {
    rebuildSuggester();
    insertTestDocuments(TITLE_VALUE_FIELD);
    SolrQuery q = new SolrQuery();
    String suggestQueryString = "the da";
    q.set("spellcheck.q", suggestQueryString);
    q.setRequestHandler("/suggest/all");
    QueryResponse resp = solr.query(q);
    SpellCheckResponse scr = resp.getSpellCheckResponse();
    Suggestion suggestion = scr.getSuggestion(suggestQueryString);
    assertNotNull("no suggestion found for 'the da'", suggestion);
    assertEquals(1, suggestion.getNumFound());
    assertEquals("<b>The</b> <b>Da</b>wning of a New Era", suggestion.getAlternatives().get(0));
  }

  @Test
  public void testEmptyDictionary() throws Exception {
    MultiDictionary dict = new MultiDictionary();
    WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
    Directory dir = new RAMDirectory();
    SafariInfixSuggester s = new SafariInfixSuggester(dir, analyzer, analyzer, 1, true);
    try {
      s.build(dict);
      assertTrue(s.lookup("", false, 1).isEmpty());
    } finally {
      s.close();
    }
  }

  @Test
  public void testBuildStartsFresh() throws Exception {
    rebuildSuggester();
    insertTestDocuments(TITLE_FIELD);
    Suggestion suggestion = assertSuggestionCount("a2", 1, "all");
    assertEquals("<b>a2</b> document", suggestion.getAlternatives().get(0));
    // solr.deleteById("/doc/2");
    solr.deleteByQuery("*:*");
    solr.commit();
    rebuildSuggester();
    assertSuggestionCount("a2", 0, "all");
  }
  
  @Test
  public void testEliminateDuplicates() throws Exception {
    rebuildSuggester();
    // test building incrementally:
    insertTestDocuments(TITLE_FIELD);
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("uri", "/doc/1");
    // add a duplicate value to a field whose stored value is used as a suggestion source
    doc.addField("duplicate_title_t", TITLE.toLowerCase());
    // add a duplicate value to a field whose indexed terms are used as a suggestion source
    // analyzed using a KeywordTokenizer, so the indexed value is the same, but exercises a 
    // different code path 
    doc.addField("keyword", TITLE.toLowerCase());
    solr.add(doc);
    solr.commit();
    Suggestion suggestion = assertSuggestionCount("dawn", 2, "all");
    assertEquals ("The <b>Dawn</b>ing of a New Era", suggestion.getAlternatives().get(0));
    assertEquals ("<b>dawn</b>ing", suggestion.getAlternatives().get(1));
    // test rebuilding using a dictionary:
    rebuildSuggester();
    assertEquals ("The <b>Dawn</b>ing of a New Era", suggestion.getAlternatives().get(0));
    assertEquals ("<b>dawn</b>ing", suggestion.getAlternatives().get(1));
  }

  @Test
  public void testExcludeDocsWithSpecificFormats() throws Exception {
    // Erase any lingering data
    rebuildSuggester();
    // Add a document with book format
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("uri", "/doc/1");
    doc.addField(TITLE_FIELD, "A Nice Book");
    doc.addField(TEXT_FIELD, "This is a very nice book.");
    doc.addField(FORMAT_FIELD, "book");
    solr.add(doc);

    // Add 2 documents, both of which have formats that are excluded in the solrconfig's excludeFormat option.
    SolrInputDocument excludedDoc = new SolrInputDocument();
    excludedDoc.addField("uri", "/doc/2");
    excludedDoc.addField(TITLE_FIELD, "A Nice Playlist");
    excludedDoc.addField(TEXT_FIELD, "This is a nice playlist that should not show in suggestions.");
    excludedDoc.addField(FORMAT_FIELD, "collection");
    SolrInputDocument excludedDoc2 = new SolrInputDocument();
    excludedDoc2.addField("uri", "/doc/3");
    excludedDoc2.addField(TITLE_FIELD, "A Nice Test");
    excludedDoc2.addField(TEXT_FIELD, "This nice test should also not show up.");
    excludedDoc2.addField(FORMAT_FIELD, "test-format");

    solr.add(doc);
    solr.add(excludedDoc);
    solr.add(excludedDoc2);
    solr.commit();

    // There should only be one suggestion: suggestion for the book.
    // The doc with 'collection' format should not show in suggestions at all.
    Suggestion suggestion = assertSuggestionCount("nice", 1, "all");
    assertEquals ("A <b>Nice</b> Book", suggestion.getAlternatives().get(0));
    assertSuggestionCount("Playlist", 0, "all");
    assertSuggestionCount("Test", 0, "all");
  }

  @Test
  public void testMultiSuggest() throws Exception {
    rebuildSuggester();
    assertNoSuggestions();
    insertTestDocuments(TITLE_FIELD);
    assertSuggestions();
    // Rebuilding the index leaves everything the same
    rebuildSuggester();
    assertSuggestions();
  }

  @Test
  public void testDocFreqWeight() throws Exception {
    // ALL
    rebuildSuggester();
    assertNoSuggestions();
    long t0 = System.nanoTime();
    insertTestDocuments(TITLE_FIELD, 100);
    long t1 = System.nanoTime();
    assertSuggestionCount("a2", 11, "all");
    System.out.println("testDocFreqWeight: " + (t1 - t0) + " ns");
  }

  @Test
  public void testConstantWeight() throws Exception {
    // ALL
    rebuildSuggester();
    assertNoSuggestions();
    long t0 = System.nanoTime();
    insertTestDocuments(TITLE_VALUE_FIELD, 100);
    long t1 = System.nanoTime();
    assertSuggestionCount("a2", 11, "all");
    System.out.println("testDocFreqWeight: " + (t1 - t0) + " ns");
  }

  @Test
  public void testOverrideAnalyzer() throws Exception {
    rebuildSuggester();
    assertNoSuggestions();
    insertTestDocuments(TITLE_VALUE_FIELD);
    assertSuggestions();
    assertSuggestionCount("a1", 1, "title");
  }

  @Test
  public void testAutocommit() throws Exception {
    // TITLE
    rebuildSuggester();
    assertNoSuggestions();
    int numDocs = 10;
    insertTestDocuments(TITLE_VALUE_FIELD, numDocs, false);
    Thread.sleep (500); // wait for autocommit
    //solr.commit();
    long numFound = solr.query(new SolrQuery("*:*")).getResults().getNumFound();
    assertEquals (numDocs, numFound);
    assertSuggestions();
    assertSuggestionCount("a1", 1, "title");
  }

  /*
   * test workaround for LUCENE-5477/SOLR-6246
   */
  @Test
  public void testReloadCore() throws Exception {
    SolrInputDocument doc = new SolrInputDocument();
    doc.addField("uri", "/doc/1");
    doc.addField(TITLE_FIELD, TITLE);
    doc.addField(TEXT_FIELD, TEXT);
    solr.add(doc);
    solr.commit(false, true, true);

    CoreAdminRequest reload = new CoreAdminRequest();
    reload.setAction(CoreAdminAction.RELOAD);
    reload.setCoreName("collection1");
    reload.process(solr);
  }

}
