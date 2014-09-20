package com.ifactory.press.db.solr;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public class SolrTest {

    static CoreContainer coreContainer;
    protected SolrServer solr;

    @BeforeClass
    public static void startup() throws Exception {
        try {
            FileUtils.cleanDirectory(new File("solr/collection1/data/"));
        } catch (Exception e) { }
        // start an embedded solr instance
        coreContainer = new CoreContainer("solr");
        coreContainer.load();
    }

    @AfterClass
    public static void shutdown() throws Exception {
        SolrCore core = coreContainer.getCore("collection1");
        if (core != null) {
            core.close();
        }
        coreContainer.shutdown();
    }

    @Before
    public void init() throws Exception {
        solr = new EmbeddedSolrServer(coreContainer, "collection1");
        clearIndex();
    }
    
    protected void clearIndex () throws SolrServerException, IOException {
        solr.deleteByQuery("*:*");
        solr.commit(false, true, true);
    }

    @After
    public void cleanup() throws Exception {
    }

}