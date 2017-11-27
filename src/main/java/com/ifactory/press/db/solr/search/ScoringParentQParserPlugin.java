package com.ifactory.press.db.solr.search;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.join.BlockJoinParentQParserPlugin;

public class ScoringParentQParserPlugin extends BlockJoinParentQParserPlugin {

    @Override
    protected QParser createBJQParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
        return new ScoringParentQParser(qstr, localParams, params, req);
    }

}