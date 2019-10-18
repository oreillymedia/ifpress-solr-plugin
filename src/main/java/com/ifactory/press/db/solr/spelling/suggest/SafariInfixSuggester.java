package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.*;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafariInfixSuggester extends AnalyzingInfixSuggester {

  private final boolean highlight;
  private static final Logger LOG = LoggerFactory.getLogger(SafariInfixSuggester.class);

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
      Override each possible lookup method from AnalyzingInfixSuggester to return empty results
      if suggest build is in progress (instead of throwing error)
   */
  @Override
  public List<LookupResult> lookup(CharSequence key, Set<BytesRef> contexts, boolean onlyMorePopular, int num) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    if (contexts != null) {
      contexts.addAll(showContext);
    } else {
      contexts = showContext;
    }
    return super.lookup(key, contexts, num, true, highlight);
  }

  public List<LookupResult> lookup(CharSequence key, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    return super.lookup(key, (BooleanQuery)null, num, allTermsRequired, doHighlight);
  }

  public List<LookupResult> lookup(CharSequence key, Set<BytesRef> contexts, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    return super.lookup(key, this.toQuery(contexts), num, allTermsRequired, doHighlight);
  }

  public List<LookupResult> lookup(CharSequence key, Map<BytesRef, BooleanClause.Occur> contextInfo, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    return super.lookup(key, this.toQuery(contextInfo), num, allTermsRequired, doHighlight);
  }

  public List<LookupResult> lookup(CharSequence key, BooleanQuery contextQuery, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    return super.lookup(key, contextQuery, num, allTermsRequired, doHighlight);
  }

  private BooleanQuery toQuery(Map<BytesRef, BooleanClause.Occur> contextInfo) {
    if (contextInfo != null && !contextInfo.isEmpty()) {
      BooleanQuery.Builder contextFilter = new BooleanQuery.Builder();
      Iterator var3 = contextInfo.entrySet().iterator();

      while(var3.hasNext()) {
        Map.Entry<BytesRef, BooleanClause.Occur> entry = (Map.Entry)var3.next();
        this.addContextToQuery(contextFilter, (BytesRef)entry.getKey(), (BooleanClause.Occur)entry.getValue());
      }

      return contextFilter.build();
    } else {
      return null;
    }
  }

  private BooleanQuery toQuery(Set<BytesRef> contextInfo) {
    if (contextInfo != null && !contextInfo.isEmpty()) {
      BooleanQuery.Builder contextFilter = new BooleanQuery.Builder();
      Iterator var3 = contextInfo.iterator();

      while(var3.hasNext()) {
        BytesRef context = (BytesRef)var3.next();
        this.addContextToQuery(contextFilter, context, BooleanClause.Occur.SHOULD);
      }

      return contextFilter.build();
    } else {
      return null;
    }
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
