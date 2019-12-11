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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SafariInfixSuggester extends AnalyzingInfixSuggester {

  private final boolean highlight;
  private Map<Suggestion, Long> suggestWeightMap;
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
    suggestWeightMap = new HashMap<>();

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

  @Override
  public void build(InputIterator iter) throws IOException {
    // Reset suggestion HashSet on build
    LOG.info("\n\nStarting suggestion build.");
    suggestWeightMap = new HashMap<>();
    super.build(iter);
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

  /**
   * Adds suggestion, only de-duplicating for the same text AND context,
   * and keeping the duplicate with the highest weight.
   * Calls AnalyzingInfix's add method if adding new suggestion,
   * or AnalyzingInfix's update method if updating with a higher weight for existing suggestion.
   * @param text BytesRef representing the text of suggestion
   * @param contexts Set<BytesRef> representing the filter contexts for the suggestion
   * @param weight the long weight of suggestion
   * @param payload BytesRef payload of suggestion, usually used to store more metadata about suggestion
   * @throws IOException
   */
  @Override
  public void add(BytesRef text, Set<BytesRef> contexts, long weight, BytesRef payload) throws IOException {
    Suggestion suggestion = new Suggestion(text, contexts, weight, payload);
    Long currentSuggestWeight = suggestWeightMap.get(suggestion);

    // Add suggestion if it has not yet been added.
    if(currentSuggestWeight == null) {
      suggestWeightMap.put(suggestion, weight);
      super.add(text, contexts, weight, payload);
    }
    // If suggestion was already added with a lower weight, update suggestion with this weight
    else if(currentSuggestWeight.doubleValue() < weight) {
      suggestWeightMap.put(suggestion, weight);
      super.update(text, contexts, weight, payload);
    }
  }

  /*
      Override each possible lookup method from AnalyzingInfixSuggester to:
      1. Return empty results if suggest build is in progress (instead of throwing error)
      2. Return highlighted suggestion during lookup if it exists
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
    List<LookupResult> lookups = super.lookup(key, contexts, num, true, highlight);
    return extractHighlightedLookups(lookups);
  }

  @Override
  public List<LookupResult> lookup(CharSequence key, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    List<LookupResult> lookups = super.lookup(key, (BooleanQuery)null, num, allTermsRequired, true);
    return extractHighlightedLookups(lookups);
  }

  @Override
  public List<LookupResult> lookup(CharSequence key, Set<BytesRef> contexts, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    List<LookupResult> lookups = super.lookup(key, this.toQuery(contexts), num, allTermsRequired, true);
    return extractHighlightedLookups(lookups);
  }

  @Override
  public List<LookupResult> lookup(CharSequence key, Map<BytesRef, BooleanClause.Occur> contextInfo, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    List<LookupResult> lookups = super.lookup(key, this.toQuery(contextInfo), num, allTermsRequired, true);
    return extractHighlightedLookups(lookups);
  }

  @Override
  public List<LookupResult> lookup(CharSequence key, BooleanQuery contextQuery, int num, boolean allTermsRequired, boolean doHighlight) throws IOException {
    if (super.searcherMgr == null) {
      LOG.info("Attempting to retrieve suggestions while suggest build in progress.");
      return new ArrayList<>();
    }
    List<LookupResult> lookups = super.lookup(key, contextQuery, num, allTermsRequired, true);
    return extractHighlightedLookups(lookups);
  }

  /*
      Returns a list of LookupResults identical to param lookups,
      except it uses the highlightedKey if it exists.

      This is workaround for a Solr bug where Suggestion classes ignore LookupResult's
      highlightedKey field regardless of highlight configurations.
   */
  private List<LookupResult> extractHighlightedLookups(List<LookupResult> lookups) {
    List<LookupResult> highlightedLookups = new ArrayList<>();
    for(LookupResult lr : lookups) {
      if(lr.highlightKey != null) {
        highlightedLookups.add(new LookupResult(lr.highlightKey.toString(), lr.highlightKey, lr.value, lr.payload, lr.contexts));
      }
    }
    return highlightedLookups;
  }

  // The following toQuery methods were taken directly from Lucene source code without modification,
  // as AnalyzingInfixSuggester's toQuery methods are private and cannot be used here.
  private BooleanQuery toQuery(Map<BytesRef, BooleanClause.Occur> contextInfo) {
    if (contextInfo != null && !contextInfo.isEmpty()) {
      BooleanQuery.Builder contextFilter = new BooleanQuery.Builder();
      Iterator contextIter = contextInfo.entrySet().iterator();

      while(contextIter.hasNext()) {
        Map.Entry<BytesRef, BooleanClause.Occur> entry = (Map.Entry)contextIter.next();
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
      Iterator contextIter = contextInfo.iterator();

      while(contextIter.hasNext()) {
        BytesRef context = (BytesRef)contextIter.next();
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
