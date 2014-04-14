package com.ifactory.press.db.solr.processor;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FieldMergingProcessor is a Solr UpdateRequestProcessor that merges 
 * several fields into one field.  It provides a similar function as the
 * built-in copyFields directive but also allows for a different Analyzer
 * to be used with each source field.
 * 
 * It must be configured in solrconfig.xml by defining an updateRequestProcessorChain
 * that includes the FieldMergingProcessorFactory and its configuration, and
 * associates that chain with the update handlers. Each listed source Field names 
 * may be associated with a Solr schema fieldType (keyword in the example below).
 * When the fieldType is present (the source field &lt;str> configuration element is not 
 * empty), then the index-time analyzer of the given fieldType is used to analyze the
 * source field text when it is merged into the destination field.  Otherwise the
 * analyzer configured for the source field itself is used.
 * 
 * <pre>
 *   &lt;updateRequestProcessorChain name="my-update-chain" default="true">
 *     &lt;processor class="com.ifactory.press.db.solr.processor.FieldMergingProcessorFactory">
 *       &lt;str name="destinationField">catchall&lt;/str>
 *       &lt;lst name="sourceFields">
 *         &lt;str name="title">keyword&lt;/str>
 *         &lt;str name="text" />
 *       &lt;/lst>
 *     &lt;/processor>
 *     &lt;processor class="solr.LogUpdateProcessorFactory" />
 *     &lt;processor class="solr.RunUpdateProcessorFactory" />
 *   &lt;/updateRequestProcessorChain>
 *   
 *   &lt;requestHandler name="/update" class="solr.UpdateRequestHandler">
 *     &lt;lst name="defaults">
 *       &lt;str name="update.chain">my-update-chain&lt;/str>
 *     &lt;/lst>
 *   &lt;/requestHandler>
 * </pre>
 */
public class FieldMergingProcessor extends UpdateRequestProcessor {
    
    // private static Logger log = LoggerFactory.getLogger(FieldMergingProcessorFactory.class);
    
    private final String destinationField;
    private final HashMap<String,SchemaField> sourceFields;
    
    public FieldMergingProcessor(String destinationField, HashMap<String, SchemaField> sourceFields, UpdateRequestProcessor next) {
        super(next);
        this.destinationField = destinationField;
        this.sourceFields = sourceFields;
    }
    
    public void processAdd(AddUpdateCommand cmd) throws IOException {
        if (sourceFields != null && destinationField != null) {
            SolrInputDocument doc = cmd.getSolrInputDocument();
            for (Map.Entry<String, SchemaField> entry : sourceFields.entrySet()) {
                String sourceFieldName = entry.getKey();
                SchemaField schemaField = entry.getValue();
                Collection<Object> fieldValues = doc.getFieldValues(sourceFieldName);
                if (fieldValues != null) {
                    for (Object value : fieldValues) {
                        // TODO: create an Analyzer that caches its TokenStream and then resets it when tokenStream is called???
                        IndexableField fieldValue = new TextField (destinationField, analyzerWrapper.tokenStream(sourceFieldName, value.toString()));
                        doc.addField(destinationField, fieldValue);
                    }
                }
            }
        }
        if (next != null) next.processAdd(cmd);
    }
    
}
