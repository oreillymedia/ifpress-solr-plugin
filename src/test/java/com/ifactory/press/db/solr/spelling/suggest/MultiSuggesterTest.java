package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.*;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse.Suggestion;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class MultiSuggesterTest extends SolrTest {
    
    private static final String TEXT_FIELD = "fulltext_t";
    private static final String TITLE_FIELD = "title_ms";
    private static final String TEST = "Now is the time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";
    private static final String TITLE_SUGGEST = "<b>T</b>he Dawning of a New Era";
    
    @Test
    public void testMultiSuggest() throws Exception {
        // insert ten documents; one of them has the title TITLE
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField(TITLE_FIELD, TITLE);
        doc.addField(TEXT_FIELD, TEST);
        solr.add(doc);
        for (int i = 2; i < 11; i++) {
            doc = new SolrInputDocument();
            doc.addField("uri", "/doc/" + i);
            doc.addField(TITLE_FIELD, "document " + i);
            // 'the' 'to' should get excluded from suggestions by maxWeight configured to 0.3
            doc.addField(TEXT_FIELD, "the to " + i);
            solr.add(doc);
        }
        solr.commit(false, true, true);
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        assertNotNull ("no spell check reponse found", scr);
        // should come first due to higher weighting of title
        Suggestion suggestion = scr.getSuggestion("t");
        assertNotNull ("No suggestion found for 't'", suggestion);
        assertEquals (3, suggestion.getNumFound());
        assertEquals (TITLE_SUGGEST, suggestion.getAlternatives().get(0));
        assertEquals ("<b>t</b>heir", suggestion.getAlternatives().get(1));
        assertEquals ("<b>t</b>ime", suggestion.getAlternatives().get(2));
    }

}
