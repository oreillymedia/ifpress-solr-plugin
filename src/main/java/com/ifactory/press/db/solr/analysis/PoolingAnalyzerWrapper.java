package com.ifactory.press.db.solr.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;

/**
 * This analyzer is used by FieldMergingProcessor to enable the same type of analyzer 
 * to be re-used for several instances of the same source field.  It defines a PooledReuseStrategy
 * that enables multiple analysis chains for the same field to be created, and then released and re-used.
 * Callers should call releaseComponents() when it is known that all analysis chains (TokenStreamComponents)
 * reserved using #setReusableComponents() have been closed and may be reused.
 */
public final class PoolingAnalyzerWrapper extends AnalyzerWrapper {

    private final Analyzer wrapped;
    
  /**
   * Constructs with wrapped analyzer 
   *
   * @param analyzer 
   */
  public PoolingAnalyzerWrapper(Analyzer analyzer) {
    super(POOLED_REUSE_STRATEGY);
    this.wrapped= analyzer;
  }

  @Override
  protected Analyzer getWrappedAnalyzer(String fieldName) {
      return wrapped;
  }
  
  @Override
  public TokenStreamComponents wrapComponents (String fieldName, TokenStreamComponents components) {
      return components;
  }

  @Override
  public String toString() {
    return "MultiValuedFieldAnalyzerWrapper(" + wrapped + ")";
  }
  
  public void release() {
      ((PooledReuseStrategy)getReuseStrategy()).releaseComponents();
  }
  
  public static final PooledReuseStrategy POOLED_REUSE_STRATEGY = new PooledReuseStrategy();
  
  static class PooledReuseStrategy extends Analyzer.ReuseStrategy {
      
      private final HashMap<String, ArrayList<TokenStreamComponents>> pool;
      private final HashMap<String, ArrayList<TokenStreamComponents>> reserved;
      
      public PooledReuseStrategy() {
          pool = new HashMap<String, ArrayList<TokenStreamComponents>>();
          reserved = new HashMap<String, ArrayList<TokenStreamComponents>>();
      }

      /**
       * Retrieves the components from a per-field pool containing components that are free for re-use.
       * If the pool is empty, returns null, causing the caller to create new components.
       */
      @Override
      public TokenStreamComponents getReusableComponents(Analyzer analyzer, String fieldName) {
        ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = pool.get (fieldName);
        if (fieldAnalysisComponents == null) {
            pool.put(fieldName, new ArrayList<Analyzer.TokenStreamComponents>());
            return null;
        } else if (fieldAnalysisComponents.isEmpty()) {
            return null;
        } else {
            return fieldAnalysisComponents.remove(fieldAnalysisComponents.size()-1);
        }
    }

    /**
     * stores the components in a reserved area
     */
    @Override
    public void setReusableComponents(Analyzer analyzer, String fieldName, TokenStreamComponents components) {
        ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = reserved.get (fieldName);
        if (fieldAnalysisComponents == null) {
            fieldAnalysisComponents = new ArrayList<Analyzer.TokenStreamComponents>();
            reserved.put(fieldName, fieldAnalysisComponents);
        }
        fieldAnalysisComponents.add(components);
    }
    
    /**
     * releases all of the reserved TokenStreamComponents into the pool, making them available for re-use
     */
    public void releaseComponents() {
        for (Map.Entry<String,ArrayList<TokenStreamComponents>> entry : reserved.entrySet()) {
            ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = pool.get (entry.getKey());
            assert (fieldAnalysisComponents != null);
            fieldAnalysisComponents.addAll(entry.getValue());
            entry.getValue().clear();
        }
    }
      
  }
}
