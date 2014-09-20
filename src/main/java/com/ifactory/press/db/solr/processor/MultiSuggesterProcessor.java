package com.ifactory.press.db.solr.processor;

import java.io.IOException;
import java.util.Collection;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;

import com.ifactory.press.db.solr.spelling.suggest.MultiSuggester;

/**
 * This processor enables incremental updates (aka near-realtime updates) for AnalyzingInfixSuggester.
 * It is tied to a spell check component and discovers any MultiSuggesters configured for that component.
 */
public class MultiSuggesterProcessor extends UpdateRequestProcessor {
    
    private final Collection<MultiSuggester> suggesters;

    public MultiSuggesterProcessor(Collection<MultiSuggester> suggesters, UpdateRequestProcessor next) {
        super(next);
        this.suggesters = suggesters;
    }

    @Override
    public void processAdd(AddUpdateCommand cmd) throws IOException {
        SolrInputDocument doc = cmd.getSolrInputDocument();
        for (MultiSuggester suggester : suggesters) {
            suggester.add (doc, cmd.getReq().getSearcher());
        }
        if (next != null) {
            next.processAdd(cmd);
        }
    }
    
    @Override
    public void processCommit(CommitUpdateCommand cmd) throws IOException {
        for (MultiSuggester suggester : suggesters) {
            suggester.commit ();
        }
        if (next != null) {
            next.processCommit(cmd);
        }
    }
    
    // Note: what to do about deletions?  We don't have any good way to know when a suggestion is deleted
    // since it could occur in multiple documents.  In throry we can either rebuild, or ref count, or ignore.  
    // Only ignoring makes any sense though -- users will need to issue rebuild commands manually

}
