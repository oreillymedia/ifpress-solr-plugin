ifpress-solr-plugin CHANGELOG
==================
* 1,5,0
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
  - (WIP, there's a lot to put here)


* 1.3.10
  - Provides more meaningful query explanations to SafariBlockJoinQuery


* 1.3.9
  - Was the latest version used 2015 - June 2018
  - Adds checks for ArrayIndexOutOfBoundsException in SBJQ
