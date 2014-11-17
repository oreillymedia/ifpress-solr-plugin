# Safari Solr plugin project

This library provides Solr plugins used at Safari.  These are designed 
to be loaded into Solr by installing the jar in a library folder that
will extend Solr's classpath: usually solr/lib.

## HitCount

HitCount is a function, for use as part of a Solr query, that counts the total number of times all query
terms occur within each search result.

## FieldMergingProcessor

FieldMergingProcessor is a Solr UpdateRequestProcessor that merges 
several fields into one field.  It provides a similar function to the
built-in copyFields directive but allows for a different Analyzer
to be used with each source field.

## MultiSuggester, SafariInfixSuggester, MultiSuggesterProcessor

MultiSuggester is a Suggester -- in Solr, deployed as part of a SpellcheckComponent -- that 
provides suggestions drawn from multiple sources. 

MultiSuggester also provides incremental update methods  that are used by MultiSuggesterProcessor. In this
configuration, terms from newly added documents are added to the suggester index as part of the update process,
so a full suggester index rebuild is not required.

SafariInfixSuggester is a wrapper around AnalyzingInfixSuggester that provides some missing methods and adds a few features like duplicate
elimination.

## UpdateDocValuesProcessor

UpdateDocValuesProcessor is a Solr UpdateRequestProcessor that updates NumericDocValues fields.