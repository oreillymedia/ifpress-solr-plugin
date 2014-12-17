package com.ifactory.press.db.solr.search;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParserPlugin;

/**
 * Creates a parser based on the edismax parser (extended extended dismax?) with Safari customizations.
 * See Wiki page http://wiki.apache.org/solr/ExtendedDisMax
 */
public class SafariQueryParserPlugin extends QParserPlugin {
  
  public static final String NAME = "safari";
  
  @Override
  public void init(@SuppressWarnings("rawtypes") NamedList args) {
  }

  @Override
  public SafariQueryParser createParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new SafariQueryParser(qstr, localParams, params, req);
  }
  
}
