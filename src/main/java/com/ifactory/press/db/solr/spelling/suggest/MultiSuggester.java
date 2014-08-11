package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;

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
import org.apache.solr.schema.FieldType;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.spelling.suggest.Suggester;

/**
 * <h3>A suggester that draws suggestions from terms in multiple solr fields.</h3>
 * 
 * <p>Contributions from each field are weighted by a per-field weight, and
 * filtered based on a global minimum threshold term frequency, a per-field minimum and a per-term maximum.
 * All thresholds are expressed as a fraction of total documents containing the term; maximum=0.5 means terms
 * occurring in at least half of all documents will be excluded.</p>
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
      <float name="threshold">0.0</float>
      <!-- true == performance-killer.  Schedule a rebuild nightly instead -->
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
    </lst>

  </searchComponent>
 * }</pre>
 * 
 */
@SuppressWarnings("rawtypes")
public class MultiSuggester extends Suggester {
    
    private WeightedField[] fields;
    
    @Override
    public String init(NamedList config, SolrCore coreParam) {
        String myname = super.init(config, coreParam);
        initWeights ((NamedList) config.get("fields"), coreParam);
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
            FieldType fieldType = coreParam.getLatestSchema().getFieldType(fieldName);
            Analyzer analyzer = fieldType.getAnalyzer();
            fields[ifield] = new WeightedField(fieldName, weight, minFreq, maxFreq, analyzer);
        }
    }
    
    @Override
    public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
        reader = searcher.getIndexReader();
        dictionary = new MultiDictionary();
        for (WeightedField fld : fields) {
            HighFrequencyDictionary hfd = new HighFrequencyDictionary(reader, fld.name, fld.minFreq);
            int minFreq = (int) (fld.minFreq * reader.numDocs());
            int maxFreq = (int) (fld.maxFreq * reader.numDocs());
            ((MultiDictionary)dictionary).addDictionary(hfd, minFreq, maxFreq, fld.weight);
        }
        lookup.build(dictionary);
        // TODO store a persistent copy of the lookup if it supports it
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
            if (! doc.containsKey(fld.name)) {
                continue;
            }
            for (Object value : doc.getFieldValues(fld.name)) {
                TokenStream tokens = fld.analyzer.tokenStream(fld.name, value.toString());
                tokens.reset();
                CharTermAttribute termAtt = tokens.addAttribute(CharTermAttribute.class);
                int floor = (int) Math.floor(fld.minFreq * numDocs);
                int ceil = (int) Math.ceil(fld.maxFreq * numDocs);
                while (tokens.incrementToken()) {
                    fld.term.bytes().copyChars(termAtt);
                    int freq = reader.docFreq(fld.term);
                    if (freq >= floor && freq <= ceil) {
                        long weight = (long) (fld.weight * (float) (freq  + 1) / (numDocs + 1));
                        ais.add(fld.term.bytes(), null, weight, null);
                    }
                    else {
                        ais.update(fld.term.bytes(), null, 0, null);
                    }
                }
                tokens.close();
            }
        }   
    }
    
    public void commit () throws IOException {
        if (! (lookup instanceof AnalyzingInfixSuggester)) {
            return;
        }
        // TODO: ais.getIndexWriter().commit() ??
        AnalyzingInfixSuggester ais = (AnalyzingInfixSuggester) lookup;
        try {
            ais.refresh();
        } catch (NullPointerException e) {
            // just ignore?  Sometimes, intermittently during tests, the AIS.searcherMgr is null?
        }
    }
    
    public void close() throws IOException {
        if (lookup instanceof Closeable) {
            ((Closeable)lookup).close();
        }
    }
    
    class WeightedField {
        final static int MAX_TERM_LENGTH = 128;
        final String name;
        final float weight;
        final float minFreq;
        final float maxFreq;
        final Term term;
        final Analyzer analyzer;
        
        WeightedField (String name, float weight, float minFreq, float maxFreq, Analyzer analyzer) {
            this.name = name;
            this.weight = weight;
            this.minFreq = minFreq;
            this.maxFreq = maxFreq;
            this.term = new Term (name, new BytesRef(MAX_TERM_LENGTH));
            this.analyzer = analyzer;
        }
        
    }
    
}
