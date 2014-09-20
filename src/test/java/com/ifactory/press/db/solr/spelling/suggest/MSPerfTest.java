package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse.Suggestion;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class MSPerfTest extends SolrTest {
    
    private static final int NUMDOCS = 100000;

    @Test
    public void testDocValuesWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments("weighted_title_ms", NUMDOCS);
        long t1 = System.nanoTime();
        Suggestion suggestion = assertSuggestionCount("doc", 26);
        assertEquals ("<b>doc</b>a", suggestion.getAlternatives().get(0));
        logTime("testDocValuesWeight", t0, t1);
    }
    
    @Test
    public void testDocFreqWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments("title_ms", NUMDOCS);
        long t1 = System.nanoTime();
        Suggestion suggestion = assertSuggestionCount("doc", 26);
        assertEquals ("<b>doc</b>a", suggestion.getAlternatives().get(0));
        logTime("testDocFreqWeight", t0, t1);
    }

    private void logTime(String testCase, long t0, long t1) {
        System.out.println(String.format ("%s: %f sec", testCase, (t1-t0)/1000000000.0));
    }
    
    @Test
    public void testConstantWeight() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments("title_t", NUMDOCS);
        long t1 = System.nanoTime();
        assertSuggestionCount("doc", 26);
        logTime("testConstantWeight", t0, t1);
    }
    
    @Test
    public void testConstantWeight2() throws Exception {
        // run again after warming
        testConstantWeight();
    }
    
    @Test
    public void testNoSuggestions() throws Exception {
        rebuildSuggester();
        long t0 = System.nanoTime();
        insertTestDocuments("text_t", NUMDOCS);
        long t1 = System.nanoTime();
        assertSuggestionCount("doc", 0);
        logTime("testNoSuggestions", t0, t1);
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
            return suggestion;
        }
        assertNotNull ("No suggestion found for " + prefix, suggestion);
        assertEquals (count, suggestion.getNumFound());
        return suggestion;
    }
    
    private void insertTestDocuments(String field, int numDocs) throws SolrServerException, IOException {
        String [] alphabet = new String[26];
        boolean [] whackamole = new boolean[26];
        int i = 0;
        for (char c = 'a'; c <= 'z'; c++) {
            alphabet[i++] = new String("doc" + c);
        }
        for (i = 0; i < numDocs; i++) {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField("uri", "/doc/" + i);
            // mimic Zipf distribution of words
            int r = (int) Math.round(26 / (1 + 25 * Math.random())) - 1;
            whackamole [r] = true;
            doc.addField(field, alphabet[r]);
            solr.add(doc);
            if (i % 5000 == 4999) {
                // hard commit
                solr.commit(false, true, false);
            }
        }
        solr.commit(false, true, false);
        int whacked = 0;
        for (i=0; i<26; i++) {
            if (whackamole[i]) {
                ++whacked;
            }
        }
        System.out.println ("whacked: " + whacked);
    }
    
    private QueryResponse rebuildSuggester () throws SolrServerException {
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        q.set("spellcheck.index", "suggest-infix-all");
        q.set("spellcheck.build", "true");
        return solr.query(q);
    }
    
}
