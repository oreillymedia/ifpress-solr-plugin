package com.ifactory.press.db.solr.highlight;

import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;

import java.util.Arrays;

public class SafariSolrHighlighter extends UnifiedSolrHighlighter {

  public static final String PRESERVED_FIELDS = "hl.preservedFields";

  /** Creates an instance of the Lucene PostingsHighlighter. Provided for subclass extension so that
   * a subclass can return a subclass of {@link UnifiedSolrHighlighter.SolrExtendedUnifiedHighlighter}. */
  @Override
  protected UnifiedHighlighter getHighlighter(SolrQueryRequest req) {
    return new SafariUnifiedHighlighter(req);
  }

  public class SafariUnifiedHighlighter extends SolrExtendedUnifiedHighlighter {

    public SafariUnifiedHighlighter(SolrQueryRequest req) {
      super(req);
    }
        
    @Override
    protected PassageFormatter getFormatter(String fieldName) {
      // Try to get highlight configs, setting defaults if configs do not exist.
      String preTag = params.getFieldParam(fieldName, HighlightParams.TAG_PRE, "<em>");
      String postTag = params.getFieldParam(fieldName, HighlightParams.TAG_POST, "</em>");
      String ellipsis = params.getFieldParam(fieldName, HighlightParams.TAG_ELLIPSIS, "... ");
      String encoder = params.getFieldParam(fieldName, HighlightParams.ENCODER, "simple");
      // Load PRESERVED_FIELDS, allowing the config to specify all fields in one param
      String[] fieldsToPreserve = params.getFieldParam(fieldName, PRESERVED_FIELDS, "").split("\\s+");
      boolean shouldPreserveField = Arrays.asList(fieldsToPreserve).contains(fieldName);
      return new HighlightFormatter(preTag, postTag, ellipsis, "html".equals(encoder), shouldPreserveField);
    }

    @Override
    protected PassageScorer getScorer(String fieldName) {
      float k1 = params.getFieldFloat(fieldName, HighlightParams.SCORE_K1, 1.2f);
      float b = params.getFieldFloat(fieldName, HighlightParams.SCORE_B, 0.75f);
      float pivot = params.getFieldFloat(fieldName, HighlightParams.SCORE_PIVOT, 87f);
      return new PassageScorer(k1, b, pivot);
    }

  }

}