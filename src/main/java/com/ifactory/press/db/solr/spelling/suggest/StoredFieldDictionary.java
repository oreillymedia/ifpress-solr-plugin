package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;

public class StoredFieldDictionary implements Dictionary {

    private final IndexReader reader;
    private final String fieldName;
    private final HashSet<String> fieldsToLoad;
    private final BytesRef bytes;
    private final Comparator<BytesRef> comparator;
    
    public StoredFieldDictionary (IndexReader reader, String fieldName) {
        this.reader = reader;
        this.fieldName = fieldName;
        fieldsToLoad = new HashSet<String>();
        fieldsToLoad.add(fieldName);
        bytes = new BytesRef();
        comparator = BytesRef.getUTF8SortedAsUnicodeComparator();
    }
    
    @Override
    public InputIterator getEntryIterator() throws IOException {
        return new StoredFieldIterator();
    }
    
    class StoredFieldIterator implements InputIterator {
        
        private int idoc = 0;

        @Override
        public BytesRef next() throws IOException {
            while (idoc < reader.maxDoc()) {
                // TODO: exclude deleted documents
                Document doc = reader.document(idoc ++, fieldsToLoad);
                String value = doc.get(fieldName);
                if (value != null) {
                    bytes.copyChars(value);
                    return bytes;
                }
            }
            return null;
        }

        @Override
        public Comparator<BytesRef> getComparator() {
            return comparator;
        }

        @Override
        public long weight() {
            return 1;
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
        
    }

}
