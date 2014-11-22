package com.safari.bigram;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;

public class SafariDocRetriever implements Iterable<String>, Iterator<String> {

  private final SolrServer server;
  private final String q;
  private final String textField;
  private final String idField;
  public long totalDocCount;
  private IterState iterState;
  private CircBuffer<String> texts;
  private static final int BATCH_SIZE = 10;
  private final static int THREAD_COUNT = 8;
  private final static int MAX_OFFSET = THREAD_COUNT * BATCH_SIZE * 10;
  private AtomicInteger activeThreads;

  public SafariDocRetriever(String solrUrl, String query, String idField, String textField) {
    server = new HttpSolrServer(solrUrl);
    this.q = query;
    this.idField = idField;
    this.textField = textField;

    iterState = new IterState("*", 0);

    texts = new CircBuffer<String>(BATCH_SIZE * THREAD_COUNT);
    activeThreads = new AtomicInteger(THREAD_COUNT);

    for (int i = 0; i < THREAD_COUNT; i++) {
      new Thread(new SolrDocReader()).start();
    }

  }

  @Override
  public Iterator<String> iterator() {
    return this;
  }

  @Override
  public boolean hasNext() {
    return !texts.isEmpty() || activeThreads.get() > 0;
  }

  @Override
  public String next() {
    while (hasNext()) {
      String text = texts.pop();
      if (text != null) {
        return text;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    return null;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  class IterState {

    String id;
    int offset;

    IterState(String id, int offset) {
      this.id = id;
      this.offset = offset;
    }

    public int compareTo(IterState other) {
      int idcmp = id.compareTo(other.id);
      if (idcmp == 0) {
        return this.offset - other.offset;
      }
      if (idcmp == 1) {
        if (this.offset < other.offset) {
          // unknown
          return -1;
        }
        return 1;
      }
      return idcmp;
    }

    public String toString() {
      return id + "[" + offset + "]";
    }

  }

  synchronized IterState getIterState() {
    IterState state = iterState;
    if (state.offset > MAX_OFFSET) {
      return null;
    }
    iterState = new IterState(state.id, state.offset + BATCH_SIZE);
    return state;
  }

  synchronized void advanceIterState(IterState prevState, IterState nextState) {
    // System.err.println (iterState + ": " + prevState + "->" + nextState);
    if (prevState.compareTo(iterState) >= 0) {
      // System.err.println (nextState);
      iterState = nextState;
    }
  }

  class SolrDocReader implements Runnable {

    public void run() {
      // System.out.println ("running query: " + query());
      for (;;) {
        IterState state = getIterState();
        if (state == null) {
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
          }
          continue;
        }
        SolrQuery solrQuery = new SolrQuery(query(state.id));
        solrQuery.setFields(idField, textField);
        solrQuery.setStart(state.offset);
        solrQuery.setRows(BATCH_SIZE);
        solrQuery.setSort(idField, ORDER.asc);
        // System.err.println(solrQuery);
        QueryResponse resp;
        try {
          resp = server.query(solrQuery);
        } catch (SolrServerException e) {
          throw new RuntimeException(e);
        }
        int i = 0;
        String[] docs = new String[BATCH_SIZE];
        for (SolrDocument result : resp.getResults()) {
          String text = (String) result.getFieldValue(textField);
          if (text != null) {
            docs[i++] = text;
          }
        }
        if (resp.getResults().isEmpty()) {
          activeThreads.decrementAndGet();
          return;
        }
        while (! texts.push(docs, 0, i)) {
          try {
            Thread.sleep (20);
          } catch (InterruptedException e) {
          }
        }
        IterState nextState = new IterState(resp.getResults().get(i - 1).get(idField).toString(), 0);
        state.offset += BATCH_SIZE;
        advanceIterState(state, nextState);
      }
    }

    private String query(String id) {
      return String.format("+(%s) +%s:{%s TO *]", q, idField, id);
    }

  }

}
