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
import org.junit.Test;

import com.ifactory.press.db.solr.SolrTest;

import java.io.IOException;


public class UpdateDocValuesTest extends SolrTest {
    
    private static final String UPDATE_DOCVALUES = "/update/docvalues";
    private static final String URI = "uri";
    private static final String WEIGHT_DV = "weight_dv";
    private static final String TEXT_FIELD = "text_mt";
    
    @Test 
    public void testBenchDocValues() throws Exception {
      
      //final int numIters = 100000;
      final int numIters = 1;
      
      long t = System.nanoTime();
      insertTestDocuments(numIters);
      long dt = (System.nanoTime() - t) / 1000000;
      System.out.println("inserted " + numIters + " docs in : " + dt);

      t = System.nanoTime();
      updateDocValues(numIters);
      dt = (System.nanoTime() - t) / 1000000;
      System.out.println("updated " + numIters + " docvalues in : " + dt);

      t = System.nanoTime();
      insertTestDocuments(numIters, true);
      dt = (System.nanoTime() - t) / 1000000;
      System.out.println("updated " + numIters + " docs, preserving docvalues, in : " + dt);

    }

    @Test
    /**
     * The happy path - shows that we can update doc values independent of other fields
     */
    public void testUpdateDocValues() throws Exception {
      insertTestDocuments (10);
      
      assertNoDocValues();

      updateDocValues (10);

      assertDocValues(10);
    }

    @Test
    /** We preserve docvalues when updating documents that don't provide a value for their docvalues field */
    public void testUpdateDocument() throws Exception {
        insertTestDocuments(10);
        updateDocValues(10);
        
        insertTestDocuments(10, true); // overwrite the docs, preserving docvalues
        assertDocValues(10);

        insertTestDocuments(10); // overwrite the docs without preserving docvalues
        assertNoDocValues();
    }
    
    @Test
    /**
     * When we insert a document without having set up docvalues
     */
    public void testZeroDefault() throws Exception {
      insertTestDocuments (10, true);
      SolrQuery query = new SolrQuery ("*:*");
      String dvFieldFunction = String.format("field(%s)", WEIGHT_DV);
      query.set ("fl", dvFieldFunction);
      QueryResponse resp = solr.query(query);
      SolrDocumentList docs = resp.getResults();
      assertEquals (0, docs.get(0).getFirstValue(dvFieldFunction));
    }

    private void assertDocValues(int n) throws SolrServerException, IOException {
      SolrQuery query = new SolrQuery ("*:*");
      
      query.setSort(WEIGHT_DV, ORDER.desc);
      assertEquals (uri(1), getFirstUri(query));

      query.setSort(WEIGHT_DV, ORDER.asc);
      assertEquals (uri(n), getFirstUri(query));
    }

    private void assertDocValue(String uri, Integer dv) throws SolrServerException, IOException {
      final SolrQuery query = new SolrQuery (String.format("%s:\"%s\"", URI, uri));
      final String weightField = String.format("field(%s)", WEIGHT_DV);
      query.setFields(URI, weightField);
      QueryResponse resp = solr.query(query);
      SolrDocumentList docs = resp.getResults();
      Integer wt = (Integer) docs.get(0).getFirstValue(weightField);
      assertEquals (dv, wt);
    }

    private String uri(int n) {
      return "/doc:" + n + ":x";
    }
    
    private String uriChild(int n, int k) {
      return "/doc:" + n + ":x/" + k;
    }
    
    private void assertNoDocValues() throws SolrServerException, IOException {
      SolrQuery query = new SolrQuery ("*:*");

      String firstUri = getFirstUri(query);      

      // with no doc values, should get the same doc first:
      query.setSort(WEIGHT_DV, ORDER.desc);
      assertEquals (firstUri, getFirstUri(query));

      // no matter what the order is
      query.setSort(WEIGHT_DV, ORDER.asc);
      assertEquals (firstUri, getFirstUri(query));
    }

    private String getFirstUri (SolrQuery query) throws SolrServerException, IOException {
      QueryResponse resp = solr.query(query);
      SolrDocumentList docs = resp.getResults();
      return (String) docs.get(0).getFirstValue(URI);
    }
    
    @Test
    /** The update service throws an error when key field is provided with no value fields */
    public void testMissingValue() throws Exception {
      insertTestDocuments(1);
      UpdateRequest req = new UpdateRequest();
      SolrInputDocument doc = new SolrInputDocument();
      req.add(doc);
      req.setPath(UPDATE_DOCVALUES);
      req.setParam(UpdateDocValuesProcessor.UPDATEDV_KEY_FIELD, "id");
      req.setParam(UpdateDocValuesProcessor.UPDATEDV_VALUE_FIELD, "x");
      // doc with no value for key
      try {
        solr.request(req);
        assertFalse ("expected exception not thrown", true);
      } catch (SolrException e) {
        assertTrue (e.getMessage(), e.getMessage().contains("no value for updatedv.key.field"));
      }

      doc.addField("id", "id0");
      req.setParam(UpdateDocValuesProcessor.UPDATEDV_VALUE_FIELD, null);
      // no UpdateDocValuesProcessor.UPDATEDV_VALUE_FIELD
      try {
        solr.request(req);
        assertFalse ("expected exception not thrown", true);
      } catch (SolrException e) {
        assertTrue (e.getMessage().contains("missing parameter updatedv.value.field"));
      }
    }
    
    @Test
    /** The service takes the first of multiple values */
    public void testMultipleValues() throws Exception {
      insertTestDocuments(1);
      UpdateRequest req = updateDocValues();
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField(URI, uri(1));
      doc.addField(WEIGHT_DV, 0);
      doc.addField(WEIGHT_DV, 1);
      req.add(doc);
      solr.request(req);
      // no error thrown
    }
    
    @Test
    /** Child documents' values should be set when indexed */
    public void testBlockIndex() throws Exception {
      insertTestDocuments(1, 1, false);
      
      // no doc values yet
      assertDocValue(uri(1), 0);
      assertDocValue(uriChild(1, 1), null);

      UpdateRequest req = updateDocValues();
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField(URI, uri(1));
      doc.addField(WEIGHT_DV, 3);
      req.add(doc);
      solr.request(req);
      solr.commit(false, true, true);
      
      // child doc dv still not set
      assertDocValue(uri(1), 3);
      assertDocValue(uriChild(1, 1), null);
      
      SolrInputDocument child = new SolrInputDocument();
      child.addField(URI, uriChild(1, 1));
      child.addField(WEIGHT_DV, 7);
      doc.addChildDocument(child);
      solr.request(req);
      solr.commit(false, true, true);

      // child doc dv updated
      assertDocValue(uri(1), 3);
      assertDocValue(uriChild(1, 1), 7);
      
      // re-add docs, preserving existing docvalues:
      insertTestDocuments(1, 1, true);
      assertDocValue(uri(1), 3);
      assertDocValue(uriChild(1, 1), 7);
      
    }
    
    private void insertTestDocuments (int n) throws Exception {
      insertTestDocuments (n, false);
    }
    
    private void insertTestDocuments (int n, boolean preserveDV) throws Exception {
      insertTestDocuments(n, 0, preserveDV);
    }
    
    private void insertTestDocuments (int n, int nkids, boolean preserveDV) throws Exception {
      UpdateRequest req = new UpdateRequest();
      req.setPath(UPDATE_DOCVALUES);
      if (preserveDV) {
        req.setParam(UpdateDocValuesProcessor.UPDATEDV_VALUE_FIELD, WEIGHT_DV);
      }
      for (int i = 1; i <= n; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(URI, uri(i));
        doc.addField(TEXT_FIELD, "This is document " + i);
        // NOTE: must provide a value for at least one document in order to create the field:
        // it's not enough to just put it in the solr schema
        if (! preserveDV) {
          doc.addField(WEIGHT_DV, 0);
        }
        for (int j = 1; j <= nkids; j++) {
          SolrInputDocument kid = new SolrInputDocument();
          kid.addField(URI, uriChild(i, j));
          kid.addField(TEXT_FIELD, "This is child document " + i + "/" + j);
          doc.addChildDocument(kid);
        }
        req.add(doc);
      }
      solr.request(req);
      solr.commit(false, true, true);
    }
    
    private void updateDocValues (int n) throws Exception {
      UpdateRequest req = updateDocValues();
      for (int i = 1; i <= n; i++) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField(URI, uri(i));
        doc.addField(WEIGHT_DV, n - i);
        req.add(doc);
      }
      solr.request(req);
      solr.commit(false, true, true);
    }
    
    private UpdateRequest updateDocValues () {
      UpdateRequest req = new UpdateRequest();
      req.setParam(UpdateDocValuesProcessor.UPDATEDV_KEY_FIELD, URI);
      req.setParam(UpdateDocValuesProcessor.UPDATEDV_VALUE_FIELD, WEIGHT_DV);
      req.setPath(UPDATE_DOCVALUES);
      return req;
    }
    
}
