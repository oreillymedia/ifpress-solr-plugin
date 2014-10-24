package com.ifactory.press.db.solr.processor;

import java.io.IOException;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Updates all docvalues field for the document.
 */
public class UpdateDocValuesProcessor extends UpdateRequestProcessor  {

  private final SolrCore core;
  
  private final static Logger LOG = LoggerFactory.getLogger(UpdateDocValuesProcessor.class);
  
  public UpdateDocValuesProcessor(SolrCore core, UpdateRequestProcessor next) throws SolrException {
    super(null);
    if (next != null) {
      throw new SolrException(ErrorCode.SERVER_ERROR, "Improperly configured update chain: UpdateDocValuesProcessor must be last");
    }
    this.core = core;
  }
  
  @Override
  public void processAdd(AddUpdateCommand cmd) throws IOException {
    RefCounted<IndexWriter> iwref = core.getSolrCoreState().getIndexWriter(core);
    try {
      IndexWriter iw = iwref.get();
      SolrParams params = cmd.getReq().getParams();
      String keyField = params.get("key.field");
      String valueField = params.get("value.field");
      if (keyField == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "missing required parameter key.field");
      }
      if (valueField == null) {
        throw new SolrException(ErrorCode.BAD_REQUEST, "missing required parameter value.field");
      }
      SolrInputDocument solrInputDocument = cmd.getSolrInputDocument();
      String key = getStringValue(solrInputDocument, keyField);
      Long value = getLongValue(solrInputDocument, valueField);
      Term term = new Term(keyField, key);
      // LOG.debug(String.format("update docvalues %s %s=%d", term, valueField, value));
      iw.updateNumericDocValue(term, valueField, value);
    } finally {
      iwref.decref();
    }
  }
  
  private String getStringValue (SolrInputDocument solrInputDocument, String keyField) {
    Object o = solrInputDocument.getFieldValue(keyField);
    if (o == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "no value for DocValues key field " + keyField);
    }
    if (! (o instanceof String)) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Value of DocValue key field " + keyField + " must be a string");
    }
    return (String) o;
  }
  
  private long getLongValue(SolrInputDocument solrInputDocument, String valueField) {
    Object o = solrInputDocument.getFieldValue(valueField);
    if (o == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "no value for DocValues value field " + valueField);
    }
    try {
      return Long.parseLong(o.toString());
    } catch (NumberFormatException e) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Value of DocValue valuefield " + valueField + " must be an integer, not " + o);
    }
  }
  
}
