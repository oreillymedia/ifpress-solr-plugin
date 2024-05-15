package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.solr.search.EarlyTerminatingCollectorException;

/** A collector to use when you know there will be no more than one match, or you don't
 * care which match you get; terminates after finding a match.
 */
class FirstCollector extends SimpleCollector {

    private LeafReaderContext arc;

    int docID = -1;

    public int getDocID() {
        return docID;
    }

    @Override
    public void collect(int doc) throws IOException {
        this.docID = arc.docBase + doc;
        throw new EarlyTerminatingCollectorException(1, 1);
    }

    @Override
    protected void doSetNextReader(LeafReaderContext context) throws IOException {
        arc = context;
    }

    @Override
    public ScoreMode scoreMode() {
        return ScoreMode.COMPLETE_NO_SCORES;
    }
}