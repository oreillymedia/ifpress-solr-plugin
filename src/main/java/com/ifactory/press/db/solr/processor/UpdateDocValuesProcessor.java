package com.ifactory.press.db.solr.processor;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles docvalues updates. There are three modes of operation: update
 * docvalues, preserve docvalues, and update all.
 *
 * Query parameter <code>updatedv.value.field</code> specifies the docvalues
 * fields to update or preserve.
 *
 * If parameter <code>updatedv.key.field</code> is present, the processor
 * updates docvalues of documents whose key field matches the value provided for
 * that field in the update message.
 *
 * Otherwise it updates documents' stored and indexed fields in the usual way,
 * and also stores the docvalues fields. The values stored in the docvalues
 * fields are retrieved from the existing docvalues field unless provided in the
 * input document, in which the input value is stored. If no value is found in
 * the input and the document does not exist (or has no docvalues field value),
 * a value of 0 is stored.
 *
 * Note that the key field match is performed using a TermQuery, so the provided
 * key must match an indexed term exactly. For this reason, it's recommended to
 * use this feature with unanalyzed identifier-style fields.
 */
public class UpdateDocValuesProcessor extends UpdateRequestProcessor {

    public static final String UPDATEDV_VALUE_FIELD = "updatedv.value.field";
    public static final String UPDATEDV_KEY_FIELD = "updatedv.key.field";

    private final SolrCore core;

    private final String idField;

    private final static Logger LOG = LoggerFactory.getLogger(UpdateDocValuesProcessor.class);

    public UpdateDocValuesProcessor(String idField, SolrCore core, UpdateRequestProcessor next) throws SolrException {
        super(next);
        this.core = core;
        this.idField = idField;
    }

    @Override
    public void processAdd(AddUpdateCommand cmd) throws IOException {
        SolrParams params = cmd.getReq().getParams();
        String keyField = params.get(UPDATEDV_KEY_FIELD);
        String[] valueFields = params.getParams(UPDATEDV_VALUE_FIELD);
        if (keyField != null) {
            if (valueFields == null) {
                throw new SolrException(ErrorCode.BAD_REQUEST, "missing parameter updatedv.value.field");
            }
            updateDocValues(keyField, valueFields, cmd);
        } else {
            if (valueFields != null) {
                retrieveDocValues(cmd, valueFields);
            }
            if (next != null) {
                next.processAdd(cmd);
            }
        }
    }

    private void updateDocValues(String keyField, String[] valueFields, AddUpdateCommand cmd) throws IOException {
        RefCounted<IndexWriter> iwref = core.getSolrCoreState().getIndexWriter(core);
        try {
            IndexWriter iw = iwref.get();
            SolrInputDocument solrInputDocument = cmd.getSolrInputDocument();
            updateDocValuesHelper(keyField, valueFields, iw, solrInputDocument);
        } finally {
            iwref.decref();
        }
    }

    private void updateDocValuesHelper(String keyField, String[] valueFields, IndexWriter iw, SolrInputDocument solrInputDocument) throws IOException {
        String key = getStringValue(solrInputDocument, keyField);
        if (key == null) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "no value for updatedv.key.field " + keyField);
        }
        Term term = new Term(keyField, key);
        LOG.debug(String.format("update docvalues %s", term));
        for (String valueField : valueFields) {
            long value = getLongValue(solrInputDocument, valueField);
            iw.updateNumericDocValue(term, valueField, value);
        }
        // update values of child documents, too
        List<SolrInputDocument> childDocuments = solrInputDocument.getChildDocuments();
        if (childDocuments != null) {
            for (SolrInputDocument childDocument : childDocuments) {
                updateDocValuesHelper(keyField, valueFields, iw, childDocument);
            }
        }
    }

    private void retrieveDocValues(AddUpdateCommand cmd, String[] valueFields) throws IOException {
        // retrieve an existing value, apply a default value
        SolrInputDocument doc = cmd.getSolrInputDocument();
        RefCounted<SolrIndexSearcher> searcherRef = core.getSearcher();
        try {
            SolrIndexSearcher searcher = searcherRef.get();
            retrieveDocValuesHelper(valueFields, doc, searcher);
            // apply default values?
        } finally {
            searcherRef.decref();
        }
    }

    private void retrieveDocValuesHelper(String[] valueFields, SolrInputDocument doc, SolrIndexSearcher searcher)
            throws IOException {
        String id = getStringValue(doc, idField);
        if (id == null) {
            return;
        }
        // LOG.debug(String.format("retrieve docvalues %s", id));
        Term idTerm = new Term(idField, id);
        TermQuery query = new TermQuery(idTerm);
        TopDocs docs = searcher.search(query, 1);
        if (docs.totalHits == 1) {
      // get the value
            // LOG.debug(String.format("found %s", id));
            int docID = docs.scoreDocs[0].doc;
            for (String valueField : valueFields) {
                if (doc.get(valueField) == null) {
                    NumericDocValues ndv = searcher.getAtomicReader().getNumericDocValues(valueField);
                    if (ndv != null) {
                        long lvalue = ndv.get(docID);
                        doc.addField(valueField, lvalue);
                        // LOG.debug("retrieved doc value %d", lvalue);
                    } else {
                        doc.addField(valueField, 0);
                        // LOG.debug("Initializing doc value to 0");
                    }
                } else {
                    // LOG.debug(String.format("%s found in input document: %s", valueField, doc.get(valueField)));
                }
            }
        } else {
            for (String valueField : valueFields) {
                // new document, inserted without dv values: default to zero
                doc.addField(valueField, 0);
            }
        }
        List<SolrInputDocument> childDocuments = doc.getChildDocuments();
        if (childDocuments != null) {
            for (SolrInputDocument childDoc : childDocuments) {
                retrieveDocValuesHelper(valueFields, childDoc, searcher);
            }
        }
    }

    private String getStringValue(SolrInputDocument solrInputDocument, String keyField) {
        Object o = solrInputDocument.getFieldValue(keyField);
        if (o == null) {
            return null;
        }
        if (!(o instanceof String)) {
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
