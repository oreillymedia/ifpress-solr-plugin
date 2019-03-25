package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

public class SafariInfixSuggester extends AnalyzingInfixSuggester {

  private final boolean highlight;

  public enum Context {
    SHOW, HIDE
  };

  private Set<BytesRef> showContext, hideContext;

  public SafariInfixSuggester(
          Directory dir,
          Analyzer indexAnalyzer,
          Analyzer queryAnalyzer,
          int minPrefixChars,
          boolean highlight
  ) throws IOException {
    super(dir, indexAnalyzer, queryAnalyzer, minPrefixChars, true);
    this.highlight = highlight;

    showContext = Collections.singleton(new BytesRef(new byte[] { (byte) Context.SHOW.ordinal() }));
    hideContext = Collections.singleton(new BytesRef(new byte[] { (byte) Context.HIDE.ordinal() }));

    if (!DirectoryReader.indexExists(dir)) {
      // no index in place -- build an empty one so we are prepared for updates
      clear();
    }

  }
  
  public void clear () throws IOException {
    super.build(new EmptyInputIterator());
  }

  public void update(BytesRef bytes, long weight) throws IOException {
    super.update(bytes, weight <= 0 ? hideContext : showContext, weight, null);
  }

  /**
   * Like build(), but without flushing the old entries, and *ignores duplicate entries*
   * 
   * @param dict
   * @throws IOException
   */
  public void add(Dictionary dict) throws IOException {
    InputIterator iter = dict.getEntryIterator();
    BytesRef text;
    while ((text = iter.next()) != null) {
      if (lookup(text.utf8ToString(), 1, true, false).size() > 0) {
        continue;
      }
      add(text, iter.contexts(), iter.weight(), iter.payload());
    }
  }

  /*
   * disable highlighting
   */
  @Override
  public List<LookupResult> lookup(CharSequence key, Set<BytesRef> contexts, boolean onlyMorePopular, int num) throws IOException {
    if (contexts != null) {
      contexts.addAll(showContext);
    } else {
      contexts = showContext;
    }
    return lookup(key, contexts, num, true, highlight);
  }

  static class EmptyInputIterator implements InputIterator {

    @Override
    public BytesRef next() throws IOException {
      return null;
    }

    @Override
    public long weight() {
      return 0;
    }

    @Override
    public BytesRef payload() {
      return null;
    }

    @Override
    public boolean hasPayloads() {
      return false;
    }

    @Override
    public Set<BytesRef> contexts() {
      return null;
    }

    @Override
    public boolean hasContexts() {
      return false;
    }

  }

}
