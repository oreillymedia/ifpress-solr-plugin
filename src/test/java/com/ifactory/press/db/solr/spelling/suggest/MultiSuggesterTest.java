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
    private static final String TITLE_TEXT_FIELD = "title_t";
    private static final String TEXT = "Now is the time time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";
    private static final String TITLE_SUGGEST = "<b>T</b>he Dawning of a New Era";
    
    @Test
    public void testMultiSuggest() throws Exception {
        rebuildSuggester();

        insertTestDocuments(TITLE_FIELD);
        
        SolrQuery q = assertSuggestions();
        
        // Rebuilding the index causes the common terms to be excluded since their freq is visible
        // while the index is being built
        rebuildSuggester();
        Suggestion suggestion = solr.query(q).getSpellCheckResponse().getSuggestion("t");
        assertEquals (3, suggestion.getNumFound());
    }
    
    @Test
    public void testOverrideAnalyzer() throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_TEXT_FIELD);
        assertSuggestions();
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
    
    private SolrQuery assertSuggestions() throws SolrServerException {
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        assertNotNull ("no spell check reponse found", scr);
        // should come first due to higher weighting of title
        Suggestion suggestion = scr.getSuggestion("t");
        assertNotNull ("No suggestion found for 't'", suggestion);
        // max threshold sets weight of common terms to zero but doesn't exclude them
        assertEquals (5, suggestion.getNumFound());
        assertEquals (TITLE_SUGGEST, suggestion.getAlternatives().get(0));
        assertEquals ("<b>t</b>ime", suggestion.getAlternatives().get(1));
        assertEquals ("<b>t</b>heir", suggestion.getAlternatives().get(2));
        return q;
    }

    private void insertTestDocuments(String titleField) throws SolrServerException, IOException {
        // insert ten documents; one of them has the title TITLE
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField(titleField, TITLE);
        doc.addField(TEXT_FIELD, TEXT);
        solr.add(doc);
        solr.commit(false, false, true);
        for (int i = 2; i < 11; i++) {
            doc = new SolrInputDocument();
            doc.addField("uri", "/doc/" + i);
            doc.addField(titleField, "document " + i);
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
    
}
