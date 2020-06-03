package org.apache.solr.handler.dataimport;

import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A hybrid SQL/Json entity processor which reads JSON/JSONB data from a JDBC data source and maps the json data to
 * fields in the schema
 */
public class JsonSQLEntityProcessor extends EntityProcessorBase {
    private static final Logger LOG = LoggerFactory.getLogger(JsonSQLEntityProcessor.class);

    private DataSource<Iterator<Map<String, Object>>> dataSource;

    private static final String QUERY = "query";

    private static Pattern SELECT_WHERE_PATTERN = Pattern.compile("^\\s*(select\\b.*?\\b)(where).*", Pattern.CASE_INSENSITIVE);

    @Override
    public void init(Context context) {
        super.init(context);
        this.dataSource = context.getDataSource();
    }

    private void initQuery(String query) {
        try {
            JsonSQLEntityProcessor.QUERY_COUNT.get().incrementAndGet();
            rowIterator = dataSource.getData(query);
            this.query = query;
        } catch (DataImportHandlerException e) {
            throw e;
        } catch ( Exception e) {
            LOG.error("The query failed '" + query + "'", e);
            throw new DataImportHandlerException(DataImportHandlerException.SEVERE, e);
        }
    }

    @Override
    public Map<String, Object> nextRow() {
        if (rowIterator == null) {
            String query = getQuery();
            initQuery(context.replaceTokens(query));
        }

        return getNext();
    }



    public String getQuery() {
        // Just return the query attribute, since we do want to do a full dump on each import
        return context.getEntityAttribute(QUERY);
    }

    static final ThreadLocal<AtomicLong> QUERY_COUNT = new ThreadLocal<AtomicLong>() {
        @Override
        protected AtomicLong initialValue() {
            return new AtomicLong();
        }
    };

}
