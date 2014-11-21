package com.ifactory.press.db.solr.analysis;

import static org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.text.ParseException;
import java.util.regex.Pattern;

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
import org.apache.lucene.analysis.pattern.PatternReplaceFilter;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;

public class SafariAnalyzer extends Analyzer {
  
  private final boolean tokensOverlap;
  
  /**
   * @param tokensOverlap when true, include a SynonymFilter and preserveOriginal option to WordDelimiterFilter.
   */
  public SafariAnalyzer(boolean tokensOverlap) {
    this.tokensOverlap = tokensOverlap;
  }

  @Override
  protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
    CharFilter charFilter = new HTMLStripCharFilter(reader);
    Pattern pat1 = Pattern.compile("([A-Za-z])\\+\\+");
    charFilter = new PatternReplaceCharFilter(pat1, "$1plusplus", charFilter);
    charFilter = new PatternReplaceCharFilter(Pattern.compile("([A-Za-z])\\#"), "$1sharp", charFilter);
    Tokenizer tokenizer = new WhitespaceTokenizer(charFilter);
    // TODO protwords.txt
    int wdfOptions =GENERATE_WORD_PARTS |
        GENERATE_NUMBER_PARTS |
        SPLIT_ON_CASE_CHANGE |
        SPLIT_ON_NUMERICS |
        STEM_ENGLISH_POSSESSIVE|
        PRESERVE_ORIGINAL;
    if (tokensOverlap) {
      wdfOptions |= PRESERVE_ORIGINAL;
    }
    TokenFilter filter = new WordDelimiterFilter(tokenizer, wdfOptions, null);
    filter = new LowerCaseFilter(filter);
    filter = new PatternReplaceFilter(filter, Pattern.compile("\\P{Alnum}+"), "", true);
    if (tokensOverlap) {
      filter = new SynonymFilter(filter, buildSynonymMap(), true);
      filter = new RemoveDuplicatesTokenFilter(filter);
    }
    // TODO: HunspellStemFilter
    return new TokenStreamComponents(tokenizer, filter);
  }

  
  private SynonymMap buildSynonymMap() {
    SolrSynonymParser parser = new SolrSynonymParser(true, true, new SynonymAnalyzer());
    try {
      InputStream synonyms = getClass().getResourceAsStream("synonyms.txt");
      Reader synonymReader;
      if (synonyms == null) {
        synonymReader = new StringReader("parts=parts");
      } else {
        synonymReader = new InputStreamReader (synonyms);
      }
      parser.parse(synonymReader);
      return parser.build();
    } catch (ParseException e) {
      throw new RuntimeException ("failed to parse synonyms", e);
    } catch (IOException e) {
      throw new RuntimeException ("failed to read synonyms", e);
    }
  }

  class SynonymAnalyzer extends Analyzer {

    @Override
    protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
      Tokenizer tokenizer = new WhitespaceTokenizer(reader);
      TokenFilter filter = new LowerCaseFilter(tokenizer);
      return new TokenStreamComponents(tokenizer, filter);
    }
  }
  
}