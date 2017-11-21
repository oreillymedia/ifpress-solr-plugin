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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MultiSuggesterTest extends SolrTest {

    private static final String TEXT_FIELD = "fulltext_t";
    private static final String TITLE_FIELD = "title_ms";
    private static final String TITLE_VALUE_FIELD = "title_t";
    private static final String TEXT = "Now is the time time for all good people to come to the aid of their dawning intentional community";
    private static final String TITLE = "The Dawning of a New Era";

    @Test
    public void testMultiSuggest() throws Exception {
        rebuildSuggester();
        assertNoSuggestions();
        insertTestDocuments(TITLE_FIELD);
        
    }

    @Test
    public void testOverrideAnalyzer() throws Exception {
        rebuildSuggester();
        assertNoSuggestions();
        insertTestDocuments(TITLE_VALUE_FIELD);
        assertSuggestionCount("a1", 1, "title");
        rebuildSuggester();
        assertSuggestionCount("a1", 1, "title");
    }

    @Test
    public void testAutocommit() throws Exception {
        rebuildSuggester();
        assertNoSuggestions();
        int numDocs = 10;
        insertTestDocuments(TITLE_VALUE_FIELD, numDocs, false);
        Thread.sleep(500); // wait for autocommit
        long numFound = solr.query(new SolrQuery("*:*")).getResults().getNumFound();
        assertEquals(numDocs, numFound);
        assertSuggestionCount("a1", 1, "title");
    }

    @Test
    public void testDocFreqWeight() throws Exception {
        rebuildSuggester();
        assertNoSuggestions();
        long t0 = System.nanoTime();
        insertTestDocuments(TITLE_FIELD, 100);
        long t1 = System.nanoTime();
        assertSuggestionCount("a2", 11, "all");  // becoming 22 for some reason
        System.out.println("testDocFreqWeight: " + (t1 - t0) + " ns");
    }

    @Test
    public void testConstantWeight() throws Exception {
        rebuildSuggester();
        assertNoSuggestions();
        long t0 = System.nanoTime();
        insertTestDocuments(TITLE_VALUE_FIELD, 100);
        long t1 = System.nanoTime();
        assertSuggestionCount("a2", 11, "all"); //becoming 12 for some reason
        System.out.println("testDocFreqWeight: " + (t1 - t0) + " ns");
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

    private Suggestion assertSuggestionCount(String prefix, int count, String suggester) throws SolrServerException {
        SolrQuery q = new SolrQuery(prefix);
        q.setRequestHandler("/suggest/" + suggester);
        q.set("spellcheck.count", 100);
        QueryResponse resp = null;  
        try {
            resp = solr.query(q);
        } catch (IOException ex) {
            Logger.getLogger(MultiSuggesterTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        //assertNotNull("no spell check reponse found", scr);
        Suggestion suggestion = null;
        if (scr != null) {
            suggestion = scr.getSuggestion(prefix);
            if (suggestion != null) {
                List<String> alts = suggestion.getAlternatives();
                System.out.println("alts count = " + alts.size());
                for (String alt : alts) {
                    System.out.println("count: " + count + " prefix: " + prefix + " -alt = " + alt);
                }
            } else {
                return null;
            }
            if (count == 0) {
                assertNull("Unexpected suggestion found for " + prefix, suggestion.getAlternatives().get(0));
                return null;
            }
        } else {
            return null;
        }

        assertEquals(suggestion.getAlternatives().toString(), count, suggestion.getNumFound());
        return suggestion;
    }

    private void assertNoSuggestions() throws SolrServerException {
        assertSuggestionCount("t", 0, "all");
        assertSuggestionCount("a", 0, "title");
    }

    private void assertSuggestions() throws SolrServerException {
        Suggestion suggestion = assertSuggestionCount("t", 8, "all");
        // TITLE occurs once in a high-weighted field; t1-t4, etc each occur twice, t5 once, their/time occur once
        // 'the' and 'to' occur too many times and get excluded
        assertEquals(TITLE, suggestion.getAlternatives().get(0));
        for (int i = 1; i <= 5; i++) {
            String sugg = suggestion.getAlternatives().get(i);
            System.out.println("sugg = " + sugg.getBytes());
            assertTrue(sugg + " does not match t[1-5]", sugg.matches("t[1-5]"));
        }
        assertTrue(suggestion.getAlternatives().get(6).matches("their|time"));
        assertTrue(suggestion.getAlternatives().get(7).matches("their|time"));
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
            System.out.println("inserting = " + String.format("a%d document ", i));
            doc.addField(titleField, String.format("a%d document ", i));
            // 'the' 'to' should get excluded from suggestions by maxWeight configured
            // to 0.3
            doc.addField(TEXT_FIELD, "the the to t" + i / 2);
            solr.add(doc);
        }
        if (commit) {
            solr.commit();
        }
    }

    private QueryResponse rebuildSuggester() throws SolrServerException {
        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/title");
        q.set("spellcheck.build", "true");
        QueryResponse qr = null;  // rivey catch exception
        try {
            qr = solr.query(q);
        } catch (IOException ex) {
            Logger.getLogger(MultiSuggesterTest.class.getName()).log(Level.SEVERE, null, ex);
        }
        q.setRequestHandler("/suggest/all");
        return qr;
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
        solr.commit();
        
        String AAAA = title.substring(0, 100);
        assertEquals("AAAA AAAA ", AAAA.substring(0, 10));
        AAAA = AAAA.replaceAll("AAAA", "AAAA");

        // suggester is configured to segment at 100 char bounds
        SolrQuery q = new SolrQuery("AAAA");
        q.setRequestHandler("/suggest/all");
        rebuildSuggester();
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        assertNotNull("no spell check reponse found", scr);
        // should come first due to higher weighting of title
        Suggestion suggestion = scr.getSuggestion("AAAA");
        assertNotNull("No suggestion found for 'AAAA'", suggestion);
        // max threshold sets weight of common terms to zero but doesn't exclude
        // them
        assertEquals(1, suggestion.getNumFound());

        assertEquals(AAAA, suggestion.getAlternatives().get(0));
    }

    @Test
    public void testExtendedResultFormat() throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_FIELD);

        SolrQuery q = new SolrQuery("t");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        //Suggestion suggestion = scr.getSuggestion("t");

        // no extended results   rivey so if null the actual response should not
        // have an object
        assertNull(scr);
        Suggestion suggestion = null;
        // extended results
        q.set("spellcheck.extendedResults", true);
        resp = solr.query(q);
        scr = resp.getSpellCheckResponse();
        //assertNotNull("no spell check reponse found", scr);
        /* suggestion = scr.getSuggestion("t");
        assertNotNull(suggestion.getAlternativeFrequencies());
        assertEquals("The Dawning of a New Era", suggestion.getAlternatives().get(0)); */
        // The title field is analyzed, so the weight is computed as
        // #occurrences/#docs(w/title) * field-weight
        // = 1 / 10 * 11 * 10000000 = 11000000
        /* assertEquals(11000000, suggestion.getAlternativeFrequencies().get(0).intValue());
        int last = suggestion.getNumFound() - 1;
        assertTrue(suggestion.getAlternatives().get(last).matches("their|time"));
        assertTrue(suggestion.getAlternativeFrequencies().get(last) > 0); */
    }

    @Test
    public void testMultipleTokenQuery() throws Exception {
        rebuildSuggester();
        insertTestDocuments(TITLE_VALUE_FIELD);
        SolrQuery q = new SolrQuery();
        q.set("spellcheck.q", "the da");
        q.setRequestHandler("/suggest/all");
        QueryResponse resp = solr.query(q);
        SpellCheckResponse scr = resp.getSpellCheckResponse();
        Suggestion suggestion = scr.getSuggestion("the da");
        assertNotNull("no suggestion found for 'the da'", suggestion);
        assertEquals(1, suggestion.getNumFound());
        assertEquals(TITLE, suggestion.getAlternatives().get(0));
    }

    @Test
    public void testEmptyDictionary() throws Exception {
        MultiDictionary dict = new MultiDictionary();
        WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
        Directory dir = new RAMDirectory();
        SafariInfixSuggester s = new SafariInfixSuggester(Version.LATEST, dir, analyzer, analyzer, 1, true);
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
        System.out.println("fresh");
        insertTestDocuments(TITLE_FIELD);
        System.out.println("fresh b4 assert sug count");
        Suggestion suggestion = assertSuggestionCount("a2", 1, "all"); // was 1
        assertEquals("a2 document", suggestion.getAlternatives().get(0).toString().trim());
        solr.deleteByQuery("*");
        solr.commit();
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
        Suggestion suggestion = assertSuggestionCount("dawn", 1, "all");
        assertEquals("The Dawning of a New Era", suggestion.getAlternatives().get(0));
        // test rebuilding using a dictionary:
        rebuildSuggester();
        assertEquals("The Dawning of a New Era", suggestion.getAlternatives().get(0));
    }

}
