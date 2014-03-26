package com.ifactory.press.db.solr.analysis;

import static org.junit.Assert.*;

import java.io.CharArrayReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.Random;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.Version;
import org.junit.Test;

public class XmlCharFilterTest {
    
    @Test
    public void testReader1 () throws Exception {
        Reader reader = new CharArrayReader ("<test>this is a test</test>".toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        char[] buf = new char[64];
        int n = read(xmlReader, buf);
        assertEquals (" this is a test ", String.valueOf(buf, 0, n));
        assertEquals (0, xmlReader.correctOffset(0));
        assertEquals (6, xmlReader.correctOffset(1));
        assertEquals (16, xmlReader.correctOffset(11));
        assertEquals (26, xmlReader.correctOffset(21));
        assertEquals (0, read(xmlReader, buf));
    }
    
    @Test
    public void testReader2 () throws Exception {
    	String xml = "<test>this <i>is</i> a test <i>of</i> something</test>";
        Reader reader = new CharArrayReader (xml.toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        char[] buf = new char[64];
        int total = read(xmlReader, buf);
        assertTextOffsets (xml, "this is a test of something");
        assertEquals (" this  is  a test  of  something ", String.valueOf(buf, 0, total));
    }
    
    @Test
    public void testXmlEntities () throws Exception {
        assertTextOffsets ("<test>&lt;this&gt; <i>is</i> a test</test>", "<this> is a test");
    }

    private int read(XmlCharFilter xmlReader, char[] buf) throws IOException {
        int n = 0, total = 0;
        do {
            n = xmlReader.read(buf, total, 64);
            if (n >0)
                total += n;
        } while (n > 0);
        return total;
    }
    
    @Test
    public void testReaderBasic () throws Exception {
        assertTextOffsets ("<test>this <i>is</i> a test</test>", "this is a test");        
    }
    
    @Test
    public void testReaderAttribute () throws Exception {
        assertTextOffsets ("<test id=\"i1\">this <i id=\"2\">is</i> a test</test>", "this is a test");        
    }
    
    @Test
    public void testReaderNamespace() throws Exception {
        assertTextOffsets ("<test xmlns=\"#i1\">this <n:i xmlns:n=\"#ns\">is</n:i> a test</test>", "this is a test");        
    }
    
    @Test
    public void testInternalEntity() throws Exception {
        String prolog = "<?xml version=\"1.0\" standalone=\"yes\" ?>\n" +
        "<!DOCTYPE author [\n" +
        "  <!ELEMENT author (#PCDATA)>\n" +
        "  <!ENTITY js \"Jo Smith\">\n" +
        "]>\n";
        String xml = prolog + "<author>&js; Lives!</author>\n";
        XmlCharFilter xmlReader = newReader (new CharArrayReader(xml.toCharArray()));
        char[] buf = new char[64];
        int total = read(xmlReader, buf);
        String output = String.valueOf(buf, 0, total);
        assertEquals (" Jo Smith Lives! ", output);
        assertEquals (xml.indexOf("&js;"), 
                xmlReader.correctOffset(output.indexOf("Jo Smith"))); 
        assertEquals (xml.indexOf("Lives!"), 
                xmlReader.correctOffset(output.indexOf("Lives!"))); 
    }
    
    @Test
    public void testXMLEntities() throws Exception {
        String input = "<bar><foo>&lt;tag id=&quot;1&quot;&gt;&amp;amp;&lt;/tag&gt;</foo>sync</bar>";
        Reader reader = new CharArrayReader (input.toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        char[] buf = new char[64];
        int n = read(xmlReader, buf);
        String output = String.valueOf(buf, 0, n);
        assertEquals ( "  <tag id=\"1\">&amp;</tag> sync ", output);
        assertEquals (input.indexOf("sync"), 
                xmlReader.correctOffset(output.indexOf("sync")));
        assertEquals (input.indexOf("&quot;"), 
                xmlReader.correctOffset(output.indexOf("\"")));
        assertEquals (input.indexOf("&amp;amp;"), 
                xmlReader.correctOffset(output.indexOf("&amp;")));  
    }
    
    @Test
    public void testXMLEntitiesInAttribute () throws Exception {
        // This test is actually kind of pointless since attribute values are completely ignored,
        // but it does test an edge case where we pass a negative offset.
        // And it shows that entities in attributes don't mess up the character offsets of following
        // text.
        String input = "<bar><foo attr=\"&lt;tag id=&quot;1&quot;&gt;&amp;amp;&lt;/tag&gt;\" />sync</bar>";
        Reader reader = new CharArrayReader (input.toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        char[] buf = new char[64];
        int n = read(xmlReader, buf);
        String output = String.valueOf(buf, 0, n);
        assertEquals ( "   sync ", output);
        assertEquals (input.indexOf("sync"), 
                xmlReader.correctOffset(output.indexOf("sync")));
        assertEquals (0, xmlReader.correctOffset(output.indexOf("\"")));
    }
    
    @Test
    public void testNumericEntities() throws Exception {
        String input = "<bar><foo>&#x3c;tag id=&#x22;1&#x22;&#x3e;&#x26;amp;&#x3c;/tag&#x3e;</foo>sync</bar>";
        Reader reader = new CharArrayReader (input.toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        char[] buf = new char[64];
        int n = read(xmlReader, buf);
        String output = String.valueOf(buf, 0, n);
        assertEquals ( "  <tag id=\"1\">&amp;</tag> sync ", output);
        assertEquals (input.indexOf("sync"), 
                xmlReader.correctOffset(output.indexOf("sync")));
        assertEquals (input.indexOf("&#x22;"), 
                xmlReader.correctOffset(output.indexOf("\"")));
        assertEquals (input.indexOf("&#x26;amp;"), 
                xmlReader.correctOffset(output.indexOf("&amp;")));  
    }
    
    @Test
    public void testCDATA() throws Exception {        
        assertTextOffsets (
                "<foo>content text<![CDATA[<greeting>Hello</greeting>]]> other content</foo>", 
                "content text<greeting>Hello</greeting> other content");
        assertTextOffsets (
                "<foo><!--description-->content text<![CDATA[<greeting>Hello</greeting>]]> other content</foo>", 
                "content text<greeting>Hello</greeting> other content");
    }
    
    @Test
    public void testComment() throws Exception {
        assertTextOffsets (
                "<foo><!--description-->content text other content</foo>", 
                "content text other content");
        assertTextOffsets (
                "<foo>\n" +
                "  <!--description-->\n" +
                "  content text <empty id=\"1\"/> other content" +
                "</foo>", 
                "\n  \n  content text  other content");
    }
    
    @Test
    public void testPI () throws Exception {
        assertTextOffsets (
                "<?xml-stylesheet type=\"text/css\" href=\"toto.xsl\"?>" +
                "<foo><!--description-->content text other content</foo>", 
                "content text other content"); 
    }
    
    private String readXMLText (String filename) throws FileNotFoundException, XMLStreamException, FactoryConfigurationError {
        XMLEventReader evr = XMLInputFactory.newInstance().createXMLEventReader(new FileInputStream("pom.xml"));
        StringBuilder buf = new StringBuilder();
        while (evr.hasNext()) {
            XMLEvent evt = evr.nextEvent();
            if (evt.isCharacters()) {
                buf.append(evt.asCharacters().getData());
            }
        }
        return buf.toString();
    }
    
    @Test
    public void testRandomDocument () throws Exception {
        String xml = IOUtils.toString(new FileInputStream("pom.xml"));
        String text = readXMLText("pom.xml");
        assertTextOffsets (xml, text);
    }
    
    @Test
    public void testLineEndingFixup() throws Exception {
        String xml = IOUtils.toString(new FileInputStream("pom.xml"));
        xml = xml.replace("\n", "\r\n");
        String text = readXMLText("pom.xml");
        assertTextOffsets (xml, text);
    }
    
    @Test
    public void testLineEndingFixupSmall() throws Exception {
        String xml = ("<xml>This\r\nis\r\na\r\ntest.\r\n</xml>");
        String text = "This\nis\na\ntest.\n";
        assertTextOffsets (xml, text);
    }
    
    @Test
    public void testInclude () throws Exception {
        char[] buf = new char[64];
    	String xml = "<test>this <i>is</i> a test <i>of</i> something</test>";
        
    	Reader reader = new CharArrayReader (xml.toCharArray());        
        XmlCharFilter xmlReader = newReader(reader, new String[] { "test"}, null);        
        int total = read(xmlReader, buf);
        assertTextOffsets (xml, "this is a test of something");
        assertEquals (" this  is  a test  of  something", String.valueOf(buf, 0, total));
        
        reader = new CharArrayReader (xml.toCharArray());
        xmlReader = newReader(reader, new String[] { "i" }, null);
        total = read(xmlReader, buf);
        assertEquals (" is of", String.valueOf(buf, 0, total));
        
        reader = new CharArrayReader (xml.toCharArray());
        xmlReader = newReader(reader, null, new String[] { "i"});
        total = read(xmlReader, buf);
        assertEquals (" this   a test   something ", String.valueOf(buf, 0, total));
    }


	// check that the text in xml at the offset position indicated by
    // stream matches the characters in cbuf.
    private void assertTextOffsets (String xml, String text) throws Exception {

        Reader reader = new CharArrayReader (xml.toCharArray());
        XmlCharFilter xmlReader = newReader(reader);
        if (xml.contains("\r\n")) {
            xmlReader.setFixupCRLF(true);
        }
        char[] cbuf = new char[xml.length()];
        int total = read(xmlReader, cbuf);
        
        // make sure we got the text we expected
        assertEquals (text.replaceAll("\\s+", " ").trim(),
        		String.valueOf(cbuf, 0, total).replaceAll("\\s+", " ").trim());
        
        // check all the offsets
        for (int i = 0; i < total; i++) {
            int j;
            try {
                j = xmlReader.correctOffset(i);
            } catch (ArrayIndexOutOfBoundsException e) {
                // one of our tests has enough offsets to exceed
                // the internal XmlCharReader buffer size 
                continue;
            }
            char c1 = cbuf[i];
            char c2 = xml.charAt(j);
            if (xmlReader.isFixupCRLF() && c1 == '\n')
                c1 = '\r';
            if (c1 == c2 || (Character.isSpaceChar(c1) && c2 == '<'))
            	continue;
            if (c2 == '&') {
                // read an XML entity
                StringBuilder entity = new StringBuilder();
                while(j < xml.length()) {
                    c2 = xml.charAt(++j);
                    if (c2 == ';') {
                        break;
                    }
                    entity.append(c2);
                }
                String e = entity.toString();
                if (c2 != ';') {
                    throw new Exception ("XML entity " + e + " not terminated with semicolon");
                }
                if (e.matches("#x[a-f0-9]+")) {
                    int codepoint = Integer.valueOf(e.substring(2), 16);
                    // we don't handle astral plane chars
                    c2 = Character.toChars(codepoint)[0];
                } else if (e.matches("#[0-9]+")) {
                    int codepoint = Integer.valueOf(e.substring(1));
                    // we don't handle astral plane chars
                    c2 = Character.toChars(codepoint)[0];
                } else if (e.equals("lt")) {
                    c2 = '<';
                }
                else if (e.equals("gt")) {
                    c2 = '>';
                }
                else if (e.equals("amp")) {
                    c2 = '&';
                }
                else if (e.equals("quot")) {
                    c2 = '"';
                }
                else if (e.equals("apos")) {
                    c2 = '\'';
                }
                if (c1 == c2) {
                    continue;
                }
            }
            try {
                    dumpOffsets ((XmlCharFilter) xmlReader, cbuf, total);
            } catch (ArrayIndexOutOfBoundsException e) { }            
            assertEquals (
                    "In [" + text.substring(i, text.length() > i + 32 ? i + 32 : text.length()) + 
                    "] at " + i + ", [" + c1 + 
                    "] not found in source at " + xmlReader.correctOffset(i) +
                    " which has: [" + c2 + "]",
                    c1, c2);
        }
    }
    
    private void dumpOffsets (XmlCharFilter reader, char[] cbuf, int total) {
        int j = 0;
        for (int i = 0; i < total; i++, j++) {
            int off = reader.correctOffset(i);
            while (j < off) {
                System.out.print(' ');
                ++j;
            }
            System.out.print(cbuf[i]);
        }
    }
    
    @Test
    public void testBinarySearch () {
        Random random = new Random();
        int seed = random.nextInt();
        random.setSeed(seed);
        int[] arr = new int[256];
        // test with some random but tight distribution of sorted integers;
        // we want to make sure some entries are adjacent
        for (int testcount = 0; testcount < 100; testcount++) {
            int r = 0;
            int size = random.nextInt(arr.length-1) + 1;
            for (int i = 0; i < size; i++) {
                r += random.nextInt(4) + 1;
                arr[i] = r;
            }
            for (int casecount = 0; casecount < 10; casecount++) {
                int s = random.nextInt((arr[size-1] * 4)/3);
                int o = XmlCharFilter.binarySearch(s, arr, size);
                assertTrue ("out of bounds " + + seed + " run " + testcount + "," + casecount, o >= 0 && o < size);
                // arr[o] <= s < arr[o+1] (if o < size-1)
                assertTrue ("too high " + seed + " run " + testcount + "," + casecount, 
                        arr[o] <= s || o == 0);
                assertTrue ("too low " + + seed + " run " + testcount + "," + casecount, o == size-1 || arr[o+1] > s);
            }
        }
    }
        
    @Test
    public void testParseQName () {
    	XmlCharFilter.QName [] qnames = XmlCharFilter.QName.parseArray(new String[] {"{ns}foo"});
    	assertNotNull (qnames);
    	assertEquals (1, qnames.length);
    	assertEquals ("ns", qnames[0].namespace);
    	assertEquals ("foo", qnames[0].name);
    	
    	qnames = 
    		XmlCharFilter.QName.parseArray(new String[] {"foo"});
    	assertNotNull (qnames);
    	assertEquals (1, qnames.length);
    	assertEquals ("", qnames[0].namespace);
    	assertEquals ("foo", qnames[0].name);
    }
    
    @Test
    public void testTokenize() throws Exception {
        Analyzer analyzer = new XmlWhitespaceAnalyzer();
        displayTokensWithPositions(analyzer, "<test>This <i>is</i> a test</test>");
        analyzer.close();
        analyzer = new XmlWhitespaceAnalyzer();
        Reader reader = new InputStreamReader (getClass().getResourceAsStream ("/test/docbook-test-2.xml"));
        displayTokensWithPositions(analyzer, reader);
    }
    
    public static void displayTokensWithPositions (Analyzer analyzer, String text) throws IOException {
        displayTokensWithPositions(analyzer, new StringReader(text));
    }

    /* copied from "Lucene in Action, 2d ed, p.137" */
    public static void displayTokensWithPositions (Analyzer analyzer, Reader reader)
        throws IOException {
        TokenStream stream = analyzer.tokenStream("contents", reader);
        CharTermAttribute term = (CharTermAttribute) stream.addAttribute(CharTermAttribute.class);
        PositionIncrementAttribute posIncr =
            (PositionIncrementAttribute) stream.addAttribute(PositionIncrementAttribute.class);
        stream.reset();
        int pos = 0;
        while (stream.incrementToken()) {
            int incr = posIncr.getPositionIncrement();
            if (incr > 0) {
                pos += incr;
                System.out.println();
                System.out.print (pos + ": ");
            }
            System.out.print("[" + term.toString() + "] ");
        }
        System.out.println();
    }
    
    public final class XmlWhitespaceAnalyzer extends Analyzer {

        @Override
        protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
            return new TokenStreamComponents (new WhitespaceTokenizer(
                    Version.LUCENE_46,
                    new XmlCharFilter(reader)));
        }
    }
 
    private XmlCharFilter newReader (Reader input) throws XMLStreamException {
    	return new XmlCharFilter(input);
    }
    

    private XmlCharFilter newReader(Reader reader, String[] includes,
			String[] excludes) {
		return new XmlCharFilter(reader, includes, excludes);
	}
}
