/*
 * Copyright 2014 Safari Books Online
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ifactory.press.db.solr.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;

/**
 * This analyzer is used by FieldMergingProcessor to enable the same type of
 * analyzer to be re-used for several instances of the same source field. It
 * defines a PooledReuseStrategy that enables multiple analysis chains for the
 * same field to be created, and then released and re-used. Callers should call
 * releaseComponents() when it is known that all analysis chains
 * (TokenStreamComponents) reserved using #setReusableComponents() have been
 * closed and may be reused.
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
        this.wrapped = analyzer;
        POOLED_REUSE_STRATEGY.createPool(this);
    }

    @Override
    protected Analyzer getWrappedAnalyzer(String fieldName) {
        return wrapped;
    }

    @Override
    public String toString() {
        return "PoolingAnalyzerWrapper(" + wrapped + ")";
    }

    public void release() {
        ((PooledReuseStrategy) getReuseStrategy()).releaseComponents(this);
    }

    public static final PooledReuseStrategy POOLED_REUSE_STRATEGY = new PooledReuseStrategy();

    protected static class PooledReuseStrategy extends Analyzer.ReuseStrategy {

        public PooledReuseStrategy() {
        }

        public void createPool (Analyzer analyzer) {
            setStoredValue(analyzer, new TokenStreamComponentsPool());
        }

        private TokenStreamComponentsPool getPool(Analyzer analyzer) {
            return (TokenStreamComponentsPool) getStoredValue(analyzer);
        }
        
        /**
         * Retrieves the components from a per-field pool containing components
         * that are free for re-use. If the pool is empty, returns null, causing
         * the caller to create new components.
         */
        @Override
        public TokenStreamComponents getReusableComponents(Analyzer analyzer, String fieldName) {
            return getPool(analyzer).get(fieldName);
        }

        /**
         * stores the components in a reserved area
         */
        @Override
        public void setReusableComponents(Analyzer analyzer, String fieldName, TokenStreamComponents components) {
            getPool(analyzer).put(fieldName, components);
        }

        /**
         * releases all of the reserved TokenStreamComponents into the pool,
         * making them available for re-use
         */
        public void releaseComponents(Analyzer analyzer) {
            getPool(analyzer).release();
        }

    }

    protected static class TokenStreamComponentsPool {
        private final HashMap<String, ArrayList<TokenStreamComponents>> available;
        private final HashMap<String, ArrayList<TokenStreamComponents>> reserved;

        protected TokenStreamComponentsPool() {
            available = new HashMap<String, ArrayList<TokenStreamComponents>>();
            reserved = new HashMap<String, ArrayList<TokenStreamComponents>>();
        }

        protected TokenStreamComponents get(String fieldName) {
            ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = available.get(fieldName);
            if (fieldAnalysisComponents == null) {
                available.put(fieldName, new ArrayList<Analyzer.TokenStreamComponents>());
                return null;
            } else if (fieldAnalysisComponents.isEmpty()) {
                return null;
            } else {
                return fieldAnalysisComponents.remove(fieldAnalysisComponents.size() - 1);
            }
        }

        protected void put(String fieldName, TokenStreamComponents components) {
            ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = reserved.get(fieldName);
            if (fieldAnalysisComponents == null) {
                fieldAnalysisComponents = new ArrayList<Analyzer.TokenStreamComponents>();
                reserved.put(fieldName, fieldAnalysisComponents);
            }
            fieldAnalysisComponents.add(components);
        }

        protected void release() {
            for (Map.Entry<String, ArrayList<TokenStreamComponents>> entry : reserved.entrySet()) {
                ArrayList<Analyzer.TokenStreamComponents> fieldAnalysisComponents = available.get(entry.getKey());
                assert (fieldAnalysisComponents != null);
                fieldAnalysisComponents.addAll(entry.getValue());
                entry.getValue().clear();
            }
        }

    }

}
