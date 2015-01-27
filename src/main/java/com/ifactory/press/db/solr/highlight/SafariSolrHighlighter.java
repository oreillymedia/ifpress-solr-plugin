package com.ifactory.press.db.solr.highlight;

import org.apache.lucene.search.postingshighlight.PassageFormatter;
import org.apache.lucene.search.postingshighlight.PassageScorer;
import org.apache.lucene.search.postingshighlight.PostingsHighlighter;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.highlight.PostingsSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;

public class SafariSolrHighlighter extends PostingsSolrHighlighter {

  /** Creates an instance of the Lucene PostingsHighlighter. Provided for subclass extension so that
   * a subclass can return a subclass of {@link PostingsSolrHighlighter.SolrExtendedPostingsHighlighter}. */
  @Override
  protected PostingsHighlighter getHighlighter(SolrQueryRequest req) {
    return new SafariPostingsHighlighter(req);
  }

  public class SafariPostingsHighlighter extends SolrExtendedPostingsHighlighter {

    public SafariPostingsHighlighter(SolrQueryRequest req) {
      super(req);
    }
        
    @Override
    protected PassageFormatter getFormatter(String fieldName) {
      String preTag = params.getFieldParam(fieldName, HighlightParams.TAG_PRE, "<em>");
      String postTag = params.getFieldParam(fieldName, HighlightParams.TAG_POST, "</em>");
      String ellipsis = params.getFieldParam(fieldName, HighlightParams.TAG_ELLIPSIS, "... ");
      String encoder = params.getFieldParam(fieldName, HighlightParams.ENCODER, "simple");
      return new HighlightFormatter(preTag, postTag, ellipsis, "html".equals(encoder));
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