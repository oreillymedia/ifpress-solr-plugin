package com.ifactory.press.db.solr.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.TermsResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.TermsParams;
import org.apache.solr.core.CoreContainer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class FieldMergingProcessorTest {
    
    private static final String TEST = "Now is the time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";
    static CoreContainer coreContainer;
    SolrServer solr;
    
    @BeforeClass
    public static void startup() throws Exception {
        // start an embedded solr instance
        coreContainer = new CoreContainer("solr");
        coreContainer.load();
    }
    
    @AfterClass
    public static void shutdown() throws Exception {
        coreContainer.shutdown();
    }
    
    @Before
    public void init() throws Exception {
        solr = new EmbeddedSolrServer(coreContainer, "collection1");
        solr.deleteByQuery("*:*");
        solr.commit(true, true);
    }

    // insert text_t and title_t and expect to get titles as phrase tokens and text as word tokens
    // in catchall
    
    @Test
    public void testMergeFields () throws Exception {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField("title_t", TITLE);
        doc.addField("text_t", TEST);
        solr.add(doc);
        solr.commit(true, true);
        
        // basic check that the document was inserted
        SolrQuery solrQuery = new SolrQuery ("uri:\"/doc/1\"");
        QueryResponse resp = solr.query(solrQuery);
        SolrDocumentList docs = resp.getResults();
        assertEquals (1, docs.size());
        SolrDocument result = docs.get(0);
        assertEquals ("/doc/1", result.get("uri"));
        
        // text_t is tokenized, analyzed:
        assertQueryCount (1, "text_t:intentional");

        // title_t is tokenized, analyzed:
        assertQueryCount (1, "title_t:era");
        assertQueryCount (1, "title_t:dawning");
        
        for (TermsResponse.Term term : getTerms("title_t")) {
            assertNotEquals (TITLE, term.getTerm());
        }

        HashSet<String> words = new HashSet<String>(Arrays.asList(TEST.split(" "))); 
        int numWords = words.size();
        List<TermsResponse.Term> terms;
        terms = getTerms("catchall");
        // one term for each word in text + 1 for the title
        assertEquals ("Wrong number of terms in catchall field", numWords + 1, terms.size());
        boolean found = false;
        for (TermsResponse.Term term : terms) {
            if (TITLE.equals(term.getTerm())) {
                found = true;
            }
        }
        assertTrue ("title not found in catchall terms list", found);
    }
    
    private void assertQueryCount (int count, String query) throws SolrServerException {
        SolrQuery solrQuery = new SolrQuery (query);
        QueryResponse resp = solr.query(solrQuery);
        SolrDocumentList docs = resp.getResults();
        assertEquals (count, docs.size());
        
    }
    
    private List<TermsResponse.Term> getTerms (String field) throws SolrServerException {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setParam(CommonParams.QT, "/terms");
        solrQuery.setParam(TermsParams.TERMS, true);
        solrQuery.setParam(TermsParams.TERMS_LIMIT, "100");
        solrQuery.setParam(TermsParams.TERMS_FIELD, field);
        QueryResponse resp = solr.query(solrQuery);
        return resp.getTermsResponse().getTermMap().get(field);
    }

}
