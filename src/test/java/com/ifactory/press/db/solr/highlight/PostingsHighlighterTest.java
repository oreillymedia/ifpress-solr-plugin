package com.ifactory.press.db.solr.highlight;

import static org.junit.Assert.*;
import static org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.ParseException;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.postingshighlight.PostingsHighlighter;
import org.apache.lucene.store.RAMDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PostingsHighlighterTest {

  private IndexWriter iw;
  
  @Before
  public void startup() throws IOException {
    RAMDirectory dir = new RAMDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new SafariAnalyzer(true));
    iw = new IndexWriter(dir, iwc);
  }
  
  @After
  public void cleanup() throws IOException {
    iw.close();
  }
  
  @Test
  public void testHighlightChapter5() throws IOException {
    // searching for "gas" didn't work on the Safari site
    
    InputStream ch5stream = getClass().getResourceAsStream("ch5.txt");
    String ch5 = IOUtils.toString(ch5stream);

    // add a single document to the index
    // configure field with offsets at index time
    FieldType offsetsType = new FieldType(TextField.TYPE_STORED);
    offsetsType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    Field text = new Field("text", ch5, offsetsType);

    Document doc = new Document();
    doc.add(new StringField("id", "ch5", Store.YES));
    doc.add(text);
    iw.addDocument(doc);
    iw.commit();
    
    DirectoryReader reader = DirectoryReader.open(iw, true);
    IndexSearcher searcher = new IndexSearcher(reader);

    // retrieve highlights at query time 
    PostingsHighlighter highlighter = new PostingsHighlighter(100000);
    Query query = new TermQuery(new Term("text", "gas"));
    TopDocs topDocs = searcher.search(query, 1);
    String highlights[] = highlighter.highlight("text", query, searcher, topDocs);
    assertEquals (1, highlights.length);
    assertNotNull ("PH returns null highlight", highlights[0]);
    assertTrue (highlights[0] + " \n does not contain <b>gas</b>", highlights[0].contains("<b>gas</b>"));
  }
  
  class SynonymAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
      Tokenizer tokenizer = new WhitespaceTokenizer();
      TokenFilter filter = new LowerCaseFilter(tokenizer);
      return new TokenStreamComponents(tokenizer, filter);
    }
  }
  
  class SafariAnalyzer extends Analyzer {
    
    private final boolean isIndexAnalyzer;
    
    public SafariAnalyzer(boolean isIndexAnalyzer) {
      this.isIndexAnalyzer = isIndexAnalyzer;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
      Tokenizer tokenizer = new WhitespaceTokenizer();
      // TODO protwords.txt
      TokenFilter filter = new WordDelimiterFilter(tokenizer, 
          GENERATE_WORD_PARTS |
          GENERATE_NUMBER_PARTS |
          SPLIT_ON_CASE_CHANGE |
          SPLIT_ON_NUMERICS |
          STEM_ENGLISH_POSSESSIVE|
          PRESERVE_ORIGINAL, 
          null);
      filter = new LowerCaseFilter(filter);
      if (isIndexAnalyzer) {
        filter = new SynonymFilter(filter, buildSynonymMap(), true);
      }
      // TODO: HunspellStemFilter
      filter = new RemoveDuplicatesTokenFilter(filter);
      return new TokenStreamComponents(tokenizer, filter);
    }

    
    private SynonymMap buildSynonymMap() {
      SolrSynonymParser parser = new SolrSynonymParser(true, true, new SynonymAnalyzer());
      try {
        parser.parse(new InputStreamReader (getClass().getResourceAsStream("synonyms.txt")));
        return parser.build();
      } catch (ParseException e) {
        throw new RuntimeException ("failed to parse synonyms", e);
      } catch (IOException e) {
        throw new RuntimeException ("failed to read synonyms", e);
      }
    }
  }

}
