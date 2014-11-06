package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.SpellingOptions;
import org.apache.solr.spelling.SpellingResult;
import org.apache.solr.spelling.suggest.Suggester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * <h3>A suggester that draws suggestions from terms in multiple fields.</h3>
 * 
 * <p>
 * Contributions from each field are weighted by a per-field weight, and
 * zero-weighted based on a global minimum threshold term frequency, a per-field
 * minimum and a per-term maximum. All thresholds are compared against (term
 * frequency / document count), an estimate of the fraction of documents
 * containing the term, maximum=0.5 means terms occurring at least as many times
 * as half the number of documents will be given a weight of zero.
 * </p>
 * 
 * <p>
 * The field analyzer is used to tokenize the field values; each token becomes a
 * suggestion.
 * </p>
 * 
 * <p>
 * An alternate mode of operation provides for unanalyzed stored field values to
 * be used as suggestions. This mode is selected by specifying
 * analyzerFieldType=string in the suggester configuration. In this mode, every
 * suggestion has the constant weight 1.
 * </p>
 * 
 * <p>
 * The following sample configuration illustrates a setup where suggestions are
 * drawn from a title field and a full text field, with different weights and
 * thresholds.
 * </p>
 * 
 * <pre>
 * {@code
 *  <!-- Suggester -->
 *   <searchComponent name="suggest-component" class="solr.SpellCheckComponent">
 * 
 *     <!-- Multiple "Spell Checkers" can be declared and used by this
 *          component
 *       -->
 * 
 *     <!-- a spellchecker built from a field of the main index -->
 *     <lst name="spellchecker">
 *       <str name="name">suggest-infix-all</str>
 *       <str name="classname">org.apache.solr.spelling.suggest.MultiSuggester</str>
 *       <str name="lookupImpl">org.apache.solr.spelling.suggest.fst.AnalyzingInfixLookupFactory</str>
 *       <str name="suggestAnalyzerFieldType">text</str>
 *       <int name="maxSuggestionLength">80</int>
 *       <float name="threshold">0.0</float>
 *       <!-- true == performance-killer. MultiSuggester handles incremental updates automatically, so there's no need for this anyway. -->
 *       <str name="buildOnCommit">false</str>
 *       <lst name="fields">
 *         <lst name="field">
 *           <str name="name">fulltext_t</str>
 *           <float name="weight">1.0</float>
 *           <float name="minfreq">0.005</float>
 *           <float name="maxfreq">0.3</float>
 *         </lst>
 *         <lst name="field">
 *           <str name="name">title_ms</str>
 *           <float name="weight">10.0</float>
 *         </lst>
 *         <lst name="field">
 *           <!-- a field whose values are weighted by the value of another field in the same document -->
 *           <str name="name">weighted_field_ms</str>
 *           <str name="weight_field">weight_dv</str>
 *           <float name="weight">10.0</float>
 *         </lst>
 *         <lst name="field">
 *           <str name="name">title_t</str>
 *           <analyzerFieldType>string</analyzerFieldType>
 *           <float name="weight">10.0</float>
 *         </lst>
 *       </lst>
 *     </lst>
 * 
 *   </searchComponent>
 * }
 * </pre>
 * 
 * 
 * NOTE: the incremental weighting scheme gives an artifical "advantage" to
 * infrequent terms that happen to be indexed first because their weights are
 * normalized when the number of documents is low. To avoid this, it's
 * recommended to rebuild the index periodically. If the index is large and
 * growing relatively slowly, this effect will be very small, though.
 */
@SuppressWarnings("rawtypes")
public class MultiSuggester extends Suggester {

  public static final int DEFAULT_MAX_SUGGESTION_LENGTH = 80;

  // weights are stored internally as longs, but externally as small floating
  // point numbers. The floating point weights are multiplied by this factor to
  // convert
  // them to longs with a sufficient range. WEIGHT_SCALE should be greater than
  // the
  // number of documents
  private static final int WEIGHT_SCALE = 10000000;

  private static final Logger LOG = LoggerFactory.getLogger(MultiSuggester.class);

  private WeightedField[] fields;

  private int maxSuggestionLength;

  // use a synchronized Multimap - there may be one with the same name for each
  // core
  private static final ListMultimap<Object, Object> registry = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());

  @Override
  public String init(NamedList config, SolrCore coreParam) {
    String myname = (String) config.get(DICTIONARY_NAME);
    this.core = coreParam;

    // Workaround for SOLR-6246 (lock exception on core reload): close
    // any suggester registered with the same name.

    if (registry.containsKey(myname)) {
      MultiSuggester suggesterToClose = null;
      for (Object o : registry.get(myname)) {
        MultiSuggester suggester = (MultiSuggester) o;
        if (suggester.core.getName().equals(coreParam.getName())) {
          suggesterToClose = suggester;
          break;
        }
      }
      if (suggesterToClose != null) {
        registry.remove(myname, suggesterToClose);
        try {
          suggesterToClose.close();
        } catch (IOException e) {
          LOG.error("An exception occurred while closing the spellchecker", e);
        }
      }
    }
    super.init(config, coreParam);
    // effectively disable analysis *by the SpellChecker/Suggester component*
    // because this leads
    // to independent suggestions for each token; we want AIS to perform
    // analysis and consider the tokens together
    analyzer = new KeywordAnalyzer();
    initWeights((NamedList) config.get("fields"), coreParam);
    Integer maxLengthConfig = (Integer) config.get("maxSuggestionLength");
    maxSuggestionLength = maxLengthConfig != null ? maxLengthConfig : DEFAULT_MAX_SUGGESTION_LENGTH;
    registry.put(myname, this);
    core.addCloseHook(new CloseHandler());
    return myname;
  }

  private void initWeights(NamedList fieldConfigs, SolrCore coreParam) {
    fields = new WeightedField[fieldConfigs.size()];
    for (int ifield = 0; ifield < fieldConfigs.size(); ifield++) {
      NamedList fieldConfig = (NamedList) fieldConfigs.getVal(ifield);
      String fieldName = (String) fieldConfig.get("name");
      Float weight = (Float) fieldConfig.get("weight");
      if (weight == null) {
        weight = 1.0f;
      }
      Float minFreq = (Float) fieldConfig.get("minfreq");
      if (minFreq == null) {
        minFreq = 0.0f;
      }
      Float maxFreq = (Float) fieldConfig.get("maxfreq");
      if (maxFreq == null) {
        maxFreq = 1.0f;
      }
      String analyzerFieldTypeName = (String) fieldConfig.get("analyzerFieldType");
      Analyzer fieldAnalyzer;

      boolean useStoredField = analyzerFieldTypeName != null;
      if (useStoredField) {
        // useStoredField - when re-building, we retrieve the stored field value
        if ("string".equals(analyzerFieldTypeName)) {
          fieldAnalyzer = null;
        } else {
          fieldAnalyzer = coreParam.getLatestSchema().getFieldTypeByName(analyzerFieldTypeName).getAnalyzer();
        }
      } else {
        // Use the existing term values as analyzed by the field
        fieldAnalyzer = coreParam.getLatestSchema().getFieldType(fieldName).getAnalyzer();
      }
      fields[ifield] = new WeightedField(fieldName, weight, minFreq, maxFreq, fieldAnalyzer, useStoredField);
    }
    Arrays.sort(fields);
  }

  @Override
  public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
    LOG.info("build suggestion index: " + name);
    reader = searcher.getIndexReader();

    SafariInfixSuggester ais = (SafariInfixSuggester) lookup;
    ais.clear();
    
    // index all the terms-based fields using dictionaries
    for (WeightedField fld : fields) {
      if (fld.useStoredField) {
        buildFromStoredField(fld, searcher);
      } else {
        // TODO: refactor b/c we're not really using the MultiDictionary's multiple dictionary capability any more
        dictionary = new MultiDictionary(maxSuggestionLength);
        buildFromTerms(fld);
        ais.add(dictionary);
        ais.refresh();
      }
    }
    LOG.info(String.format("%s suggestion index built: %d suggestions", name, ais.getCount()));
  }

  private void buildFromStoredField(WeightedField fld, SolrIndexSearcher searcher) throws IOException {
    if (fld.fieldAnalyzer != null) {
      throw new IllegalStateException("not supported: analyzing stored fields");
    }
    LOG.info(String.format("build suggestions from values for: %s (%d)", fld.fieldName, fld.weight));
    HashSet<String> fieldsToLoad = new HashSet<String>();
    fieldsToLoad.add(fld.fieldName);
    int maxDoc = searcher.maxDoc();
    for (int idoc = 0; idoc < maxDoc; ++idoc) {
      // TODO: exclude deleted documents
      Document doc = reader.document(idoc++, fieldsToLoad);
      String value = doc.get(fld.fieldName);
      if (value != null) {
        addRaw(fld, value);
      }
      if (idoc % 10000 == 9999) {
        commit(searcher);
      }
    }
    commit(searcher);
  }

  private void buildFromTerms(WeightedField fld) throws IOException {
    HighFrequencyDictionary hfd = new HighFrequencyDictionary(reader, fld.fieldName, fld.minFreq);
    int numDocs = reader.getDocCount(fld.fieldName);
    int minFreq = (int) (fld.minFreq * numDocs);
    int maxFreq = (int) (fld.maxFreq * numDocs);
    LOG.info(String.format("build suggestions from terms for: %s (min=%d, max=%d, weight=%d)", fld.fieldName, minFreq, maxFreq, fld.weight));
    ((MultiDictionary) dictionary).addDictionary(hfd, minFreq, maxFreq, fld.weight / (2 + numDocs));
  }

  @Override
  public void reload(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
    if (lookup instanceof AnalyzingInfixSuggester) {
      // AnalyzingInfixSuggester maintains its own index and sees updates, so we
      // don't need to
      // build it every time the core starts or is reloaded
      AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
      if (ais.getCount() > 0) {
        LOG.info("load existing suggestion index");
        return;
      }
    }
    build(core, searcher);
  }

  /**
   * Adds the field values from the document to the suggester
   * 
   * suggestions for each field are managed using one of the following weighting
   * and update strategies: - constant weight: all terms occurring in the field
   * are weighted equally - frequency weight: terms have a weight that is the
   * field's weight * the number of occurrences frequency-weighted suggestions
   * can have their frequency calculated by: - the value of docFreq() on a
   * source field - a frequency maintained in a docValues field - the current
   * weight in the suggester index
   * 
   * @param doc
   * @param searcher
   * @throws IOException
   */
  public void add(SolrInputDocument doc, SolrIndexSearcher searcher) throws IOException {
    if (!(lookup instanceof SafariInfixSuggester)) {
      return;
    }
    for (WeightedField fld : fields) {
      if (!doc.containsKey(fld.fieldName)) {
        continue;
      }
      fld.pendingDocCount++;
      for (Object value : doc.getFieldValues(fld.fieldName)) {
        String strValue = value.toString();
        if (fld.fieldAnalyzer == null) {
          addRaw(fld, strValue);
        } else {
          addTokenized(fld, strValue);
        }
      }
    }
  }

  /**
   * Add the value to the suggester, so it will be available as a suggestion.
   * 
   * @param ais
   *          the suggester
   * @param weight
   *          the weight of the suggestion
   * @param value
   *          the value to add
   * @throws IOException
   */
  private void addRaw(WeightedField fld, String value) throws IOException {
    if (value.length() > maxSuggestionLength) {
      // break the value into segments if it's too long
      BreakIterator scanner = BreakIterator.getWordInstance();
      scanner.setText(value);
      int offset = 0;
      while (offset < value.length() - maxSuggestionLength) {
        int next = scanner.following(offset + maxSuggestionLength - 1);
        incPending(fld, value.substring(offset, next));
        offset = next;
      }
      // just drop any trailing goo
    } else {
      // add the value unchanged
      incPending(fld, value);
    }
    // LOG.debug ("add raw " + value);
  }

  private void addTokenized(WeightedField fld, String value) throws IOException {
    TokenStream tokens = fld.fieldAnalyzer.tokenStream(fld.fieldName, value);
    tokens.reset();
    CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
    HashSet<String> once = new HashSet<String>();
    try {
      while (tokens.incrementToken()) {
        String token = termAtt.toString();
        token = MultiDictionary.stripAfflatus(token);
        if (once.add(token)) {
          // only add each token once per field value to keep frequencies in line with
          // HighFrequencyDictionary, which counts using TermsEnum.docFreq()
          incPending(fld, token);
          // LOG.debug("add token " + token);
        }
      }
      tokens.end();
    } finally {
      tokens.close();
    }
  }

  private void incPending(WeightedField fld, String suggestion) {
    if (fld.pending.containsKey(suggestion)) {
      fld.pending.put(suggestion, fld.pending.get(suggestion) + 1);
    } else {
      fld.pending.put(suggestion, 1);
    }
  }

  public void commit(SolrIndexSearcher searcher) throws IOException {
    if (!(lookup instanceof SafariInfixSuggester)) {
      return;
    }
    SafariInfixSuggester ais = (SafariInfixSuggester) lookup;
    for (WeightedField fld : fields) {
      // get the number of documents having this field
      long docCount = searcher.getIndexReader().getDocCount(fld.fieldName) + fld.pendingDocCount;
      fld.pendingDocCount = 0;
      // swap in a new pending map so we can accept new suggestions while we
      // commit
      ConcurrentHashMap<String, Integer> batch = fld.pending;
      fld.pending = new ConcurrentHashMap<String, Integer>(batch.size());
      BytesRef bytes = new BytesRef(maxSuggestionLength);
      Term t = new Term(fld.fieldName, bytes);
      long minCount = (long) (fld.minFreq * docCount);
      long maxCount = (long) (docCount <= 1 ? Long.MAX_VALUE : (fld.maxFreq * docCount + 1));
      for (Map.Entry<String, Integer> e : batch.entrySet()) {
        String term = e.getKey();
        // check for duplicates
        if (ais.lookup(term, 1, true, false).size() > 0) {
          // LOG.debug("skipping duplicate " + term);
          continue;
        }
        // TODO: incorporate external metric (eg popularity) into weight
        long weight;
        if (fld.fieldAnalyzer == null) {
          weight = fld.weight;
        } else {
          long count = searcher.getIndexReader().docFreq(t);
          if (count < 0) {
            // FIXME: is this even possible?
            count = e.getValue();
          } else {
            count += e.getValue();
          }
          if (count < minCount || count > maxCount) {
            weight = 0;
          } else {
            weight = (fld.weight * count) / docCount;
          }
        }
        bytes.copyChars(term);
        // LOG.debug("add " + bytes.utf8ToString());
        ais.update(bytes, weight);
      }
    }
    // refresh after each field so the counts will accumulate across fields?
    ais.refresh();
  }

  public void close() throws IOException {
    if (lookup instanceof Closeable) {
      ((Closeable) lookup).close();
    }
  }
  
  /**
   * Note: this class has a natural ordering that is inconsistent with equals.
   */
  class WeightedField implements Comparable<WeightedField> {
    final static int MAX_TERM_LENGTH = 128;
    final String fieldName;
    final long weight;
    final float minFreq;
    final float maxFreq;
    final Analyzer fieldAnalyzer;
    final boolean useStoredField;
    private ConcurrentHashMap<String, Integer> pending;
    private int pendingDocCount;

    WeightedField(String name, float weight, float minFreq, float maxFreq, Analyzer analyzer, boolean useStoredField) {
      this.fieldName = name;
      this.weight = (long) (weight * WEIGHT_SCALE);
      this.minFreq = minFreq;
      this.maxFreq = maxFreq;
      this.fieldAnalyzer = analyzer;
      this.useStoredField = useStoredField;
      pending = new ConcurrentHashMap<String, Integer>();
      pendingDocCount = 0;
    }

    @Override
    public String toString() {
      return fieldName + '^' + weight;
    }

    @Override
    public int compareTo(WeightedField fld) {
      // sort from highest to lowest
      return (int) (fld.weight - weight);
    }

  }

  class CloseHandler extends CloseHook {

    @Override
    public void preClose(SolrCore c) {
      try {
        close();
      } catch (IOException e) {
        LOG.error("An error occurred while closing: " + e.getMessage(), e);
      }
    }

    @Override
    public void postClose(SolrCore c) {
    }

  }

  @Override
  public SpellingResult getSuggestions(SpellingOptions options) throws IOException {
    SpellingResult result = super.getSuggestions(options);
    if (options.extendedResults) {
      for (Map.Entry<Token, LinkedHashMap<String, Integer>> suggestion : result.getSuggestions().entrySet()) {
        Token token = suggestion.getKey();
        int freq = 0;
        for (Map.Entry<String, Integer> e : suggestion.getValue().entrySet()) {
          if (e.getKey().equals(token.toString())) {
            freq = e.getValue();
            break;
          }
        }
        result.addFrequency(token, freq);
      }
    }
    return result;
  }

}
