package com.ifactory.press.db.solr.processor;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.SpellCheckComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.spelling.SolrSpellChecker;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ifactory.press.db.solr.spelling.suggest.MultiSuggester;

public class MultiSuggesterProcessorFactory extends UpdateRequestProcessorFactory implements SolrCoreAware {
    
    private String suggesterComponentName;
    
    private final ArrayList<MultiSuggester> suggesters = new ArrayList<MultiSuggester>();

    private static final Logger LOG = LoggerFactory.getLogger(MultiSuggesterProcessor.class);
    
    
    @Override
    public void init (@SuppressWarnings("rawtypes") NamedList args) {
        Object componentName = args.get("suggester-component");
        if (componentName == null) {
            throw new SolrException(ErrorCode.SERVER_ERROR, "Missing configuration: 'suggester-component'");
        }
        suggesterComponentName = componentName.toString();
    }
    
    @Override
    public UpdateRequestProcessor getInstance(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
        return new MultiSuggesterProcessor(suggesters, next);
    }
    
    @Override
    public void inform(SolrCore core) {
        
        SpellCheckComponent suggesterComponent = (SpellCheckComponent) core.getSearchComponent(suggesterComponentName);
        if (suggesterComponent == null) {
            LOG.warn("No suggester component found named: " + suggesterComponentName);
            return;
        }
        
        for (SolrSpellChecker spellChecker : suggesterComponent.getSpellCheckers().values()) {
            if (spellChecker instanceof MultiSuggester) {
                suggesters.add ((MultiSuggester) spellChecker);
            }
        }
        
        core.addCloseHook(new CloseHook() {
                
            @Override
            public void preClose(SolrCore core) {
            }
                
            @Override
            public void postClose(SolrCore core) {
                for (MultiSuggester suggester : suggesters) {
                    try {
                        if (suggester != null) {
                            suggester.close();
                        }
                    } catch (IOException e) {
                        LOG.error("An exception occurred while closing", e);
                    }
                }
            }
        });
   
    }
    

}
