package com.ifactory.press.db.solr.spelling.suggest;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.assertEquals;

public class SafariInfixSuggesterTest {

  private SafariInfixSuggester suggester;

  @Before
  public void startup() throws Exception {
    // Initialize SafariInfixSuggester with empty suggestions
    WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
    Directory dir = new RAMDirectory();
    String[] excludedContexts = {"collection", "certification"};
    suggester = new SafariInfixSuggester(dir, analyzer, analyzer, 0, true, Arrays.asList(excludedContexts));
  }

  private void addSuggestion(String text, String[] contexts, int weight, BytesRef payload) throws IOException {
    Set<BytesRef> contextSet = new HashSet<>();
    if(contexts != null) {
      for (String context : contexts) {
        contextSet.add(new BytesRef(context));
      }
    }
    suggester.add(new BytesRef(text), contextSet, weight, payload);
  }

  private int getSuggestionCount() {
    return suggester.getSuggestWeightMap().size();
  }

  private Long getSuggestionWeight(String suggestText) {
    Suggestion suggestion = new Suggestion(suggestText, null, 0, null);
    return suggester.getSuggestWeightMap().get(suggestion);
  }

  @Test
  public void testAddBasicSuggest() throws IOException {
    // Adding a basic suggestion without additional data
    addSuggestion("python", null, 0, null);
    assertEquals(1, getSuggestionCount());
  }

  @Test
  public void testAddMultipleSuggestions() throws IOException {
    addSuggestion("python", null, 0, null);
    addSuggestion("java", null, 0, null);
    addSuggestion("c++", null, 0, null);
    assertEquals(3, getSuggestionCount());
  }

  @Test
  public void testAddSuggestionsWithContext() throws IOException {
    // Adding basic suggestions with multiple contexts
    addSuggestion("python", new String[]{"book", "en"}, 0, null);
    addSuggestion("java video", new String[]{"video", "en"}, 0, null);
    assertEquals(2, getSuggestionCount());
  }

  @Test
  public void testAddSuggestionsWithPayload() throws IOException {
    // Adding basic suggestions with payloads
    addSuggestion("python", new String[]{"book", "en"}, 0, new BytesRef("book"));
    addSuggestion("java video", new String[]{"video", "en"}, 0, new BytesRef("video"));
    addSuggestion("c++", new String[]{"video", "en"}, 0, new BytesRef("video"));
    assertEquals(3, getSuggestionCount());
  }

  @Test
  public void testAddDuplicateSuggestions() throws IOException {
    // Suggest HashMap should filter out duplicates
    addSuggestion("python", null, 0, null);
    addSuggestion("java", null, 0, null);
    addSuggestion("python", null, 0, null);
    assertEquals(2, getSuggestionCount());
  }

  @Test
  public void testAddDupeSuggestionsDifferentFormatContext() throws IOException {
    // Suggest HashMap should include duplicate suggest text if format contexts are different
    addSuggestion("python", new String[]{"book"}, 10, null);
    addSuggestion("python", new String[]{"video"}, 50, null);
    addSuggestion("python", new String[]{"learning path"}, 100, null);
    assertEquals(3, getSuggestionCount());
  }

  @Test
  public void testAddDupeSuggestionsDifferentContext() throws IOException {
    // Suggest HashMap should include duplicate suggest text if multiple context fields are different
    addSuggestion("python", new String[]{"book", "en"}, 0, null);
    addSuggestion("java video", new String[]{"video", "en"}, 0, null);
    addSuggestion("python", new String[]{"video", "en"}, 0, null);
    assertEquals(3, getSuggestionCount());
  }

  @Test
  public void testAddDupeSuggestionsDifferentPayload() throws IOException {
    // Payloads are strictly to transfer data, should not be used when deciding if suggestions are duplicates
    addSuggestion("python", new String[]{"book", "en"}, 0, new BytesRef("payload"));
    addSuggestion("python", new String[]{"book", "en"}, 0, new BytesRef("test"));
    assertEquals(1, getSuggestionCount());
  }

  @Test
  public void testDuplicateContextsDifferentText() throws IOException {
    // Two suggestions with the same context but different text should not considered duplicates
    addSuggestion("python", new String[]{"book", "en"}, 0, null);
    addSuggestion("java", new String[]{"book", "en"}, 0, null);
    assertEquals(2, getSuggestionCount());
  }

  @Test
  public void testDuplicateSuggestionKeepsHighestWeight() throws IOException {
    // When de-duping suggestions, suggestion should always keep the highest weight
    addSuggestion("python", null, 10, null);
    addSuggestion("python", null, 100, null);
    addSuggestion("python", null, 50, null);
    assertEquals(1, getSuggestionCount());
    assertEquals(Long.valueOf(100), getSuggestionWeight("python"));
  }

  @Test
  public void testContextWithHyphen() throws IOException {
    // Hyphens should persist and be treated as normal text when used in suggest contexts
    addSuggestion("python", new String[]{"book", "en-us"}, 0, null);
    addSuggestion("java video", new String[]{"video", "en"}, 0, null);
    addSuggestion("python", new String[]{"video", "en"}, 0, null);
    addSuggestion("python", new String[]{"video", "en-gb"}, 0, null);
    addSuggestion("python", new String[]{"book", "en-us"}, 0, null);
    assertEquals(4, getSuggestionCount());
  }

  @Test
  public void testExcludedContextsNotBuilt() throws IOException {
    // Suggestions with excluded contexts should not be included in the build, regardless of suggest text
    addSuggestion("python", new String[]{"book", "en-us"}, 0, null);
    addSuggestion("java", new String[]{"video", "en"}, 0, null);
    addSuggestion("python", new String[]{"collection", "en"}, 0, null);
    addSuggestion("c++", new String[]{"certification", "en-gb"}, 0, null);
    addSuggestion("python", new String[]{"collection"}, 0, null);
    assertEquals(2, getSuggestionCount());
  }
}
