package com.ifactory.press.db.solr.analysis;

import java.io.Reader;
import java.util.Map;

import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * Support for configuring an XmlCharFilter.  Supply attributes <code>include</code> and <code>exclude</code>
 * to strip XML elements by name.  Text is indexed if its nearest included-or-excluded ancestor element is in 
 * fact included.  For example, you might exclude "head" from an XHTML document, but include "title".  This would
 * have the effect of indexing the title and the body.  The <code>include</code> and <code>exclude</code> attributes
 * accept comma-separated lists of XML element names.  Each such name may include a namespace wrapped in curly braces, like:
 * <code>{http://www.w3.org/1999/xhtml}head</code>.
 */
public class XmlCharFilterFactory extends CharFilterFactory {
    
    private String[] includes=null, excludes=null;
    
    /*
     * for Solr 4.5.0
     */
    public XmlCharFilterFactory(Map<String, String> args) {
        super(args);
        init (args);
    }
    
    protected void init (Map<String,String> params) {
        if (params == null) {
            return;
        }
        String incAttr = params.get("include"); 
        String excAttr = params.get("exclude");      
        if (incAttr != null) {
            includes = incAttr.split("\\s*,\\s*");
        }
        if (excAttr != null) {
            excludes = excAttr.split("\\s*,\\s*");
        }
    }
    
    @Override
    public Reader create(Reader input) {
        return new XmlCharFilter(input, includes, excludes);
    }
}
