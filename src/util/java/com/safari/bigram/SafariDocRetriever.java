package com.safari.bigram;

import java.util.Iterator;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;

public class SafariDocRetriever implements Iterable<String> {
  
  private final SolrServer server;
  private final String q;
  private final String textField;
  private final String idField;
  public long totalDocCount;
  
  public SafariDocRetriever (String solrUrl, String query, String idField, String textField) {
    server = new HttpSolrServer(solrUrl);
    this.q = query;
    this.idField = idField;
    this.textField = textField;
  }

  @Override
  public Iterator<String> iterator() {
    try {
      return new SafariDocIterator();
    } catch (SolrServerException e) {
      throw new RuntimeException (e);
    }
  }
  
  class SafariDocIterator implements Iterator<String> {
    
    String lastID;
    int batchSize = 50;
    int posInBatch;
    String[] texts;
    
    SafariDocIterator () throws SolrServerException {
      lastID = "*";
      System.out.println ("running query: " + query());
      SolrQuery solrQuery = new SolrQuery(query());
      solrQuery.setRows(0);
      totalDocCount = server.query(solrQuery).getResults().getNumFound();
      if (totalDocCount < batchSize) {
        batchSize = (int) totalDocCount;
      }
      System.out.println ("found " + totalDocCount + " documents");
      texts = new String[batchSize];
      posInBatch = batchSize;
    }
    
    @Override
    public boolean hasNext() {
      return batchSize > 0;
    }

    @Override
    public String next() {
      if (posInBatch < batchSize) {
        return texts[posInBatch++];
      }
      System.out.println ("running query: " + query());
      SolrQuery solrQuery = new SolrQuery(query());
      solrQuery.setFields(idField, textField);
      solrQuery.setRows(batchSize);
      solrQuery.setSort(idField, ORDER.asc);
      QueryResponse resp;
      try {
        resp = server.query(solrQuery);
      } catch (SolrServerException e) {
        throw new RuntimeException (e);
      }
      int i = 0;
      for (SolrDocument result : resp.getResults()) {
        String text = (String) result.getFieldValue(textField);
        if (text == null) {
          System.err.println("document " + result.getFieldValue(idField) + " has no text");
          text = "";
        }
        texts[i++] = text;
      }
      batchSize = i;
      posInBatch = 0;
      if (batchSize > 0) {
        lastID = (String) resp.getResults().get(batchSize-1).getFieldValue(idField);
        return texts[posInBatch++];
      }
      return "";
    }

    private String query() {
      return String.format("+(%s) +%s:{%s TO *]", q, idField, lastID);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
    
  }

}
