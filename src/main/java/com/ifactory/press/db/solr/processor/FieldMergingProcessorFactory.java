package com.ifactory.press.db.solr.processor;

import java.util.HashMap;

import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FieldMergingProcessorFactory extends UpdateRequestProcessorFactory implements SolrCoreAware {
    
    private static Logger log = LoggerFactory.getLogger(FieldMergingProcessorFactory.class);
    private String destinationField;
    private HashMap<String, SchemaField> sourceSchemaFields;
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
        FieldType destinationFieldType = schema.getFieldType(destinationField);
        if (destinationFieldType == null) {
            log.error("deistinationField is not defined in the schema: it has no schema type");
            return;
        }
        o = initArgs.get("sourceField");
        if (o == null || ! (o instanceof NamedList)) {
            log.error("sourceField must be present as a list, got " + o);
            return;
        }
        NamedList<?> sourceFields = (NamedList<?>) o;
        if (sourceFields.size() == 0) {
            log.error("destinationField must not be empty");
        }
        sourceSchemaFields= new HashMap<String, SchemaField>();
        for (int i = 0; i < sourceFields.size(); i++) {
            String sourceFieldName = sourceFields.getName(i);
            o = sourceFields.getVal(i);
            SchemaField fieldType;
            if (o instanceof String && ! ((String) o).isEmpty()) {
                String analysisFieldName = (String) o;
                fieldType = schema.getField(analysisFieldName);
                if (fieldType == null) {
                    log.error ("No such field: " + analysisFieldName);
                }
            } else {
                fieldType = schema.getField(sourceFieldName);
                if (fieldType == null) {
                    log.error ("No such field " + sourceFieldName);
                }
            }
            if (fieldType != null) {
                sourceSchemaFields.put(sourceFieldName, fieldType);
            }
        }

    }

    @Override
    public FieldMergingProcessor getInstance(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
        return new FieldMergingProcessor(destinationField, sourceSchemaFields, next);
    }

}
