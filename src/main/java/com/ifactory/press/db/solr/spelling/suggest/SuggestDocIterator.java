package com.ifactory.press.db.solr.spelling.suggest;

import org.apache.solr.search.DocIterator;
import java.util.NoSuchElementException;

public class SuggestDocIterator implements DocIterator {

  /*
   * A wrapper Iterator that either uses the DocIterator supplied during initialization
   * or iterates through all doc ids between 0 and supplied maxDoc (similar to a simple for-loop fashion)
   */

  private int maxDoc;
  private int currentDocId;
  private DocIterator docSetIterator;

  public SuggestDocIterator(DocIterator docSetIterator, Integer maxDoc) {
    this.docSetIterator = docSetIterator;
    this.maxDoc = maxDoc;
    this.currentDocId = 0;
  }

  public boolean shouldScanAllDocs() {
    return docSetIterator == null;  // If no custom DocIterator configured, scan all docs
  }

  @Override
  public int nextDoc() {
    if (!hasNext()) {
      throw new NoSuchElementException("There are no remaining documents to be retrieved.");
    }
    return shouldScanAllDocs() ? ++currentDocId : docSetIterator.nextDoc();
  }

  @Override
  public Integer next() {
    return nextDoc();
  }

  @Override
  public boolean hasNext() {
    return shouldScanAllDocs() ? currentDocId < maxDoc - 1 : docSetIterator.hasNext();
  }

  @Override
  public float score() {
    // Irrelevant for our use case, implementation needed for abstract method.
    return 0;
  }
}
