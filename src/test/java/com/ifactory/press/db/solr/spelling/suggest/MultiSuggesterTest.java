package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.*;

import java.io.IOException;

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
    private static final String WEIGHTED_TITLE_FIELD = "weighted_title_ms";
    private static final String TITLE_TEXT_FIELD = "title_t";
    private static final String TEXT = "Now is the time time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";
    private static final String TITLE_SUGGEST = "<b>T</b>he Dawning of a New Era";
    
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
        insertTestDocuments(TITLE_TEXT_FIELD);
        assertSuggestions();
        rebuildSuggester();
        assertSuggestionCount("t", 3);
    }
    
    @Test
    public void testDocValuesWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments(WEIGHTED_TITLE_FIELD, 100);
        long t1 = System.nanoTime();
        assertSuggestionCount("a2", 11);
        System.out.println("testDocValuesWeight: " + (t1-t0) + " ns");
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
        insertTestDocuments(TITLE_TEXT_FIELD, 100);
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
        } else {
            assertNotNull ("No suggestion found for " + prefix, suggestion);
        }
        assertEquals (count, suggestion.getNumFound());
        return suggestion;
    }
    
    private void assertSuggestions() throws SolrServerException {
        Suggestion suggestion = assertSuggestionCount ("t", 5);
        // max threshold sets weight of common terms to zero but doesn't exclude them
        assertEquals (TITLE_SUGGEST, suggestion.getAlternatives().get(0));
        assertEquals ("<b>t</b>ime", suggestion.getAlternatives().get(1));
        assertEquals ("<b>t</b>heir", suggestion.getAlternatives().get(2));
    }

    private void insertTestDocuments(String titleField) throws SolrServerException, IOException {
        insertTestDocuments(titleField, 11);
    }
    
    private void insertTestDocuments(String titleField, int numDocs) throws SolrServerException, IOException {
        // insert ten documents; one of them has the title TITLE
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField(titleField, TITLE);
        doc.addField(TEXT_FIELD, TEXT);
        solr.add(doc);
        solr.commit(false, false, true);
        for (int i = 2; i < numDocs; i++) {
            doc = new SolrInputDocument();
            doc.addField("uri", "/doc/" + i);
            doc.addField(titleField, String.format("a%d document " , i));
            // 'the' 'to' should get excluded from suggestions by maxWeight configured to 0.3
            doc.addField(TEXT_FIELD, "the to " + i);
            solr.add(doc);
            solr.commit(false, false, true);
        }
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
        doc.addField(TITLE_TEXT_FIELD, title);
        solr.add(doc);
        solr.commit(false, false, true);

        String AAAA = title.substring(0, 100);
        assertEquals ("AAAA AAAA ", AAAA.substring(0, 10));
        AAAA = AAAA.replaceAll("AAAA", "<b>AAAA</b>");
        
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
    
}
