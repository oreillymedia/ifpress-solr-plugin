package com.ifactory.press.db.solr;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public class HeronSolrTest {

  static CoreContainer coreContainer;
  protected SolrClient solr;

  @BeforeClass
  public static void startup() throws Exception {
    FileUtils.cleanDirectory(new File("solr/heron/data/"));
    // start an embedded solr instance
    Path solrHome = Paths.get("solr");
    coreContainer = CoreContainer.createAndLoad(solrHome);
  }

  @AfterClass
  public static void shutdown() throws Exception {
    SolrCore core = getDefaultCore();
    if (core != null) {
      core.close();
    }
    coreContainer.shutdown();
    FileUtils.cleanDirectory(new File("solr/heron/data/"));
    coreContainer = null;
  }

  @Before
  public void init() throws Exception {
    solr = new EmbeddedSolrServer(coreContainer, "heron");
    clearIndex();
  }

  protected void clearIndex () throws SolrServerException, IOException {
    solr.deleteByQuery("*:*");
    solr.commit(true, true, false);
  }

  protected static SolrCore getDefaultCore() {
    return coreContainer.getCore("heron");
  }

  @After
  public void cleanup() throws Exception {
  }

}