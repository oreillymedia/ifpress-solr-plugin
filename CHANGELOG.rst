ifpress-solr-plugin CHANGELOG
==================
* 1.5.0

  - Upgrade to Solr v6.6.5
  - **Removed SafariBlockJoinQuery and ScoringParentQParser.** They were replaced with field collapsing for SPIDR-962
  - Update to UpdateDocValuesProcessor:
    - SolrIndexSearcher's getLeafReader() replaced with getSlowAtomicReader(), which is not recommend to use.
    Instead, leafContexts are used instead to get the leaf reader, only calling getSlowAtomicReader() if necessary.
  - Change to SafariQueryParserTest to handle an update to SafariQueryParser's super class ExtendedDismaxQParser:
    - SafariQueryParser now wraps BooleanQueries with an additional Occur.MUST (+) clause.
    Lucene 6.5.0 brought in this change: https://issues.apache.org/jira/browse/SOLR-9185
    The whitespace change that this ticket focuses on does not directly change anything in SafariQueryParser.

    The condition statements in QueryParser.java lines 232-238 were changed, which causes the extra Boolean clause.
    This change should not have an impact on SafariQueryParser (wrapping original result with an extra MUST boolean clause
    does not change functionality) and it is impossible to override because QueryParser.java is such a deep, internal class.

    Call stack for this change:
      ExtendedDismaxQParser.parse() > ExtendedDismaxQParser.parseOriginalQuery() > SolrQueryParserBaseClass.parse()
      > QueryParser.TopLevelQuery() > QueryParser.Query()


* 1.4.0

  - Upgrade to Solr v5.5.5. All changes in this version were necessary for Solr v5.5.5 compatibility.
  - solrconfig.xml:
    - Disabled updateLog because of issue introduced with TransactionLog.
    https://issues.apache.org/jira/browse/SOLR-8866
    TransactionLog's resolver now throws an exception (previous versions it would return the object) for Lucene's TextField:
    "org.apache.solr.common.SolrException: TransactionLog doesn't know how to
    serialize class org.apache.lucene.document.TextField; try implementing ObjectResolver?"
    The ONLY way around this is to make TextField implement ObjectResolver, but TextField is a Lucene internal class.
    AND we cannot create a custom class that Extends TextField (and Implements ObjectResolver)
    because TextField is marked final (cannot create subclasses of final classes).
    - AdminHandlers no longer needed to be defined (redundant).
  - schema.xml: EdgeNGram's 'side=front' functionality was removed
  - HitCount.java: parse method handles Query.extractTerms being replaced with Weight.extractTerms
  - MultiSuggester.java: commit method now uses BytesRefBuilder instead of BytesRef
  - SafariInfixSuggester.java: No longer need to specify the Lucene version unless want to use a different version than current.
  - ScoringParentQParser.java (changes were similar to the parser used for Lucene's ToParentBlockJoin):
    - Can no longer use FixedBitSetCachingWrapperFilter, QueryBitSetProducer recommended to use instead.
    - FixedBitSet, BitDocIdSet, and Filter classes went through major class inheritance change.
    - Created a BitDocIdSetFilterWrapper to be able to use QueryBitSetProducer with filtering.
  - SafariBlockJoinQuery.java:
    - Changes to Explanations.
    - Iterator methods needed wrapped in an Iterator class.
    - A custom TwoPhaseIterator needed for filtering
    - Uses getLiveDocs() to check for deleted docs since acceptDocs can no longer be used.
  - SafariBlockJoinTest.java: Small issue with filter query on block join, not sure if even possible to resolve.
  SBJQ used to check 'acceptDocs' to make sure parent doc was not deleted or filtered out.
  Lucene v5.3.0 includes a huge rework on 'acceptDocs' for LUCENE-6553, removing 'acceptDocs' from Weight class.
  SBJQ no longer has access to 'acceptDocs'. It was forced to use reader.getLiveDocs() instead to make sure the parent
  docs were not deleted.
  Because of this, SBJQ can filter out children of deleted parent docs (see testOrphanedDocs) but does not filter out
  children of parents who do not meet filter query criteria.


* 1.3.10

  - Provides more meaningful query explanations to SafariBlockJoinQuery


* 1.3.9

  - Was the latest version used 2015 - June 2018
  - Adds checks for ArrayIndexOutOfBoundsException in SBJQ
