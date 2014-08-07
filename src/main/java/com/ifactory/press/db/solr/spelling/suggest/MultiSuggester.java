package com.ifactory.press.db.solr.spelling.suggest;

import java.io.Closeable;
import java.io.IOException;

import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
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
    
    private NamedList fields;
    
    @Override
    public String init(NamedList config, SolrCore coreParam) {
        String myname = super.init(config, coreParam);
        fields = (NamedList) config.get("fields");
        return myname;
    }
    
    @Override
    public void build(SolrCore coreParam, SolrIndexSearcher searcher) throws IOException {
        reader = searcher.getIndexReader();
        dictionary = new MultiDictionary();
        for (int ifield = 0; ifield < fields.size(); ifield++) {
            NamedList fieldConfig = (NamedList) fields.getVal(ifield);
            String fieldName = (String) fieldConfig.get("name");
            Float weight = (Float) fieldConfig.get("weight");
            if (weight == null) {
                weight = 1.0f;
            }
            Float minFreq = (Float) fieldConfig.get("minfreq");
            Float maxFreq  = (Float) fieldConfig.get("maxfreq");
            int minWeight, maxWeight;
            if (minFreq == null) {
                minFreq = 0.0f;
                minWeight = 0;
            } else {
                minWeight = (int) (minFreq * reader.numDocs());
            }
            if (maxFreq == null) {
                maxWeight = reader.numDocs();
            } else {
                maxWeight = (int) (maxFreq * reader.numDocs());
            }
            HighFrequencyDictionary hfd = new HighFrequencyDictionary(searcher.getIndexReader(), fieldName, minFreq);
            ((MultiDictionary)dictionary).addDictionary(hfd, minWeight, maxWeight, weight);
        }
        lookup.build(dictionary);
        // TODO store a persistent copy of the lookup if it supports it
    }
    
    public void close() throws IOException {
        if (lookup instanceof Closeable) {
            ((Closeable)lookup).close();
        }
    }

}
