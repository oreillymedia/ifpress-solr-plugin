package com.ifactory.press.db.solr;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

public class DismaxTest extends HeronSolrTest {

    @Test
    public void testParsePlus() throws SolrServerException, IOException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "1");
        doc.addField("text", "love + death");
        solr.add(doc);
        solr.commit();
        SolrQuery query = new SolrQuery("life + death");
        query.setShowDebugInfo(true);
        query.set("indent", "true");
        SolrResponse resp = solr.query(query);
        System.out.println(resp.getResponse().get("debug"));
    }
}
