package com.ifactory.press.db.solr.processor;

import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.plugin.SolrCoreAware;

public class UpdateDocValuesProcessorFactory extends UpdateRequestProcessorFactory implements SolrCoreAware {
    
  private SolrCore core;
  
  @Override
  public UpdateRequestProcessor getInstance(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
      return new UpdateDocValuesProcessor(core, next);
  }

  @Override
  public void inform(SolrCore aCore) {
    this.core = aCore;
  }


}
