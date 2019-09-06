package com.ifactory.press.db.solr.spelling.suggest;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.FieldValueQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/*
 * A wrapper class for filtering docs used for suggestions on a general level specific to each suggestion index.
 * Instead of applying specific filter queries, this class applies Lucene Queries to the given SolrIndexSearcher
 * to determine which DocSet is most appropriate for each suggestion index, to avoid scanning all docs on suggest build
 * when easily possible. If no specific case is matched, a DocSet consisting of all docs is used.
 * Note: The business logic in this class is specific to O'Reilly Media's use case.
 */
public class OReillySuggestionDocSetFilter {
  private List<MultiSuggester.WeightedField> suggestFields;
  private String suggestFieldName;
  private SolrIndexSearcher searcher;
  private Query filteredDocSetQuery;
  private String EXCLUDE_FILTERING_FIELD = "title"; // The only single field that will be excluded from filtering

  private static final Logger LOG = LoggerFactory.getLogger(OReillySuggestionDocSetFilter.class);

  public OReillySuggestionDocSetFilter(List<MultiSuggester.WeightedField> suggestFields, SolrIndexSearcher searcher) {
    this.suggestFields = suggestFields;
    this.searcher = searcher;
    this.filteredDocSetQuery = new MatchAllDocsQuery();  // By default, use all docs
    if (isFilteredSuggestField()) {
      suggestFieldName = suggestFields.get(0).fieldName;
    }
  }

  public List<MultiSuggester.WeightedField> getSuggestFields() {
    return suggestFields;
  }

  public void setSuggestFields(List<MultiSuggester.WeightedField> suggestFields) {
    this.suggestFields = suggestFields;
  }

  public SolrIndexSearcher getSearcher() {
    return searcher;
  }

  public void setSearcher(SolrIndexSearcher searcher) {
    this.searcher = searcher;
  }

  public String getSuggestFieldName() {
    return suggestFieldName;
  }

  public void setSuggestFieldName(String suggestFieldName) {
    this.suggestFieldName = suggestFieldName;
  }

  public DocSet getFilteredDocSet() throws IOException {
    setAppropriateFilterDocSetQuery();
    return searcher.getDocSet(filteredDocSetQuery);
  }

  public boolean isFilteredSuggestField() {
    return suggestFields != null
        && suggestFields.size() == 1
        && !suggestFields.get(0).fieldName.equals(EXCLUDE_FILTERING_FIELD);
  }

  private void setAppropriateFilterDocSetQuery() {
    if (isFilteredSuggestField()) {
      LOG.info(String.format("Retrieving filtered DocSet for field: %s", suggestFieldName));
      switch (suggestFieldName) {
        case "authors":
          filteredDocSetQuery = new TermQuery(new Term("content_type", "author"));
          suggestFieldName = "id";    // Author names stored in 'id' in author index
          break;
        case "publishers":
          filteredDocSetQuery = new TermQuery(new Term("content_type", "publisher"));
          suggestFieldName = "id";    // Publisher names stored in 'id' in publisher index
          break;
        case "isbn":
          filteredDocSetQuery = new FieldValueQuery("isbn");  // Filters any docs without isbn field
          break;
        default:
          LOG.info(String.format("No case matched for fieldName: %s. Using all docs.", suggestFieldName));
          filteredDocSetQuery = new MatchAllDocsQuery();  // Default to matching all docs
          break;
      }
    }
  }
}
