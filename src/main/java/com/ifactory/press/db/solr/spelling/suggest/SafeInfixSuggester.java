package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;
import org.apache.solr.search.EarlyTerminatingCollectorException;

public class SafeInfixSuggester extends AnalyzingInfixSuggester {

    public SafeInfixSuggester(Version matchVersion, Directory dir, Analyzer indexAnalyzer, Analyzer queryAnalyzer, int minPrefixChars) throws IOException {
        super(matchVersion, dir, indexAnalyzer, queryAnalyzer, minPrefixChars);
        
        if (!DirectoryReader.indexExists(dir)) {
            // no index in place -- build an empty one so we are prepared for updates

            build (new InputIterator() {

                @Override
                public BytesRef next() throws IOException {
                    return null;
                }

                @Override
                public Comparator<BytesRef> getComparator() {
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
                
            });
        }
    }
    
    public void update(BytesRef text, Set<BytesRef> contexts, long weight, long count) throws IOException {
        BytesRef bytes = new BytesRef (8);
        NumericUtils.longToPrefixCodedBytes(count, 0, bytes);
        super.update(text, contexts, weight, bytes);
    }
    
    /**
     * 
     * @param term
     * @return -1 if the fieldName:term is not found, or the associated "count" if it is, which is always >= 0 
     * @throws IOException
     */
    public long getCount (String term) throws IOException {
        IndexSearcher searcher = searcherMgr.acquire();
        try {
            TermQuery tq = new TermQuery(new Term (EXACT_TEXT_FIELD_NAME, term));
            FirstCollector collector = new FirstCollector();
            try {
                searcher.search(tq, collector);
            } catch (EarlyTerminatingCollectorException e) {}
            int docID = collector.getDocID();
            if (docID < 0) {
                return -1;
            }
            BinaryDocValues payloadsDV = MultiDocValues.getBinaryValues(searcher.getIndexReader(), "payloads");
            if (payloadsDV != null) {
                BytesRef payload = new BytesRef();
                payloadsDV.get(docID, payload);
                return NumericUtils.prefixCodedToLong(payload);
            }
            return -1;
        } finally {
            searcherMgr.release(searcher);
        }
    }
    
    /*
     * TODO: control highlighting here
    @Override
    public List<LookupResult> lookup(CharSequence key, Set<BytesRef> contexts, boolean onlyMorePopular, int num) throws IOException {
      return lookup(key, contexts, num, true, false);
    }
    */

}
