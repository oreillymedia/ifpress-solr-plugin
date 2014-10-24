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

import static org.junit.Assert.*;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.core.CoreContainer;
import org.junit.Ignore;
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;


public class UpdateDocValuesTest extends SolrTest {
    
    private static final String URI = "uri";
    private static final String WEIGHT_DV = "weight_dv";
    private static final String TEXT_FIELD = "text_mt";
    static CoreContainer coreContainer;
    
    @Test 
    @Ignore
    public void testBenchDocValues() throws Exception {
      long t = System.nanoTime();
      insertTestDocuments(10000);
      long dt = (System.nanoTime() - t) / 1000000;
      System.out.println("inserted 10000 docs in : " + dt);

      t = System.nanoTime();
      updateDocValues(10000);
      dt = (System.nanoTime() - t) / 1000000;
      System.out.println("inserted 10000 docs in : " + dt);
    }

    @Test
    public void testUpdateDocValues() throws Exception {
      insertTestDocuments (10);
      
      SolrQuery query = new SolrQuery ("*:*");
      String firstUri = getFirstUri(query);
      

      // with no doc values, should get the same doc first:
      query.setSort(WEIGHT_DV, ORDER.desc);
      assertEquals (firstUri, getFirstUri(query));

      // no matter what the order is
      query.setSort(WEIGHT_DV, ORDER.asc);
      assertEquals (firstUri, getFirstUri(query));

      updateDocValues (10);

      query.setSort(WEIGHT_DV, ORDER.desc);
      assertEquals ("/doc/1", getFirstUri(query));

      query.setSort(WEIGHT_DV, ORDER.asc);
      assertEquals ("/doc/10", getFirstUri(query));
    }
    
    private String getFirstUri (SolrQuery query) throws SolrServerException {
      QueryResponse resp = solr.query(query);
      SolrDocumentList docs = resp.getResults();
      return (String) docs.get(0).getFirstValue(URI);
    }
    
    @Test
    public void testMissingArgument() throws Exception {
      insertTestDocuments(1);
      UpdateRequest req = new UpdateRequest();
      SolrInputDocument doc = new SolrInputDocument();
      req.add(doc);
      req.setPath("/update/docvalues");
      try {
        solr.request(req);
        assertFalse ("expected exception not thrown", true);
      } catch (SolrException e) {
        assertTrue (e.getMessage().contains("missing required parameter"));
      }
    }
    
    @Test
    public void testMultipleValues() throws Exception {
      insertTestDocuments(1);
      UpdateRequest req = updateDocValues();
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField(URI, "/doc/1");
      doc.addField(WEIGHT_DV, 0);
      doc.addField(WEIGHT_DV, 1);
      req.add(doc);
      solr.request(req);
      // no error thrown
    }
    
    private void insertTestDocuments (int n) throws Exception {
      for (int i = 1; i <= n; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(URI, "/doc/" + i);
        doc.addField(TEXT_FIELD, "This is document " + i);
        // NOTE: must provide a value for at least one document in order to create the field:
        // it's not enough to just put it in the solr schema
        doc.addField(WEIGHT_DV, 0);
        solr.add(doc);
      }
      solr.commit(false, true, true);
    }
    
    private void updateDocValues (int n) throws Exception {
      UpdateRequest req = updateDocValues();
      for (int i = 1; i <= n; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(URI, "/doc/" + i);
        doc.addField(WEIGHT_DV, n - i);
        req.add(doc);
      }
      solr.request(req);
      solr.commit(false, true, true);
    }
    
    private UpdateRequest updateDocValues () {
      UpdateRequest req = new UpdateRequest();
      req.setParam("key.field", URI);
      req.setParam("value.field", WEIGHT_DV);
      req.setPath("/update/docvalues");
      return req;
    }
    
}
