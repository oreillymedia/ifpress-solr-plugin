package com.ifactory.press.db.solr.analysis;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;

import java.util.Collections;
import java.util.Map;

/**
 * This analyzer is used by FieldMergingProcessor to enable the same type of analyzer 
 * to be re-used for several instances of the same source field
 */
public final class MultiValuedFieldAnalyzerWrapper extends AnalyzerWrapper {
  private final Analyzer defaultAnalyzer;
  private final Map<String, Analyzer> fieldAnalyzers;
  
  /**
   * Constructs with default analyzer and a map of analyzers to use for 
   * specific fields.
   *
   * @param defaultAnalyzer Any fields not specifically
   * defined to use a different analyzer will use the one provided here.
   * @param fieldAnalyzers a Map (String field name to the Analyzer) to be 
   * used for those fields 
   */
  public MultiValuedFieldAnalyzerWrapper(Analyzer defaultAnalyzer, Map<String, Analyzer> fieldAnalyzers) {
    super(PER_FIELD_REUSE_STRATEGY);
    this.defaultAnalyzer = defaultAnalyzer;
    this.fieldAnalyzers = (fieldAnalyzers != null) ? fieldAnalyzers : Collections.<String, Analyzer>emptyMap();
  }

  @Override
  protected Analyzer getWrappedAnalyzer(String fieldName) {
    Analyzer analyzer = fieldAnalyzers.get(fieldName);
    if (analyzer != null) {
        return analyzer;
    }
    if (Character.isDigit(fieldName.charAt(fieldName.length()-1))) {
        
    }
    return defaultAnalyzer;
  }

  @Override
  public String toString() {
    return "PerFieldAnalyzerWrapper(" + fieldAnalyzers + ", default=" + defaultAnalyzer + ")";
  }
}
