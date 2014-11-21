package com.safari.bigram;

import java.io.IOException;
import java.util.Comparator;

import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.TermsEnum.SeekStatus;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;


public class TermsIntersection implements BytesRefIterator {
  
  public static TermsIntersection create (IndexReader reader, String field1, String field2) throws IOException {
    AtomicReader atomicReader = SlowCompositeReaderWrapper.wrap(reader);
    TermsEnum t1 = atomicReader.fields().terms(field1).iterator(null);
    TermsEnum t2 = atomicReader.fields().terms(field2).iterator(null);
    return new TermsIntersection(t1, t2);
  }
  
  private final TermsEnum terms1, terms2;
  private final Comparator<BytesRef> comparator;
  
  public TermsIntersection (TermsEnum t1, TermsEnum t2) throws IOException {
    terms1 = t1;
    terms2 = t2;
    if (! terms1.getComparator().equals(terms2.getComparator())) {
      throw new IllegalArgumentException ("TermsEnum arguments do not have the same comparator");
    }
    comparator = terms1.getComparator();
  }

  @Override
  public BytesRef next() throws IOException {
    // compare, advance, compare, and repeat until equal
    
    // either we're in the initial state, or they were equal, so advance both
    BytesRef bytes1 = terms1.next();
    BytesRef bytes2 = terms2.next();
    if (bytes1 == null || bytes2 == null) {
      return null;
    }
    int cmp = comparator.compare(bytes1,  bytes2);
    if (cmp == 0) {
      return bytes1;
    }
    for (;;) {
      SeekStatus status;
      if (cmp > 0) {
        // advance 2
        status = terms2.seekCeil(terms1.term());
      } else {
        // advance 1
        status = terms1.seekCeil(terms2.term());
      }
      if (status == SeekStatus.END) {
        return null;
      }
      if (status == SeekStatus.FOUND) {
        return terms1.term(); 
      }
      cmp = - cmp;
    }
  }

  @Override
  public Comparator<BytesRef> getComparator() {
    return comparator;
  }
  
  public int docFreq1() throws IOException {
    return terms1.docFreq();
  }
  
  public int docFreq2() throws IOException {
    return terms2.docFreq();
  }
  
  public long totalTermFreq1() throws IOException {
    return terms1.totalTermFreq();
  }
  
  public long totalTermFreq2() throws IOException {
    return terms2.totalTermFreq();
  }
  
}
