package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
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
    private static final String TEXT = "Now is the time time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";
    
    @Test
    public void testMultiSuggest() throws Exception {
        rebuildSuggester();

        insertTestDocuments(TITLE_FIELD);
        
        assertSuggestions();
        
        // Rebuilding the index causes the common terms to be excluded since their freq is visible
        // while the index is being built
        rebuildSuggester();
        assertSuggestionCount("t", 3);
    }
    
    @Test
    public void testOverrideAnalyzer() throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_VALUE_FIELD);
        assertSuggestions();
        rebuildSuggester();
        assertSuggestionCount("t", 3);
    }
    
    @Test
    public void testDocFreqWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments(TITLE_FIELD, 100);
        long t1 = System.nanoTime();
        assertSuggestionCount("a2", 11);
        System.out.println("testDocFreqWeight: " + (t1-t0) + " ns");
    }
    
    @Test
    public void testConstantWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments(TITLE_VALUE_FIELD, 100);
        long t1 = System.nanoTime();
        assertSuggestionCount("a2", 11);
        System.out.println("testDocFreqWeight: " + (t1-t0) + " ns");
    }
    
    /*
     * test workaround for LUCENE-5477/SOLR-6246
     */
    @Test
    public void testReloadCore () throws Exception {
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
    
    private Suggestion assertSuggestionCount(String prefix, int count) throws SolrServerException {
        SolrQuery q = new SolrQuery(prefix);
        q.setRequestHandler("/suggest/all");
        q.set("spellcheck.count", 100);
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        assertNotNull ("no spell check reponse found", scr);
        Suggestion suggestion = scr.getSuggestion(prefix);
        if (count == 0) {
            assertNull ("Unexpected suggestion found for " + prefix, suggestion);
            return null;
        } else {
            assertNotNull ("No suggestion found for " + prefix, suggestion);
        }
        assertEquals (count, suggestion.getNumFound());
        return suggestion;
    }
    
    private void assertSuggestions() throws SolrServerException {
        Suggestion suggestion = assertSuggestionCount ("t", 5);
        // max threshold sets weight of common terms to zero but doesn't exclude them
        assertEquals (TITLE, suggestion.getAlternatives().get(0));
        assertEquals ("time", suggestion.getAlternatives().get(1));
        assertEquals ("their", suggestion.getAlternatives().get(2));
    }

    private void insertTestDocuments(String titleField) throws SolrServerException, IOException {
        insertTestDocuments(titleField, 10);
    }
    
    private void insertTestDocuments(String titleField, int numDocs) throws SolrServerException, IOException {
        // insert ten documents; one of them has the title TITLE
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField(titleField, TITLE);
        doc.addField(TEXT_FIELD, TEXT);
        solr.add(doc);
        for (int i = 1; i < numDocs; i++) {
            doc = new SolrInputDocument();
            doc.addField("uri", "/doc/" + i);
            doc.addField(titleField, String.format("a%d document " , i));
            // 'the' 'to' should get excluded from suggestions by maxWeight configured to 0.3
            doc.addField(TEXT_FIELD, "the the to " + i);
            solr.add(doc);
        }
        solr.commit(false, true, true);
    }
    
    private QueryResponse rebuildSuggester () throws SolrServerException {
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        q.set("spellcheck.index", "suggest-infix-all");
        q.set("spellcheck.build", "true");
        return solr.query(q);
    }
    
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
        assertEquals ("AAAA AAAA ", AAAA.substring(0, 10));
        AAAA = AAAA.replaceAll("AAAA", "AAAA");
        
        // suggester is configured to segment at 100 char bounds
        SolrQuery q = new SolrQuery("AAAA");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        assertNotNull ("no spell check reponse found", scr);
        // should come first due to higher weighting of title
        Suggestion suggestion = scr.getSuggestion("AAAA");
        assertNotNull ("No suggestion found for 'AAAA'", suggestion);
        // max threshold sets weight of common terms to zero but doesn't exclude them
        assertEquals (1, suggestion.getNumFound());
        
        assertEquals (AAAA, suggestion.getAlternatives().get(0));
    }
    
    @Test
    public void testExtendedResultFormat () throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_FIELD);
        
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        Suggestion suggestion = scr.getSuggestion("t");    
        
        // no extended results
        assertNull (suggestion.getAlternativeFrequencies());
        
        // extended results
        q.set("spellcheck.extendedResults", true);
        resp = solr.query(q);
        scr = resp.getSpellCheckResponse();
        assertNotNull ("no spell check reponse found", scr);
        suggestion = scr.getSuggestion("t");    
        assertNotNull (suggestion.getAlternativeFrequencies());
        assertEquals ("The Dawning of a New Era", suggestion.getAlternatives().get(0));
        // The title field is analyzed, so the weight is computed as #occurrences/#docs(w/title) * field-weight 
        // = 1 / 10 * 11 * 10000000 = 11000000
        assertEquals (11000000, suggestion.getAlternativeFrequencies().get(0).intValue());
        int last = suggestion.getNumFound() - 1;
        assertEquals ("to", suggestion.getAlternatives().get(last));
        assertEquals (0, suggestion.getAlternativeFrequencies().get(last).intValue());
    }
    
    @Test
    public void testMultipleTokenQuery () throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_VALUE_FIELD);
        SolrQuery q = new SolrQuery();
        q.set("spellcheck.q", "the da");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        Suggestion suggestion = scr.getSuggestion("the da");
        assertNotNull ("no suggestion found for 'the da'", suggestion);
        assertEquals (1, suggestion.getNumFound());
        assertEquals (TITLE, suggestion.getAlternatives().get(0));
    }
    
    @Test
    public void testEmptyDictionary() throws Exception {
        MultiDictionary dict = new MultiDictionary();
        WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
        Directory dir = new RAMDirectory();
        SafeInfixSuggester s  = new SafeInfixSuggester(Version.LATEST, dir, analyzer, analyzer, 1, true);
        try {
            s.build(dict);
            assertTrue (s.lookup("", false, 1).isEmpty());
        } finally {
            s.close();
        }
    }
    
    @Test
    public void testBuildStartsFresh() throws Exception {
      insertTestDocuments(TITLE_FIELD);
      Suggestion suggestion = assertSuggestionCount ("a2", 1);
      assertEquals ("a2 document ", suggestion.getAlternatives().get(0));
      //solr.deleteById("/doc/2");
      solr.deleteByQuery("*:*");
      solr.commit();
      rebuildSuggester();
      assertSuggestionCount("a2", 0);
    }

    
}
