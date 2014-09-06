package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.suggest.Suggester;
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
      </lst>
      <lst name="fields">
        <lst name="field">
          <str name="name">title_ms</str>
          <float name="weight">10.0</float>
        </lst>
      </lst>
      <lst name="fields">
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
            if (analyzerFieldTypeName != null) {
                if ("string".equals(analyzerFieldTypeName)) {
                    fieldAnalyzer = null;
                } else {
                    fieldAnalyzer = coreParam.getLatestSchema().getFieldTypeByName(analyzerFieldTypeName).getAnalyzer();
                }
            } else {
                fieldAnalyzer = coreParam.getLatestSchema().getFieldType(fieldName).getAnalyzer();
            }
            fields[ifield] = new WeightedField(fieldName, weight, minFreq, maxFreq, fieldAnalyzer, analyzerFieldTypeName != null);
        }
    }
    
    @Override
    public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
        reader = searcher.getIndexReader();
        LOG.info("build suggestion index: " + name);
        dictionary = new MultiDictionary();
        for (WeightedField fld : fields) {
            if (fld.useStoredField) {
                buildFromStoredField(fld);
            } else {
                buildFromTerms(fld);
            }
        }
        lookup.build(dictionary);
    }
    
    private void buildFromStoredField(WeightedField fld) {
        if (fld.fieldAnalyzer != null) {
            throw new IllegalStateException("not supported: analyzing stored fields");
        }
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
     * @param doc
     * @param searcher 
     * @throws IOException 
     */
    public void add(SolrInputDocument doc, SolrIndexSearcher searcher) throws IOException {
        if (! (lookup instanceof AnalyzingInfixSuggester)) {
            return;
        }
        reader = searcher.getIndexReader();
        AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
        float numDocs = reader.numDocs();
        for (WeightedField fld : fields) {
            if (! doc.containsKey(fld.fieldName)) {
                continue;
            }
            for (Object value : doc.getFieldValues(fld.fieldName)) {
                if (fld.fieldAnalyzer == null) {
                    String str = value.toString();
                    if (str.length() > maxSuggestionLength) {
                        BreakIterator scanner = BreakIterator.getWordInstance();
                        scanner.setText(str);
                        int offset = 0;
                        while (offset < str.length() - maxSuggestionLength) {
                            int next = scanner.following(offset + maxSuggestionLength - 1);
                            addRaw(ais, fld, str.substring(offset, next));
                            offset = next;
                        }
                        // just drop any trailing goo??
                        /*
                        if (offset + 20 < str.length()) {
                            addRaw(ais, fld, str.substring(offset));
                        }
                        */
                    } else {
                        addRaw(ais, fld, value);
                    }
                } else {
                    addAnalyzed (fld, value.toString(), ais, numDocs);
                }
            }
        }   
    }

    private void addRaw(AnalyzingInfixSuggester ais, WeightedField fld, Object value) throws IOException {
        // just add the unanalyzed field value
        fld.term.bytes().copyChars(value.toString());
        ais.add(fld.term.bytes(), null, (long) fld.weight, null);
        LOG.debug ("add raw " + value + "; wt=" + fld.weight);
    }
    
    private void addAnalyzed(WeightedField fld, String value, AnalyzingInfixSuggester ais, float numDocs) throws IOException {
        // 
        TokenStream tokens = fld.fieldAnalyzer.tokenStream(fld.fieldName, value);
        tokens.reset();
        CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
        int floor = (int) Math.floor(fld.minFreq * numDocs);
        int ceil = (int) Math.ceil(fld.maxFreq * numDocs);
        while (tokens.incrementToken()) {
            fld.term.bytes().copyChars(termAtt);
            int freq = reader.docFreq(fld.term);
            if (freq >= floor && freq <= ceil) {
                long weight = (long) (fld.weight * (float) (freq + 1));
                ais.add(fld.term.bytes(), null, weight, null);
                LOG.debug ("add " + fld.term + "; wt=" + weight);
            }
            else {
                //LOG.debug ("update " + fld.term + "; weight=0");
                ais.update(fld.term.bytes(), null, 0, null);
            }
        }
        tokens.close();
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
        final Term term;
        final Analyzer fieldAnalyzer;
        final boolean useStoredField;
        
        WeightedField (String name, float weight, float minFreq, float maxFreq, Analyzer analyzer, boolean useStoredField) {
            this.fieldName = name;
            this.weight = weight;
            this.minFreq = minFreq;
            this.maxFreq = maxFreq;
            this.term = new Term (name, new BytesRef(MAX_TERM_LENGTH));
            this.fieldAnalyzer = analyzer;
            this.useStoredField = useStoredField;
        }
        
    }
    
}
