package com.safari.bigram;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

//import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.highlight.Formatter;
import org.apache.lucene.search.highlight.TokenGroup;
import org.apache.lucene.search.postingshighlight.Passage;
import org.apache.lucene.search.postingshighlight.PostingsHighlighter;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.Version;

import com.ifactory.press.db.solr.analysis.SafariAnalyzer;

/**
 * Finds compound words in a text by comparing each word with each pair of adjacent words.
 */
public class Compounder {
  
  private static final String COMPOUND_FIELD_NAME = "compound";
  private static final String TEXT_FIELD_NAME = "text";
  private static final BytesRefBuilder buffer = new BytesRefBuilder ();
  private TermsEnum termsEnum;

  // static Logger log = Logger.getLogger(Compounder.class);
  
  public static void main (String[] argv) throws IOException {
    //System.out.println ("working directory=" + System.getProperty("user.dir"));
    Compounder compounder = new Compounder();
    if (argv.length > 0 && argv[0].equals ("--index")) {
      compounder.indexDocuments ();
    }
    compounder.dumpCompounds();
  }
  
  private void dumpCompounds () throws IOException {
    MMapDirectory dir = new MMapDirectory(new File("index"));
    DirectoryReader reader = DirectoryReader.open(dir);
    IndexSearcher searcher = new IndexSearcher(reader);
    TermsIntersection matcher = TermsIntersection.create(reader, TEXT_FIELD_NAME, COMPOUND_FIELD_NAME);
    termsEnum = createTermsEnum(reader);
    BytesRef bytes = matcher.next(); 
    ArrayList<Compound> compounds = new ArrayList<Compound>();
    while (bytes != null) {
      Gram joined = new Gram (bytes.utf8ToString(), matcher.docFreq1(), matcher.totalTermFreq1());
      Gram bigram = new Gram (bytes.utf8ToString(), matcher.docFreq2(), matcher.totalTermFreq2());
      Compound compound = decompound(searcher, bytes, bigram, joined);
      if (compound != null) {
        compounds.add(compound);
      }
      bytes = matcher.next(); 
    }
    Compound[] c = compounds.toArray(new Compound[0]);
    Arrays.sort(c);
    for (Compound compound : c) {
      System.out.println(compound);
    }
  }
  
  private TermsEnum createTermsEnum(DirectoryReader reader) throws IOException {
    return SlowCompositeReaderWrapper.wrap(reader).fields().terms(TEXT_FIELD_NAME).iterator(null);
  }

  // Use a highlighter to extract the bigram, split the bigram into its two parts, get their frequencies
  // and compare to the frequency of the compound ... we're looking for bigrams having compoundfreq*bigramfreq/(part1freq * part2freq)
  // above some threshold.
  //
  // How do we know that showboat is a compound but alive, across, Justin are not?  Frequency may not be sufficient?
  // Etymology really matters here, but we can probably discard compounds made fmor very short words, mostly. In the
  // end, we can probably just review the list manually, too.
  private Compound decompound (IndexSearcher searcher, BytesRef bytes, Gram bigram, Gram compound) throws IOException {
    PostingsHighlighter highlighter = new CompoundHighlighter(250000);
    TermQuery compoundQuery = new TermQuery(new Term(COMPOUND_FIELD_NAME, bytes));
    Query query = compoundQuery;
    TopDocs topDocs = searcher.search(query, 3);
    String highlights[] = highlighter.highlight(COMPOUND_FIELD_NAME, query, searcher, topDocs, 3);
    String match = null;
    for (String hl : highlights) {
      if (hl != null) {
        // System.out.println ("  " + hl.replace('\n', ' '));
        int ihl = hl.indexOf("<b>") + 3;
        match = hl.substring(ihl, hl.indexOf("</b>", ihl));
      }
    }
    if (match == null) {
      System.err.println ("no match for " + query);
      return null;
    }
    String[] unigrams = match.toLowerCase().split("\\s+");
    if (unigrams.length != 2 || !compound.text.equals(unigrams[0] + unigrams[1])) {
      // We don't want to record these as compounds because WordDelimiterFilter already handles them.
      // System.err.println ("mismatch " + compound.text + " != " + StringUtils.join(unigrams, '|'));
      return null;
    }
    //assert unigrams.length == 2;
    //assert compound.text.equals(unigrams[0] + unigrams[1]);
    return new Compound (new Gram (unigrams[0]), new Gram(unigrams[1]), bigram, compound);
  }

  private void indexDocuments () throws IOException {
    MMapDirectory dir = new MMapDirectory(new File("index"));
    Analyzer analyzer = getAnalyzer();
    IndexWriterConfig conf = new IndexWriterConfig(Version.LATEST, analyzer);
    conf.setOpenMode(OpenMode.CREATE);
    IndexWriter writer = new IndexWriter(dir, conf);
    try {
      int count = retrieveAndWriteDocs(writer);
      System.out.println (String.format("%d documents indexed", count));
    } finally {
      analyzer.close();
      writer.close();
      dir.close();
    }
  }
  
  public int retrieveAndWriteDocs (IndexWriter writer) throws IOException {
    int count = 0;
    FieldType offsetsTextType = new FieldType(TextField.TYPE_STORED);
    offsetsTextType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    offsetsTextType.freeze();
    //SafariDocRetriever docs = new SafariDocRetriever("http://hull:8983/solr/heron1", "django_ct:nest.epubarchive", "id", "text");
    SafariDocRetriever docs = new SafariDocRetriever("http://hull:8983/solr/heron1", "django_ct:(nest.epubarchive nest.htmlfile)", "id", "text");
    //SafariDocRetriever docs = new SafariDocRetriever("http://solr-01.sfo.safariflow.com/solr/collection1", "django_ct:(nest.epubarchive nest.htmlfile)", "id", "text");
    for (String text : docs) {
      if (text == null) {
        // should only occur at the end, as the threads are shutting down
        continue;
      }
      Document doc = new Document();
      doc.add(new TextField(TEXT_FIELD_NAME, text, Store.NO));
      Field compoundField = new Field(COMPOUND_FIELD_NAME, text, offsetsTextType);
      compoundField.setStringValue(text);
      doc.add(compoundField);
      writer.addDocument(doc);
      ++count;
    }
    return count;
  }
  
  private Analyzer getAnalyzer () {
    HashMap<String,Analyzer> analmap = new HashMap<String, Analyzer>();
    analmap.put(COMPOUND_FIELD_NAME, new BigramAnalyzer());
    return new PerFieldAnalyzerWrapper(new SafariAnalyzer(false), analmap);
  }
  
  /** This Analyzer tokenizes on whitespace and generates compound tokens from adjacent token pairs. */ 
  class BigramAnalyzer extends SafariAnalyzer {
    
    public BigramAnalyzer() {
      super(false);
    }
    
    @Override
    protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
      TokenStreamComponents tsc = super.createComponents(fieldName, reader);
      ShingleFilter shingleFilter = new ShingleFilter(tsc.getTokenStream(), 2, 2);
      shingleFilter.setOutputUnigrams(false);
      shingleFilter.setTokenSeparator("");
      return new TokenStreamComponents(tsc.getTokenizer(), shingleFilter);
    }
  }
  
  /** This postings highlighter does not generate a default passage when no highlight is found. */
  class CompoundHighlighter extends PostingsHighlighter {
    
    public CompoundHighlighter(int charsToAnalyze) {
      super (charsToAnalyze);
    }

    protected Passage[] getEmptyHighlight(String fieldName, BreakIterator bi, int maxPassages) {
      return new Passage[0];
    }
  
  }
  
  /** This formatter simply extracts the text matching the query and records it.  The caller
   * may inspect the stored extract and is free to discard the surrounding context. */
  class ExtractingFormatter implements Formatter {

    String extract;
    
    @Override
    public String highlightTerm(String originalText, TokenGroup tokenGroup) {
      if (tokenGroup.getTotalScore() > 0) {
        extract = originalText;
      }
      return originalText;
    }
    
  }
  
  /** 
   * a word (unigram), bigram, or compound with its total frequency and its document frequency (in how many documents it occurs) 
   */
  class Gram {
    
    final String text;
    final long freq;
    final int docFreq;
    Gram (String text) throws IOException {
      this.text = text;
      buffer.copyChars(text);
      if (!termsEnum.seekExact(buffer.get())) {
        freq = 0;
        docFreq = 0;
      } else {
        freq = termsEnum.totalTermFreq();
        docFreq = termsEnum.docFreq();
      }
    }

    Gram (String text, int docFreq, long freq) {
      this.text = text;
      this.docFreq = docFreq;
      this.freq = freq;
    }
    
  }

  /**
   * represents a possible compound by its consitutent grams and their frequencies of occurrence
   */
  class Compound implements Comparable<Compound> {
    
    final Gram g1, g2, bigram, compound;
    
    Compound (Gram g1, Gram g2, Gram bigram, Gram compound) {
      this.g1 = g1;
      this.g2 = g2;
      this.bigram = bigram;
      this.compound = compound;
      assert bigram.freq <= g1.freq;
      assert bigram.freq <= g2.freq;
    }
    
    double score () {
      // no need to normalize compound.freq by totalTermFreq since all scores are based on the same corpus
      // bigram^2/(g1 * g2) represents the stickiness of the bigram - how often the two words occur in combination
      // relative to their frequencies overall; this will discount 
      return compound.freq * bigram.freq / (double) (g1.freq * g2.freq);
    }
    
    public String toString () {
      return String.format("%5.5f\t%s\t%d\t%d\t%s\t%d\t%s\t%d", score(), compound.text, compound.freq, bigram.freq, g1.text, g1.freq, g2.text, g2.freq);
    }

    @Override
    public int compareTo(Compound o) {
      int s = (int) Math.signum(score() - o.score());
      if (s == 0) {
        return compound.text.compareTo(o.compound.text);
      }
      return s;
    }
    
  }

}
