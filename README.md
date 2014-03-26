# PubFactory Solr plugin project

This library provides two Solr plugins.  These are designed to be loaded
into Solr by installing the jar in a library folder that will extend Solr's
classpath: usually solr/lib.

## XmlCharFilter

XmlCharFilter is a Lucene CharFilter that extracts text from XML for
indexing. It is primarily used to support highlighting search terms in full
XML documents.

## HitCount

HitCount is a function that counts the total number of times all query
terms occur each search result.

