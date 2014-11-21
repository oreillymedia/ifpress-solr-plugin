package com.safari.bigram;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TermsIntersectionTest extends LuceneTestCase {
  
  private Directory dir;
  private DirectoryReader reader;
  private RandomIndexWriter iw;
  private String [] randomTokens;
  
  @Before
  public void setup () throws IOException {
    dir = newDirectory ();
    iw = new RandomIndexWriter( random(), dir,
        newIndexWriterConfig(new WhitespaceAnalyzer()).setMergePolicy(newLogMergePolicy()));
  }
  
  @After
  public void shutdown () throws IOException {
    if (reader != null) {
      reader.close();
    }
    iw.close();
    dir.close();
  }
  
  @Test
  public void testIntersect () throws Exception {
    HashMap<String,Integer> bigramFrequency = new HashMap<String, Integer>();
    insertTestDocuments (bigramFrequency);
    reader = DirectoryReader.open(iw.w, true);
    TermsIntersection intersect = TermsIntersection.create(reader, "text", "bigram");
    BytesRef term;
    int count = 0;
    for (;;) {
      term = intersect.next();
      if (term == null) {
        break;
      }
      String t = term.utf8ToString();
      // every bigram found should have been in our expected map
      // TODO: check frequencies??
      assertTrue ("found unexpected bigram: " + t, bigramFrequency.containsKey(t));
      // We counted the total number of occurrences of each bigram *as two words* in the text field, which become
      // single tokens in the bigram field
      long freq = intersect.totalTermFreq2();
      if (freq >= 0) {
        // it's possible the random index setup doesn't support getting totalTermFrequency
        assertEquals ("incorrect count for " + t, bigramFrequency.get(t).longValue(), freq);
      }
      ++ count;
    }
    // we should have found all the expected bigrams
    int numBigrams = 0;
    for (Entry<String, Integer> e : bigramFrequency.entrySet()) {
      if (e.getValue() > 0) {
        ++ numBigrams;
      }
    }
    assertEquals ("missed some bigrams", numBigrams, count);
  }

  private void insertTestDocuments (HashMap<String, Integer> bigramFrequency) throws IOException {
    int numRandomTokens = 5 + random().nextInt(5);
    randomTokens = new String[numRandomTokens * 2];
    for (int i = 0; i < numRandomTokens; i++) {
      do {
        randomTokens[i] = TestUtil.randomRealisticUnicodeString(random());
      } while (containsWhitespace(randomTokens[i]) || randomTokens[i].length() == 0);
      //randomTokens[i] = new String(Character.toChars(48 + i));
    }
    for (int i = numRandomTokens; i < randomTokens.length; i++) {
      // add some random compound tokens
      String bigram = randomTokens[random().nextInt(numRandomTokens)] + randomTokens[random().nextInt(numRandomTokens)];
      bigramFrequency.put(bigram, 0);
      randomTokens[i] = bigram;
    }
    int numDocs = random().nextInt(25);
    // int numDocs = 1;
    BigramAnalyzer bigramAnalyzer = new BigramAnalyzer();
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(new StringField ("id", "id"+i, Store.NO));
      StringBuilder buf = new StringBuilder();
      int numWords = random().nextInt(100);
      String lastWord = null;
      for (int j = 0; j < numWords; j++) {
        String word = randomTokens[random().nextInt(randomTokens.length)];
        buf.append(word).append(' ');
        if (lastWord != null) {
          String bigram = lastWord + word;
          Integer count = bigramFrequency.get(bigram);
          if (count != null) {
            bigramFrequency.put(bigram, count + 1);
          }
        }
        lastWord = word;
        // System.out.println(word);
      }
      String text = buf.toString();
      doc.add(new TextField("text", text, Store.YES));
      TokenStream bigrams = bigramAnalyzer.tokenStream("bigram", new StringReader(text));
      doc.add(new TextField("bigram", bigrams));
      iw.addDocument(doc);
    }
    iw.commit();
    bigramAnalyzer.close();
  }
  
  
  private boolean containsWhitespace(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  class BigramAnalyzer extends Analyzer {
    
    @Override
    protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
      WhitespaceTokenizer ws = new WhitespaceTokenizer(reader);
      ShingleFilter shingleFilter = new ShingleFilter(ws, 2, 2);
      shingleFilter.setOutputUnigrams(false);
      shingleFilter.setTokenSeparator("");
      return new TokenStreamComponents(ws, shingleFilter);
    }
  }
  
}
