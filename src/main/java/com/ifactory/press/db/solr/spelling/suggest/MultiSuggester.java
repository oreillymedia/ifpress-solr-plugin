package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;
import java.text.BreakIterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
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
        LOG.info("build suggestion index: " + name);
        dictionary = new MultiDictionary();
        for (WeightedField fld : fields) {
            if (fld.useStoredField) {
                buildFromStoredField(fld, searcher.getIndexReader());
            } else {
                buildFromTerms(fld, searcher.getIndexReader());
            }
        }
        lookup.build(dictionary);
        LOG.info("built suggestion index: " + name);
        if (lookup instanceof AnalyzingInfixSuggester) {
            AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
            LOG.info(String.format("suggestion index has %d suggestions", ais.getCount()));
        }
    }
    
    private void buildFromStoredField(WeightedField fld, IndexReader reader) {
        if (fld.fieldAnalyzer != null) {
            throw new IllegalStateException("not supported: analyzing stored fields");
        }
        StoredFieldDictionary sfd = new StoredFieldDictionary(reader, fld.fieldName);
        ((MultiDictionary)dictionary).addDictionary(sfd, 0, 2, fld.weight);
    }

    private void buildFromTerms(WeightedField fld, IndexReader reader) {
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
        AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
        float numDocs = searcher.getIndexReader().numDocs();
        for (WeightedField fld : fields) {
            if (! doc.containsKey(fld.fieldName)) {
                continue;
            }
            for (Object value : doc.getFieldValues(fld.fieldName)) {
                if (fld.fieldAnalyzer == null) {
                    addRaw(ais, value.toString(), (long) fld.weight);
                } else {
                    addAnalyzed (searcher, fld, value.toString(), ais, numDocs);
                }
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
                ais.add(bytes, null, weight, null);
                offset = next;
            }
            // just drop any trailing goo
        } else {
            // add the value unchanged
            bytes.copyChars(value);
            ais.add(bytes, null, (long) weight, null);
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
                if (termAtt.length() == 0) {
                    continue;
                }
                term.bytes().copyChars(termAtt);
                int freq = searcher.docFreq(term);
                if (freq >= floor && freq <= ceil) {
                    long weight = (long) (fld.weight * (float) (freq + 1));
                    ais.add(term.bytes(), null, weight, null);
                    LOG.debug ("add " + term + "; wt=" + weight);
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
        
        WeightedField (String name, float weight, float minFreq, float maxFreq, Analyzer analyzer, boolean useStoredField) {
            this.fieldName = name;
            this.weight = weight;
            this.minFreq = minFreq;
            this.maxFreq = maxFreq;
            this.fieldAnalyzer = analyzer;
            this.useStoredField = useStoredField;
        }
        
    }
    
    class CloseHandler extends CloseHook {

        @Override
        public void preClose(SolrCore core) {
            try {
                close();
            } catch (IOException e) {
                LOG.error("An error occurred while closing: " + e.getMessage(), e);
            }
        }

        @Override
        public void postClose(SolrCore core) {
        }
        
    }
    
}
