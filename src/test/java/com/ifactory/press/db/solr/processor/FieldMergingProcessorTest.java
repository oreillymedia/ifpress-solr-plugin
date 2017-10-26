/*
 * Copyright 2014 Safari Books Online
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ifactory.press.db.solr.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.TermsResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.TermsParams;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

public class FieldMergingProcessorTest extends SolrTest {

    private static final String TEXT_FIELD = "text_mt";
    private static final String TITLE_FIELD = "title_mt";
    private static final String TEST = "Now is the time for all good people to come to the aid of their intentional community";
    private static final String TITLE = "The Dawning of a New Era";

    // insert text_t and title_t and expect to get titles as phrase tokens and text as word tokens
    // in catchall
    @Test
    public void testMergeFields() throws Exception {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("uri", "/doc/1");
        doc.addField(TITLE_FIELD, TITLE);
        doc.addField(TEXT_FIELD, TEST);
        solr.add(doc);
        solr.commit(false, true, true);

        // basic check that the document was inserted
        SolrQuery solrQuery = new SolrQuery("uri:\"/doc/1\"");
        QueryResponse resp = solr.query(solrQuery);
        SolrDocumentList docs = resp.getResults();
        assertEquals(1, docs.size());
        SolrDocument result = docs.get(0);
        assertEquals("/doc/1", result.get("uri"));

        // text field is tokenized, analyzed:
        assertQueryCount(1, TEXT_FIELD + ":intentional");

        // title field is tokenized, analyzed:
        assertQueryCount(1, TITLE_FIELD + ":era");
        assertQueryCount(1, TITLE_FIELD + ":dawning");

        for (TermsResponse.Term term : getTerms(TITLE_FIELD)) {
            assertNotEquals(TITLE, term.getTerm());
        }

        HashSet<String> words = new HashSet<String>(Arrays.asList(TEST.split(" ")));
        int numWords = words.size();
        List<TermsResponse.Term> terms;
        terms = getTerms("catchall");
        // one term for each word in text + 1 for the title
        assertEquals("Wrong number of terms in catchall field", numWords + 1, terms.size());
        boolean found = false;
        for (TermsResponse.Term term : terms) {
            if (TITLE.equals(term.getTerm())) {
                found = true;
            }
        }
        assertTrue("title not found in catchall terms list", found);
    }

    private void assertQueryCount(int count, String query) throws SolrServerException {
        SolrQuery solrQuery = new SolrQuery(query);
        QueryResponse resp = solr.query(solrQuery);
        SolrDocumentList docs = resp.getResults();
        assertEquals(count, docs.size());

    }

    private List<TermsResponse.Term> getTerms(String field) throws SolrServerException {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setParam(CommonParams.QT, "/terms");
        solrQuery.setParam(TermsParams.TERMS, true);
        solrQuery.setParam(TermsParams.TERMS_LIMIT, "100");
        solrQuery.setParam(TermsParams.TERMS_FIELD, field);
        QueryResponse resp = solr.query(solrQuery);
        return resp.getTermsResponse().getTermMap().get(field);
    }

    @Test
    public void testInsertMultiple() throws Exception {
        // test committing batches of documents to see if we can successfully re-use the analysis
        // chain?
        for (int i = 0; i < 10; i++) {
            List<SolrInputDocument> docs = new ArrayList<SolrInputDocument>();
            for (int j = 0; j < 10; j++) {
                SolrInputDocument doc = new SolrInputDocument();
                doc.addField("uri", "/doc/" + i * 10 + j);
                if (j % 3 > 0) {
                    // add some docs that don't have one field or the other
                    doc.addField(TITLE_FIELD, TITLE);
                }
                if (j % 2 > 0) {
                    // sometimes add the field value twice; this tickled a TokenStream contract violation
                    doc.addField(TITLE_FIELD, TITLE);
                }
                if (j % 5 != 1) {
                    doc.addField(TEXT_FIELD, TEST);
                }
                docs.add(doc);
            }
            solr.add(docs);
            solr.commit(false, false);
        }
        solr.commit(true, true);
    }

}
