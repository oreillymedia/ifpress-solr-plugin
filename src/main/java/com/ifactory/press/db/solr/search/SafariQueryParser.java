package com.ifactory.press.db.solr.search;

import java.util.Map;

import org.apache.lucene.search.Query;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.ExtendedDismaxQParser;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.util.SolrPluginUtils;

/**
 * Adds to the edismax parser the ability to specify a distinct set of fields to
 * be used as the target fields for user-specified phrases (query clauses
 * wrapped in double quotes).
 */
public class SafariQueryParser extends ExtendedDismaxQParser {

    public static final String PQF = "pqf";

    // NOTE: must be the same as the IFN that ExtendedDismaxQParser uses
    private static final String IMPOSSIBLE_FIELD_NAME = "\uFFFC\uFFFC\uFFFC";
    private static final String IMPOSSIBLE_PHRASE_FIELD_NAME = IMPOSSIBLE_FIELD_NAME + "-p";

    private Map<String, Float> phraseFields;

    public SafariQueryParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
        super(qstr, localParams, params, req); 
        SolrParams solrParams = SolrParams.wrapDefaults(localParams, params);
        phraseFields = SolrPluginUtils.parseFieldBoosts(solrParams.getParams(PQF));
    }

    /**
     * Creates a SafariSolrQueryParser, attaching any configured phrase fields
     * as aliases for the fake default field over which all phrases range
     */
    @Override
    protected SafariSolrQueryParser createEdismaxQueryParser(QParser qParser, String field) {
        SafariSolrQueryParser qp = new SafariSolrQueryParser(qParser, field);
        if (phraseFields != null && !phraseFields.isEmpty()) {
            qp.addAlias(IMPOSSIBLE_PHRASE_FIELD_NAME, 0.0f, phraseFields);
        }
        return qp;
    }

    static public class SafariSolrQueryParser extends ExtendedSolrQueryParser {

        public SafariSolrQueryParser(QParser parser, String defaultField) {
            super(parser, defaultField);
        }

        @Override
        protected Query getFieldQuery(String field, String val, int slop) throws SyntaxError {
            if (IMPOSSIBLE_FIELD_NAME.equals(field)) {
                if (getAlias(IMPOSSIBLE_PHRASE_FIELD_NAME) != null) {
                    // Use phrase fields (value of QPF parameter) when present and no explicit field was specified
                    return super.getFieldQuery(IMPOSSIBLE_PHRASE_FIELD_NAME, val, slop);
                }
            }
            return super.getFieldQuery(field, val, slop);
        }

    }

}
