package com.ifactory.press.db.solr.processor;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.solr.core.AbstractSolrEventListener;
import org.apache.solr.core.SolrCore;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ifactory.press.db.solr.spelling.suggest.MultiSuggester;

/**
 * trigger commit to suggestion index when commit happens on main index. This is
 * required, in addition to the commit handler in the UpdateRequestProcessor, in
 * order to handle autoCommit and autoSoftCommit. Note: in testing we sometimes
 * saw exceptions when autoCommit and autoSoftCommit happened on or about the
 * same time; so we added the sync, but more testing is needed to ensure that
 * really took care of the issue.
 */
public class MultiSuggesterCommitListener extends AbstractSolrEventListener {

    private final ArrayList<MultiSuggester> suggesters;

    private static final Logger LOG = LoggerFactory.getLogger(MultiSuggesterProcessor.class);

    public MultiSuggesterCommitListener(SolrCore core, ArrayList<MultiSuggester> suggesters) {
        super(core);
        this.suggesters = suggesters;
    }

    @Override
    public void postCommit() {
        doCommit();
    }

    @Override
    public void postSoftCommit() {
        doCommit();
    }

    // synchronized hoping to avoid issues from multiple asynchronous events
    private synchronized void doCommit() {
        if (getCore().isClosed()) {
            return;
        }
        RefCounted<SolrIndexSearcher> searcher = getCore().getSearcher();
        try {
            for (MultiSuggester suggester : suggesters) {
                suggester.commit(searcher.get());
            }
        } catch (IOException e) {
            LOG.error("An IOException was thrown while committing changes to the spell suggestion index", e);
        } finally {
            searcher.decref();
        }
    }

}
