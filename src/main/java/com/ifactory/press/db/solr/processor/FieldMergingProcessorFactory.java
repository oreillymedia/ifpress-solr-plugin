package com.ifactory.press.db.solr.processor;

import java.util.HashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FieldMergingProcessorFactory extends UpdateRequestProcessorFactory implements SolrCoreAware {
    
    private static Logger log = LoggerFactory.getLogger(FieldMergingProcessorFactory.class);
    private String destinationField;
    private HashMap<String,Analyzer> sourceFieldAnalyzers;
    private IndexSchema schema;
    private NamedList<?> initArgs;

    @Override
    public void inform(SolrCore core) {
        schema = core.getLatestSchema();
        doInit();
    }
    
    @Override
    public void init (@SuppressWarnings("rawtypes") NamedList args) {
        initArgs = args;
    }
    
    private void doInit () {
        Object o = initArgs.get("destinationField");
        if (o == null || ! (o instanceof String)) {
            log.error("destinationField must be present as a string, got " + o);
            return;
        }
        destinationField = (String) o;
        o = initArgs.get("sourceField");
        if (o == null || ! (o instanceof NamedList)) {
            log.error("destinationField must be present as a list, got " + o);
            return;
        }
        NamedList<?> sourceFields = (NamedList<?>) o;
        if (sourceFields.size() == 0) {
            log.error("destinationField must not be empty");
        }
        sourceFieldAnalyzers = new HashMap<String, Analyzer>();
        for (int i = 0; i < sourceFields.size(); i++) {
            String sourceFieldName = sourceFields.getName(i);
            o = sourceFields.getVal(i);
            FieldType fieldType;
            if (o instanceof String && ! ((String) o).isEmpty()) {
                String analyzerTypeName = (String) o;
                fieldType = schema.getFieldTypeByName(analyzerTypeName);
                if (fieldType == null) {
                    log.error ("No such fieldType: " + analyzerTypeName);
                }
            } else {
                fieldType = schema.getFieldType(sourceFieldName);
                if (fieldType == null) {
                    log.error ("No such field " + sourceFieldName);
                }
            }
            if (fieldType != null) {
                sourceFieldAnalyzers.put(sourceFieldName, fieldType.getAnalyzer());
            }
        }

    }

    @Override
    public FieldMergingProcessor getInstance(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
        return new FieldMergingProcessor(destinationField, sourceFieldAnalyzers, next);
    }

}
