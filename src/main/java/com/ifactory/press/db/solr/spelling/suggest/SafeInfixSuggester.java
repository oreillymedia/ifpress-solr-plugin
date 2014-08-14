package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

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
                    // TODO Auto-generated method stub
                    return null;
                }

                @Override
                public boolean hasContexts() {
                    // TODO Auto-generated method stub
                    return false;
                }
                
            });
        }
    }

}
