package com.ifactory.press.db.solr.highlight;

import java.io.CharArrayReader;
import java.io.File;
import static org.junit.Assert.*;
import static org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Collection;
import java.util.regex.Pattern;
import org.apache.commons.io.FileUtils;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.core.SolrXmlConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PostingsHighlighterTest {

    private static final Version VERSION = Version.LATEST;

    private IndexWriter iw;


    static CoreContainer coreContainer;
    protected SolrClient solr;   // rivey

    @Before
    public void startup() throws Exception {
        FileUtils.cleanDirectory(new File("solr/configsets/collection1/data/"));
        FileUtils.cleanDirectory(new File("solr/configsets/collection1/suggestIndex/"));
        FileUtils.cleanDirectory(new File("solr/configsets/heron/data/"));
        // start an embedded solr instance
        coreContainer = new CoreContainer("solr");
        Collection<String> c = coreContainer.getAllCoreNames();
        System.out.println("c = " + c.toString());
        coreContainer.load();
        RAMDirectory dir = new RAMDirectory();
        IndexWriterConfig iwc = new IndexWriterConfig(new SafariAnalyzer(true));  // rivey took VERSION out
        iw = new IndexWriter(dir, iwc);
    }
    
     private CoreContainer init(Path homeDirectory, String xml) throws Exception {
        SolrResourceLoader loader = new SolrResourceLoader(homeDirectory);
        CoreContainer ret = new CoreContainer(SolrXmlConfig.fromString(loader, xml));
        ret.load();
        return ret;
    }
    
    protected static SolrCore getDefaultCore() {
        return coreContainer.getCore("collection1");
    }
    
    @After
    public void cleanup() throws IOException {
        iw.close();
    }

    @Test
    public void testHighlightChapter5() throws IOException {
    // searching for "gas" didn't work on the Safari site
        SolrCore core = getDefaultCore();
        InputStream ch5stream = getClass().getResourceAsStream("ch5.txt");
        String ch5 = IOUtils.toString(ch5stream);

        // add a single document to the index
        // configure field with offsets at index time
        FieldType offsetsType = new FieldType(TextField.TYPE_STORED);
        offsetsType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        Field text = new Field("text", ch5, offsetsType);

        Document doc = new Document();
        doc.add(new StringField("id", "ch5", Store.YES));
        doc.add(text);
        iw.addDocument(doc);
        iw.commit();

        DirectoryReader reader = DirectoryReader.open(iw); //, //true);
        IndexSearcher searcher = new IndexSearcher(reader);

        // retrieve highlights at query time 
        SafariAnalyzer sa = new SafariAnalyzer(true);
        UnifiedHighlighter highlighter = new UnifiedHighlighter(searcher, sa);
        Query query = new TermQuery(new Term("text", "gas"));
        TopDocs topDocs = searcher.search(query, 1);
       
        String highlights[]  = highlighter.highlight("text", query, topDocs);
        
        assertEquals(1, highlights.length);
        assertNotNull("PH returns null highlight", highlights.length);
        assertTrue(highlights[0] + " \n does not contain <b>gas</b>", highlights[0].contains("<b>gas</b>"));
    }

    class SynonymAnalyzer extends Analyzer {

        @Override //  rivey newly added
        protected TokenStreamComponents createComponents(String string) {
            Tokenizer tokenizer = new WhitespaceTokenizer(); //  (reader);   rivey does this not need a reader passed to it? check usage
            TokenFilter filter = new LowerCaseFilter(tokenizer);
            return new TokenStreamComponents(tokenizer, filter);
        }
    }

    class SafariAnalyzer extends Analyzer {

        private final boolean isIndexAnalyzer;

        public SafariAnalyzer(boolean isIndexAnalyzer) {
            this.isIndexAnalyzer = isIndexAnalyzer;
        }

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            //, Reader reader) { rivey - had to take this out of param list  -- need to trace
            Reader reader = new CharArrayReader(fieldName.toCharArray());
            CharFilter charFilter = new HTMLStripCharFilter(reader);
            Pattern pat1 = Pattern.compile("([A-Za-z])\\+\\+");
            charFilter = new PatternReplaceCharFilter(pat1, "$1plusplus", charFilter);  // this is freed
            charFilter = new PatternReplaceCharFilter(Pattern.compile("([A-Za-z])\\#"), "$1sharp", charFilter);
            Tokenizer tokenizer = new WhitespaceTokenizer();
            //Tokenizer tokenizer = new WhitespaceTokenizer(charFilter);
            // TODO protwords.txt
            TokenFilter filter = new WordDelimiterFilter(tokenizer,
                    GENERATE_WORD_PARTS
                    | GENERATE_NUMBER_PARTS
                    | SPLIT_ON_CASE_CHANGE
                    | SPLIT_ON_NUMERICS
                    | STEM_ENGLISH_POSSESSIVE
                    | PRESERVE_ORIGINAL,
                    null);
            filter = new LowerCaseFilter(filter);
            if (isIndexAnalyzer) {
                filter = new SynonymFilter(filter, buildSynonymMap(), true);
            }
            // TODO: HunspellStemFilter
            filter = new RemoveDuplicatesTokenFilter(filter);
            return new TokenStreamComponents(tokenizer, filter);
        }

        private SynonymMap buildSynonymMap() {
            SolrSynonymParser parser = new SolrSynonymParser(true, true, new SynonymAnalyzer());
            try {
                parser.parse(new InputStreamReader(getClass().getResourceAsStream("synonyms.txt")));
                return parser.build();
            } catch (ParseException e) {
                throw new RuntimeException("failed to parse synonyms", e);
            } catch (IOException e) {
                throw new RuntimeException("failed to read synonyms", e);
            }
        }


    }

}
