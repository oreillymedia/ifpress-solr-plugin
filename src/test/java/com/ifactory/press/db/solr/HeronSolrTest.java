package com.ifactory.press.db.solr;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.junit.After;
import org.junit.Before;

public class HeronSolrTest {

    static CoreContainer coreContainer;
    protected SolrServer solr;

    @Before
    public void startup() throws IOException, SolrServerException {
        // start an embedded solr instance
        coreContainer = new CoreContainer("solr");
        coreContainer.load();
        solr = new EmbeddedSolrServer(coreContainer, "heron");
        solr.deleteByQuery("*:*");
        solr.commit();
    }

    @After
    public void shutdown() throws Exception {
        SolrCore core = coreContainer.getCore("heron");
        if (core != null) {
            core.close();
        }
        coreContainer.shutdown();
    }

}
