package com.ifactory.press.db.solr.analysis;

import static javax.xml.stream.XMLStreamConstants.CDATA;
import static javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static javax.xml.stream.XMLStreamConstants.COMMENT;
import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.ENTITY_REFERENCE;
import static javax.xml.stream.XMLStreamConstants.PROCESSING_INSTRUCTION;
import static javax.xml.stream.XMLStreamConstants.SPACE;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.stream.StreamSource;

import org.apache.lucene.analysis.CharFilter;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.XMLStreamReader2;

import com.ctc.wstx.api.WstxInputProperties;

/**
 * Uses a StAX (XML pull-style) parser to retrieve a subset of
 * characters from an XML character Reader.  The basic usage retrieves
 * all text from the input stream, preserving character offsets into the
 * original XML stream.
 * 
 * <p>Why would you want to use this rather than 
 * org.apache.lucene.analysis.charfilter.HTMLStripCharFilter?
 * </p>
 * <p>For one thing, it supports a few standard XML features that the HTML filter doesn't: 
 *  specify a character set specified in the XML declaration
 *  CDATA
 *  XML entity substitution (internal or external via DTD)
 * </p>
 * 
 * <p>But probably the best additional feature here is the ability to exclude and include
 *  the contents of various elements (in a namespace-aware way).  So for example, if you only 
 *  want to search the content of headers, or only the body of a document (ignoring metadata tags),
 *  but you would like to be able to retrieve the entire original document when highlighting, this
 *  will be helpful.  See {@link XmlCharFilterFactory} for configuration docs.
 * </p>
 * 
 * <p>Note: DTDs referenced in XML DOCTYPE declarations are looked for in the solr/core/conf/dtd folder 
 * regardless of what their full path in the XML may be.  This is a fairly rigid mechanism we only 
 * use in testing right now, but it 
 * could be extended in the future to allow external configuration using a catalog, say.
 * Dependent files referenced by the DTD are looked for relative to that folder: their paths are retained. This makes it
 * possible to manage DTDs bundled with dependent modules in subdirectories.
 * </p>
 *
 */
public class XmlCharFilter extends CharFilter {

    private static final int CDATA_LENGTH = "<![CDATA[".length();
    private static final XMLInputFactory INPUT_FACTORY;
    static {
        INPUT_FACTORY = XMLInputFactory2.newInstance();
        INPUT_FACTORY.setProperty (XMLInputFactory.IS_COALESCING, false);
        INPUT_FACTORY.setProperty (XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        INPUT_FACTORY.setProperty (XMLInputFactory2.P_REPORT_PROLOG_WHITESPACE, false);
        INPUT_FACTORY.setProperty (XMLInputFactory2.RESOLVER, new Resolver());
        INPUT_FACTORY.setProperty (WstxInputProperties.P_NORMALIZE_LFS, false);
        INPUT_FACTORY.setProperty (WstxInputProperties.P_TREAT_CHAR_REFS_AS_ENTS, true);
        // must set this to 1 in order to get TREAT_CHAR_REFS_AS_ENTS to report entities?
        INPUT_FACTORY.setProperty (WstxInputProperties.P_MIN_TEXT_SEGMENT, Integer.valueOf(1));
        
        // P_DTD_RESOLVER
        // CACHE_DTDS
        // P_CUSTOM_INTERNAL_ENTITIES
    }
    private XMLStreamReader2 xmlReader;
    
    private int mapSize;
    private int mapMaxSize = 256;
    private int offsetFrom[] = new int[mapMaxSize];
    private int offsetTo[] = new int[mapMaxSize];
    private int offset0;  // offsetTo[0], at initialization
    private int totalOut = 0;
    
    private int xmlReaderOffset = 0; // offset into current text event
    
    private boolean fixupCRLF = false;
    
    private Map<QName,Boolean> includedElements;    
    private ArrayList<Boolean> including; // whether text is indexed or not
    private HashMap<String,QName[]> qnameTable;
    
	/**
	 * Creates a new filter using a 
	 * @param reader the character stream to filter
	 */
    public XmlCharFilter (Reader reader) {
    	super (reader);

        try {
            xmlReader = (XMLStreamReader2) INPUT_FACTORY.createXMLStreamReader(reader);
            // initialize by reading up to first text event
            // next();
        } catch (XMLStreamException e) {
            e.printStackTrace();
            xmlReader = null;
        }
        offset0 = offsetTo[0];
        offsetFrom[0] = 0;
    }
    
    public XmlCharFilter (Reader reader, String[] includes, String[] excludes) {
    	this (reader);
    	if (includes != null)
    		addIncludes(includes, true);
    	if (excludes != null)
    		addIncludes(excludes, false);
        if (includedElements != null) {
        	including = new ArrayList<Boolean>();
        	Boolean includeRoot = includedElements.get("/");
        	if (includeRoot == null) {
        		// default to include root if no included elements are specified,
        		// and *not* to include root otherwise
        		includeRoot = (includes == null);
        	}
        	if (!includeRoot)
        		including.add(false);
        	qnameTable = new HashMap<String,QName[]>();
        }
    }
    
    /**
     * This implements an efficient offset-map, but it only spans a fixed window size.
     * From current usage in Solr/Lucene it appears that it is necessary to 
     * maintain correctOffset for input values 0 and [start,end] of the current token,
     * so this optimization should be safe for most uses.
     * 
     * @return the corrected offset for the given current offset
     * @param currentOff the current offset to be corrected.  
     * @throws ArrayIndexOutOfBoundsException If the currentOff is out of range
     * of the range of offsets currently maintained by the reader.
     */
    @Override
    public int correct(int currentOff) {
        if (currentOff <= 0)
            return offset0;
        
        if (currentOff < offsetFrom[0])
            throw new ArrayIndexOutOfBoundsException("Offset out of range: " + currentOff + ", you need to increase XmlCharFilter.maxMapSize");                
        
        int i = getIndex(currentOff);
        return offsetTo [i] + currentOff;
    }

    // get the index of the greatest entry in offsetFrom that is <= currentOff via binary search
    private int getIndex(int currentOff) {
        return binarySearch(currentOff, offsetFrom, mapSize);
    }
    
    // expose as package private for unit testing:
    static int binarySearch(int value, int[] arr, int arrSize) {
        int lo = 0;
        int hi = arrSize-1;
        // assume that most requests will come at the end of the array
        int j = hi;
        for (;;) {
            if (arr[j] <= value) {
                if (j == hi || arr[j+1] > value)
                    return j;
                // j we want is in (j,hi]
                lo = j+1;
            } else {
                if (j == 0)
                    return j;
                // j we want is in [lo,j)
                hi = j-1;
            }
            j = (lo + hi) / 2;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (xmlReader != null)
                xmlReader.close();
        } catch (XMLStreamException e) {
            throw new IOException("error closing xml stream");
        }
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        while (xmlReader != null) {
            try {
                int nread = 0;
                int event = xmlReader.getEventType();
                if (event == ENTITY_REFERENCE) {
                	// ???
                    String text = xmlReader.getText();
                    int max = cbuf.length - off;
                    nread = text.length() - xmlReaderOffset;
                    if (nread > len) nread = len;
                    if (nread > max) nread = max;
                    if (nread > 0) {
                        System.arraycopy(text.toCharArray(), 0, cbuf, off, nread);                       
                    }
                }
                else if (event == CHARACTERS || event == CDATA || event == SPACE) {
                	if (isIncluding())
                		nread = xmlReader.getTextCharacters(xmlReaderOffset, cbuf, off, len);
                }
                else if (xmlReaderOffset == -1) {
                    // insert whitespace for other tags to avoid including
                    // them as part of a word token
                    cbuf[off] = ' ';
                    ++totalOut;
                    xmlReaderOffset = 0;
                    return 1;
                }
                if (nread > 0) {
                    if (fixupCRLF) {
                        offsetCRLF (cbuf, off, nread);
                    }
                    xmlReaderOffset += nread;
                    totalOut += nread;
                    return nread;
                }
                if (! next()) 
                    return -1;
            } catch (XMLStreamException e) {
                throw new IOException("error getting text from xml reader", e);
            }            
        }
        // an error occurred
        return -1;
    }
    
    // generate character offsets wherever there is a line feed (\n == 10)
    // since we're told it was a CRLF (\r\n = 13, 10) in the original text
    // XML parser are *required* to perform this "normalization"
    private void offsetCRLF(char[] cbuf, int off, int nread) {
        for (int i = off; i < off + nread; i++) {
            if (cbuf[i] == '\n') {
                pushOffset(totalOut + i - off + 1, offsetTo[mapSize-1] + 1);
            }
        }
    }

    // read events from the StAX input until we get some text
    // @return whether any text was read, or false if we are at the end
    private boolean next () throws XMLStreamException {
        while (xmlReader.hasNext()) {
            int event = xmlReader.next(); 
            int pos = xmlReader.getLocation().getCharacterOffset(); 
            
            if (includedElements != null && (event == START_ELEMENT || event == END_ELEMENT)) {
            	// get the element name and namespace
            	String name = xmlReader.getLocalName();
            	String namespace = xmlReader.getNamespaceURI();
            	Boolean inc = includedElements.get(QName.get(name, namespace, qnameTable));
            	if (inc != null) {
            		if (event == START_ELEMENT)
            			including.add (inc);
            		else
            			including.remove(including.size()-1);
            	}
            }
            if (!isIncluding())
        		continue;
            
        	if (event == START_ELEMENT || event == END_ELEMENT || event == COMMENT || event == PROCESSING_INSTRUCTION) {
                pushOffset (pos - totalOut);
                xmlReaderOffset = -1; // indicates to push a single space on to the stream
                shrinkOffsetMaps();
                return true;
            }
            if (event == CHARACTERS || event == SPACE)
            {
                xmlReaderOffset = 0;
                pushOffset (pos - totalOut);
                return true;
            }
            if (event == CDATA) {
                xmlReaderOffset = 0;
                pushOffset (pos - totalOut + CDATA_LENGTH);
                return true;
            }
            if (event == ENTITY_REFERENCE) {
                xmlReaderOffset = 0;
                pushOffset (pos - totalOut);
                return true;
            }
        }
        return false;
    }

    // record the offset of the current text block
    private void pushOffset(int delta) {
        pushOffset (totalOut, delta);
    }
    
    private void shrinkOffsetMaps () {
        if (mapSize > 128) {
            System.arraycopy(offsetFrom, (mapSize+1)/2, offsetFrom, 0, mapSize/2);
            System.arraycopy(offsetTo, (mapSize+1)/2, offsetTo, 0, mapSize/2);
            mapSize = mapSize / 2;
        }
    }
    
    // record an arbitrary offset - this is used to handle crlf normalization    
    private void pushOffset(int fromOffset, int toOffset) {
        if (mapSize >= mapMaxSize) {
            // grow the maps
            mapMaxSize += 256;
            offsetFrom = expandArray(offsetFrom, mapMaxSize);
            offsetTo = expandArray(offsetTo, mapMaxSize);
        }
        offsetFrom[mapSize] = fromOffset;
        offsetTo[mapSize] = toOffset;
        ++mapSize;
    }

    /**
     * @param newSize 
     * 
     */
    private int[] expandArray(int[] arr, int newSize) {
        int [] tmp = new int[newSize];
        System.arraycopy(arr, 0, tmp, 0, arr.length);
        return tmp;
    }

    /** If the input was encoded using Windows-style CRLF line endings,
     * they will have been transformed by the XML parser into LF endings,
     * unbeknownst to us.  If this fixupCRLF is true, an additional 
     * single character offset is inserted wherever a line ending occurs
     * to account for this.
     * 
     * @return whether line-ending offsets will be applied
     */
    public boolean isFixupCRLF() {
        return fixupCRLF;
    }

    /**
     * @param fixupCRLF whether to assume the input had CRLF line endings
     */
    public void setFixupCRLF(boolean fixupCRLF) {
        this.fixupCRLF = fixupCRLF;
    }
    
    /**
     * Reads from /com/ifactory/press/dtd on the classpath.  A dtd called foo.dtd is expected to be found
     * in a subdirectory named foo; so in /com/ifactory/press/dtd/foo/foo.dtd. 
     * If no matching dtd is found there,
     * returns null so the built-in resolved can try default resolution methods, like
     * retrieving from the web or filesystem.  The HTML dtds are included on the classpath 
     * so as to avoid dinging w3.org a lot. 
     */
    public static class Resolver implements XMLResolver {

        private HashMap<String,String> directoryMap;
        
        public Resolver () {
            directoryMap = new HashMap<String, String>();
            directoryMap.put ("http://www.w3.org/TR/xhtml11/DTD", "/com/ifactory/press/dtd/xhtml11");
            directoryMap.put ("http://www.w3.org/TR/xhtml-modularization/DTD", "/com/ifactory/press/dtd/xhtml11");
            directoryMap.put ("http://www.w3.org/TR/ruby", "/com/ifactory/press/dtd/xhtml11");
        }
        
        @Override
        public Object resolveEntity
            (final String publicID, final String systemID,
             final String baseURI, final String namespace) throws XMLStreamException 
        {
            String filename = systemID;
            String sysidDir;
            int islash = systemID.lastIndexOf('/');
            if (islash >= 0) {
                filename = systemID.substring(islash + 1);
                sysidDir = systemID.substring(0, islash);
            } else {
                filename = systemID;
                if (baseURI != null && baseURI.contains("/")) {
                    sysidDir = baseURI.substring(0, baseURI.lastIndexOf('/'));
                } else {
                    sysidDir = "";
                }
            }
            String directory = directoryMap.get(sysidDir);
            if (directory == null) {
                String basename = filename.substring(0, filename.lastIndexOf('.'));
                directory = "/com/ifactory/press/dtd/" + basename;
                if (islash > 0) {
                    directoryMap.put(sysidDir, directory);
                }
            }
            filename = directory  + "/" + filename;
            InputStream in = getClass().getResourceAsStream(filename);
            if (in == null) {
                if (systemID.startsWith("http")) {
                    // disable dtd processing by returning an empty stream rather than fetching random crap off the interwebs?
                    return new ByteArrayInputStream(new byte[0]);
                }
                // allow trying to read from the filesystem?
                return null;
            }
            return new StreamSource (in, systemID);
        }
    }

    private boolean isIncluding() {
    	return including == null || including.isEmpty() ||
    		including.get(including.size()-1);
    }
    
	private void addIncludes(String[] qnames, boolean include) {
    	if (includedElements == null) {
    		includedElements = new HashMap<QName, Boolean>();
    	}
		for (QName q : QName.parseArray(qnames)) {
			includedElements.put(q, include);
		}
	}
	
	static class QName {
		String namespace;
		String name;
		
		static QName get (String name, String namespace, HashMap<String,QName[]> interned) {
			QName[] names = interned.get(name);
			if (names != null) {
				if (namespace == null)
					namespace = "";
				for (QName qname : names) {
					if (qname.namespace.equals(namespace))
						return qname;
				}
				QName[] newnames = new QName[names.length + 1];
				System.arraycopy(names, 0, newnames, 0, names.length);
				names = newnames;
			} else {
				names = new QName[1];
			}
			QName qname = new QName (name, namespace);
			names[names.length-1] = qname;
			interned.put (name, names);
			return qname;
		}
		
		QName (String name, String namespace) {
			this.namespace = namespace == null ? "" : namespace;
			this.name = name == null ? "" : name;
		}
		
		QName (String qname) {
			if (qname.matches("\\{.+\\}.+")) {
				int ibrace = qname.lastIndexOf('}');
				namespace = qname.substring(1, ibrace);
				name = qname.substring(ibrace+1);
			} else {
				name = qname;
				namespace = "";
			}
		}
		
		static QName[] parseArray (String [] arr) {
			if (arr == null) {
				return null;
			}
			QName[] qnames = new QName[arr.length];
			int i = 0;
			for (String include : arr) {
				qnames[i++] = new QName (include);
			}
			return qnames;
		}
		
		@Override
		public boolean equals (Object other) {
			if (super.equals(other))
				return true;
			if (other == null)
				return false;
			return (namespace.equals(((QName)other).namespace) &&
					name.equals(((QName)other).name));
		}
		
		@Override
		public int hashCode() {
			return (name + namespace).hashCode();
		}
		
		@Override
		public String toString () {
			if (namespace.length() > 0)
				return String.format ("{%s}%s", namespace, name);
			return name;
		}
	}
}
