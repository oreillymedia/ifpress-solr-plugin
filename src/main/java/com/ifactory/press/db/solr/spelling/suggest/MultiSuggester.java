package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.EarlyTerminatingCollector;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.suggest.Suggester;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

/**
 * <h3>A suggester that draws suggestions from terms in multiple solr fields, with special support
 * for unanalyzed stored fields.</h3>
 * 
 * <p>Contributions from each field are weighted by a per-field weight, and
 * filtered based on a global minimum threshold term frequency, a per-field minimum and a per-term maximum.
 * All thresholds are expressed as a fraction of total documents containing the term; maximum=0.5 means terms
 * occurring in at least half of all documents will be excluded.</p>
 * 
 * <p>The field analyzer is used to tokenize the field values; each token becomes a suggestion. The analyzer
 * may be overridden in configuration the spellchecker configuration for the field.  Only the special value 
 * 'string' is supported, which means the suggestions are drawn from the unanalyzed stored field values.
 * There is also some code here that has initial support for an alternate analyzer (not the one associated
 * with the field in the schema), but it hasn't been fully implemented (only the incremental updates work,
 * not build()).
 * </p>
 * 
 * <p>The following sample configuration illustrates a setup where suggestions are drawn from a title field
 * and a full text field, with different weights and thresholds.
 * </p>
 * 
 * <pre>
 * {@code
 *  <!-- Suggester -->
  <searchComponent name="suggest-component" class="solr.SpellCheckComponent">

    <!-- Multiple "Spell Checkers" can be declared and used by this
         component
      -->

    <!-- a spellchecker built from a field of the main index -->
    <lst name="spellchecker">
      <str name="name">suggest-infix-all</str>
      <str name="classname">org.apache.solr.spelling.suggest.MultiSuggester</str>
      <str name="lookupImpl">org.apache.solr.spelling.suggest.fst.AnalyzingInfixLookupFactory</str>
      <str name="suggestAnalyzerFieldType">text</str>
      <int name="maxSuggestionLength">80</int>
      <float name="threshold">0.0</float>
      <!-- true == performance-killer. MultiSuggester handles incremental updates automatically, so there's no need for this anyway. -->
      <str name="buildOnCommit">false</str>
      <lst name="fields">
        <lst name="field">
          <str name="name">fulltext_t</str>
          <float name="weight">1.0</float>
          <float name="minfreq">0.005</float>
          <float name="maxfreq">0.3</float>
        </lst>
        <lst name="field">
          <str name="name">title_ms</str>
          <float name="weight">10.0</float>
        </lst>
        <lst name="field">
          <!-- a field whose values are weighted by the value of another field in the same document -->
          <str name="name">weighted_field_ms</str>
          <str name="weight_field">weight_dv</str>
          <float name="weight">10.0</float>
        </lst>
        <lst name="field">
          <str name="name">title_t</str>
          <analyzerFieldType>string</analyzerFieldType>
          <float name="weight">10.0</float>
        </lst>
      </lst>
    </lst>

  </searchComponent>
 * }</pre>
 * 
 */
@SuppressWarnings("rawtypes")
public class MultiSuggester extends Suggester {
    
    private static final int DEFAULT_MAX_SUGGESTION_LENGTH = 80;

    private static final Logger LOG = LoggerFactory.getLogger(MultiSuggester.class);
    
    private WeightedField[] fields;
    
    private int maxSuggestionLength;
    
    // use a synchronized Multimap - there may be one  with the same name for each core
    private static final ListMultimap<Object, Object> registry = 
            Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
    
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
        initWeights ((NamedList) config.get("fields"), coreParam);
        Integer maxLengthConfig = (Integer) config.get("maxSuggestionLength");
        maxSuggestionLength = maxLengthConfig != null ? maxLengthConfig : DEFAULT_MAX_SUGGESTION_LENGTH;
        registry.put(myname, this);
        core.addCloseHook(new CloseHandler());
        return myname;
    }
    
    private void initWeights (NamedList fieldConfigs, SolrCore coreParam) {
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
            Float maxFreq  = (Float) fieldConfig.get("maxfreq");
            if (maxFreq == null) {
                maxFreq = 1.0f;
            }
            String analyzerFieldTypeName = (String) fieldConfig.get("analyzerFieldType");
            Analyzer fieldAnalyzer;
            
            String weightField = (String) fieldConfig.get("weightField");
            boolean useStoredField = analyzerFieldTypeName != null;
            if (useStoredField) {
                // useStoredField - when re-building, we retrieve the stored field value
                if ("string".equals(analyzerFieldTypeName)) {
                    fieldAnalyzer = null;
                } else {
                    fieldAnalyzer = coreParam.getLatestSchema().getFieldTypeByName(analyzerFieldTypeName).getAnalyzer();
                }
            } else {
                if (weightField != null) {
                    LOG.warn("weight field not supported for terms-based suggestions");
                }
                // Use the existing term values as analyzed by the field
                fieldAnalyzer = coreParam.getLatestSchema().getFieldType(fieldName).getAnalyzer();
            }
            fields[ifield] = new WeightedField(fieldName, weight, minFreq, maxFreq, fieldAnalyzer, useStoredField, weightField);
        }
    }
    
    @Override
    public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
        LOG.info("build suggestion index: " + name);
        dictionary = new MultiDictionary();
        reader = searcher.getIndexReader();
        for (WeightedField fld : fields) {
            if (fld.useStoredField) {
                buildFromStoredField(fld);
            } else {
                buildFromTerms(fld);
            }
        }
        lookup.build(dictionary);
        LOG.info("built suggestion index: " + name);
        if (lookup instanceof AnalyzingInfixSuggester) {
            AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
            LOG.info(String.format("suggestion index has %d suggestions", ais.getCount()));
        }
    }
    
    private void buildFromStoredField(WeightedField fld) {
        if (fld.fieldAnalyzer != null) {
            throw new IllegalStateException("not supported: analyzing stored fields");
        }
        // TODO: handle weightField in StoredFieldDictionary
        StoredFieldDictionary sfd = new StoredFieldDictionary(reader, fld.fieldName);
        ((MultiDictionary)dictionary).addDictionary(sfd, 0, 2, fld.weight);
    }

    private void buildFromTerms(WeightedField fld) {
        HighFrequencyDictionary hfd = new HighFrequencyDictionary(reader, fld.fieldName, fld.minFreq);
        int minFreq = (int) (fld.minFreq * reader.numDocs());
        int maxFreq = (int) (fld.maxFreq * reader.numDocs());
        LOG.debug(String.format("build suggestions for: %s ([%d, %d], %f)", fld.fieldName, minFreq, maxFreq, fld.weight));
        ((MultiDictionary)dictionary).addDictionary(hfd, minFreq, maxFreq, fld.weight);
    }

    @Override
    public void reload(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
        if (lookup instanceof AnalyzingInfixSuggester) {
            // AnalyzingInfixSuggester maintains its own index and sees updates, so we don't need to 
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
     * suggestions for each field are managed using one of the following weighting and
     * update strategies:
     * - constant weight: all terms occurring in the field are weighted equally
     * - frequency weight: terms have a weight that is the field's weight * the number of occurrences
     * frequency-weighted suggestions can have their frequency calculated by:
     * - the value of docFreq() on a source field
     * - a frequency maintained in a docValues field
     * - the current weight in the suggester index
     *   
     * @param doc
     * @param searcher 
     * @throws IOException 
     */
    public void add(SolrInputDocument doc, SolrIndexSearcher searcher) throws IOException {
        if (! (lookup instanceof AnalyzingInfixSuggester)) {
            return;
        }
        AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
        int numDocs = searcher.getIndexReader().numDocs();
        RefCounted<IndexWriter> writer = null;
        for (WeightedField fld : fields) {
            if (! doc.containsKey(fld.fieldName)) {
                continue;
            }
            for (Object value : doc.getFieldValues(fld.fieldName)) {
                String strValue = value.toString();
                if (fld.weightField != null) {
                    // get the number of times this identical document has been inserted
                    TopScoreDocCollector collector = TopScoreDocCollector.create(1, true);
                    Term docTerm = new Term(fld.fieldName, strValue); // a term identifying a single document
                    searcher.search(new TermQuery(docTerm), new EarlyTerminatingCollector(collector, 1));
                    long count = 1;
                    if (collector.topDocs().totalHits > 0) {
                       int docID = collector.topDocs().scoreDocs[0].doc;
                       IndexReaderContext context = searcher.getIndexReader().getContext();
                       for (AtomicReaderContext leaf : context.leaves()) {
                           int reldocid = docID - leaf.docBase;
                           if (reldocid >= 0 && reldocid < leaf.reader().numDocs()) {
                               NumericDocValues ndv = leaf.reader().getNumericDocValues(fld.weightField);
                               if (ndv == null) {
                                   break;
                               }
                               count = ndv.get(reldocid);
                           }
                       }
                       count += 1;
                       if (writer == null) {
                           writer = core.getSolrCoreState().getIndexWriter(core);
                       }
                       writer.get().updateNumericDocValue(docTerm, fld.weightField, count);
                       writer.decref();
                    }
                    // assume freq. distribution is like 1/ord
                    // TODO: normalize using the count of docs having some value for this field 
                    long wt = (long) (fld.weight * Math.log (count) + 1);
                    if (fld.fieldAnalyzer == null) {
                        addRaw(ais, value.toString(), wt);
                    } else {
                        addWithWeight (fld, strValue, ais, wt);
                    }
                } else {
                    if (fld.fieldAnalyzer == null) {
                        addRaw(ais, value.toString(), (long) fld.weight);
                    } else {
                        addWithWeight (fld, strValue, ais, (long) fld.weight);
                    }
                }
            }
            if (writer != null) {
                writer.decref();
            }
        }
    }

    /**
     * Add the value to the suggester, so it will be available as a suggestion. 
     * @param ais the suggester
     * @param weight the weight of the suggestion
     * @param value the value to add
     * @throws IOException
     */
    private void addRaw(AnalyzingInfixSuggester ais, String value, long weight) throws IOException {
        BytesRef bytes = new BytesRef(maxSuggestionLength);
        if (value.length() > maxSuggestionLength) {
            // break the value into segments if it's too long
            BreakIterator scanner = BreakIterator.getWordInstance();
            scanner.setText(value);
            int offset = 0;
            while (offset < value.length() - maxSuggestionLength) {
                int next = scanner.following(offset + maxSuggestionLength - 1);
                bytes.copyChars(value.substring(offset, next));
                ais.update(bytes, null, weight, null);
                offset = next;
            }
            // just drop any trailing goo
        } else {
            // add the value unchanged
            bytes.copyChars(value);
            ais.update(bytes, null, (long) weight, null);
        }
        LOG.trace ("add raw " + value + "; wt=" + weight);
    }
    
    private void addAnalyzed(SolrIndexSearcher searcher, WeightedField fld, String value, AnalyzingInfixSuggester ais, float numDocs) throws IOException {
        // 
        TokenStream tokens = fld.fieldAnalyzer.tokenStream(fld.fieldName, value);
        tokens.reset();
        CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
        int floor = (int) Math.floor(fld.minFreq * numDocs);
        int ceil = (int) Math.ceil(fld.maxFreq * numDocs);
        Term term = new Term (fld.fieldName, new BytesRef(8));
        try {
            while (tokens.incrementToken()) {
                term.bytes().copyChars(termAtt);
                int freq = searcher.docFreq(term);
                if (freq >= floor && freq <= ceil) {
                    long weight = (long) (fld.weight * (float) (freq + 1));
                    ais.update(term.bytes(), null, weight, null);
                    LOG.trace("add " + term + "; wt=" + weight);
                }
                else {
                    //LOG.debug ("update " + fld.term + "; weight=0");
                    ais.update(term.bytes(), null, 0, null);
                }
            }
            tokens.end();
        } finally {
            tokens.close();
        }
    }
    
    private void addWithWeight(WeightedField fld, String value, AnalyzingInfixSuggester ais, long wt) throws IOException {
        // 
        TokenStream tokens = fld.fieldAnalyzer.tokenStream(fld.fieldName, value);
        tokens.reset();
        CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
        Term term = new Term (fld.fieldName, new BytesRef(8));
        try {
            while (tokens.incrementToken()) {
                term.bytes().copyChars(termAtt);
                ais.update(term.bytes(), null, wt, null);
                LOG.trace ("add weighted " + term + "; wt=" + wt);
            }
            tokens.end();
        } finally {
            tokens.close();
        }
    }

    public void commit () throws IOException {
        if (! (lookup instanceof AnalyzingInfixSuggester)) {
            return;
        }
        AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
        ais.refresh();
    }
    
    public void close() throws IOException {
        if (lookup instanceof Closeable) {
            ((Closeable)lookup).close();
        }
    }
    
    class WeightedField {
        final static int MAX_TERM_LENGTH = 128;
        final String fieldName;
        final float weight;
        final float minFreq;
        final float maxFreq;
        final Analyzer fieldAnalyzer;
        final boolean useStoredField;
        final String weightField;
        
        WeightedField (String name, float weight, float minFreq, float maxFreq, Analyzer analyzer, boolean useStoredField, String weightField) {
            this.fieldName = name;
            this.weight = weight;
            this.minFreq = minFreq;
            this.maxFreq = maxFreq;
            this.fieldAnalyzer = analyzer;
            this.useStoredField = useStoredField;
            this.weightField = weightField;
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
    
}
