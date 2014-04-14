# PubFactory Solr plugin project

This library provides Solr plugins used at Safari.  These are designed 
to be loaded into Solr by installing the jar in a library folder that
will extend Solr's classpath: usually solr/lib.

## XmlCharFilter

XmlCharFilter is a Lucene CharFilter that extracts text from XML for
indexing. It is primarily used to support highlighting search terms in 
full XML documents.  It's somewhat redundant with HtmlStripCharFilter,
but uses a true XML parser so that entities defined in DTDs can be
processed.  

## HitCount

HitCount is a function that counts the total number of times all query
terms occur each search result.

## FieldMergingProcessor

FieldMergingProcessor is a Solr UpdateRequestProcessor that merges 
several fields into one field.  It provides a similar function as the
built-in copyFields directive but also allows for a different Analyzer
to be used with each source field.
