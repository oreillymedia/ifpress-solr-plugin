package com.ifactory.press.db.solr.search;

import java.io.IOException;
import java.util.*;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.FixedBitSet;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

/**
 * Derived from the standard Lucene (parent) block join ((by copy-paste, because the class structure doesn't
 * allow for easy overriding), allowing the parent to be its own child, and
 * returning the top-scoring child (or the parent, if it is top-scorer) as the representative of the
 * group formed by the join, rather than always returning the parent.
 *
 * The other main difference to Lucene's TPBJQ is that externally-applied filters (like Solr's fq) filter
 * both child *and* parent docs.  In Lucene's version of this query, filters apply only to the parent docs.
 *
 * @see ToParentBlockJoinQuery
 */

public class SafariBlockJoinQuery extends Query {

  private final QueryBitSetProducer parentsFilter;
  private final Query childQuery;

  // If we are rewritten, this is the original childQuery we
  // were passed; we use this for .equals() and
  // .hashCode().  This makes rewritten query equal the
  // original, so that user does not have to .rewrite() their
  // query before searching:
  private final Query origChildQuery;

  /** Create a ToParentBlockJoinQuery.
   *
   * @param childQuery Query matching child documents.
   * @param parentsFilter BitSetProducer
   * identifying the parent documents (uses a FixedBitSet).
   **/
  public SafariBlockJoinQuery(Query childQuery, QueryBitSetProducer parentsFilter) {
    super();
    this.origChildQuery = childQuery;
    this.childQuery = childQuery;
    this.parentsFilter = parentsFilter;
  }

  private SafariBlockJoinQuery(Query origChildQuery, Query childQuery, QueryBitSetProducer parentsFilter) {
    super();
    this.origChildQuery = origChildQuery;
    this.childQuery = childQuery;
    this.parentsFilter = parentsFilter;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
    return new BlockJoinWeight(this, childQuery.createWeight(searcher, needsScores), parentsFilter);
  }


  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    if (this.getBoost() != 1.0F) {
      return super.rewrite(reader);
    } else {
      Query childRewrite = this.childQuery.rewrite(reader);
      return (childRewrite != this.childQuery ? new SafariBlockJoinQuery(this.origChildQuery, childRewrite, this.parentsFilter) : super.rewrite(reader));
    }
  }

  @Override
  public String toString(String field) {
    return "SafariBlockJoinQuery ("+childQuery.toString()+")";
  }

  @Override
  public boolean equals(Object _other) {
    if (_other instanceof SafariBlockJoinQuery) {
      final SafariBlockJoinQuery other = (SafariBlockJoinQuery) _other;
      return origChildQuery.equals(other.origChildQuery) &&
              parentsFilter.equals(other.parentsFilter) &&
              super.equals(other);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int hash = super.hashCode();
    hash = prime * hash + origChildQuery.hashCode();
    hash = prime * hash + parentsFilter.hashCode();
    return hash;
  }

  private static class BlockJoinWeight extends Weight {
    private final Weight childWeight;
    private final QueryBitSetProducer parentsFilter;

    public BlockJoinWeight(Query joinQuery, Weight childWeight, QueryBitSetProducer parentsFilter) {
      super(joinQuery);
      this.childWeight = childWeight;
      this.parentsFilter = parentsFilter;
    }

    @Override
    public float getValueForNormalization() throws IOException {
      return childWeight.getValueForNormalization();
    }

    @Override
    public void normalize(float norm, float topLevelBoost) {
      childWeight.normalize(norm, topLevelBoost);
    }

    @Override
    public Scorer scorer(LeafReaderContext readerContext) throws IOException {

      final Scorer childScorer = childWeight.scorer(readerContext);
      if (childScorer == null) {
        // No matches
        return null;
      }

      final int firstChildDoc = childScorer.iterator().nextDoc();
      if (firstChildDoc == NO_MORE_DOCS) {
        // No matches
        return null;
      }

      // NOTE: Filter class was completely changed as of Lucene v5.5.5. FixedBitSet no longer extends BitDocIdSet
      final BitSet parents = parentsFilter.getBitSet(readerContext);

      if (!(parents instanceof FixedBitSet)) {
        throw new IllegalStateException("parentFilter must return FixedBitSet; got " + parents);
      }

      if (parents == null) {
        // No matches
        return null;
      }
      return new BlockJoinScorer(this, childScorer, (FixedBitSet)parents, firstChildDoc, readerContext);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
      Explanation childExplanation;
      Explanation baseExplanation = childWeight.explain(context, doc);
      BlockJoinScorer scorer = (BlockJoinScorer) scorer(context);
      if (scorer != null && scorer.iterator().advance(doc) == doc) {
        Explanation matchExplanation = scorer.explain(context.docBase);
        childExplanation = Explanation.match(matchExplanation.getValue(), matchExplanation.getDescription(), baseExplanation);
      } else {
        childExplanation = Explanation.noMatch("Not a match", baseExplanation);
      }
      return childExplanation;
    }

    @Override
    public void extractTerms(Set<Term> terms) {
      this.childWeight.extractTerms(terms);
    }
  }

  static class BlockJoinScorer extends Scorer {
    private final Scorer childScorer;
    private final FixedBitSet parentBits;
    private LeafReaderContext readerContext;
    private int parentDoc = -1;
    private int prevParentDoc;
    private int totalFreq;
    private int nextChildDoc;
    private int maxScoringDoc;
    private float maxScore;

    public BlockJoinScorer(Weight weight, Scorer childScorer, FixedBitSet parentBits, int firstChildDoc, LeafReaderContext readerContext) {
      super(weight);
      this.parentBits = parentBits;
      this.childScorer = childScorer;
      this.nextChildDoc = firstChildDoc;
      this.readerContext = readerContext;
    }

    @Override
    public Collection<ChildScorer> getChildren() {
      return Collections.singleton(new ChildScorer(childScorer, "BLOCK_JOIN"));
    }

    @Override
    public int docID() {
      return parentDoc;
    }

    @Override
    public float score() throws IOException {
      return maxScore;
    }

    @Override
    public int freq() {
      return totalFreq;
    }

    public Explanation explain(int docBase) throws IOException {
      int start = docBase + prevParentDoc + 1; // +1 b/c prevParentDoc is previous parent doc
      int end = docBase + parentDoc - 1; // -1 b/c parentDoc is parent doc
      return Explanation.match(
              score(),
              String.format(Locale.ROOT, "Score based on child doc range from %d to %d:", start, end)
      );
    }

    // Lucene v5.5.5 Scorer requires iterator methods wrapped in a DocIdSetIterator method
    public DocIdSetIterator iterator() {
      return new SafariBlockJoinDocIdSetIterator();
    }

    @Override
    public TwoPhaseIterator twoPhaseIterator() {
      // Primarily used for filtering
      return new TwoPhaseIterator(iterator()) {
        @Override
        public boolean matches() throws IOException {
          return parentDoc == -1 ? false : parentBits.get(parentDoc);
        }

        @Override
        public float matchCost() {
          return this.approximation.cost();
        }
      };
    }

    public class SafariBlockJoinDocIdSetIterator extends DocIdSetIterator {
      final DocIdSetIterator childIterator;

      public SafariBlockJoinDocIdSetIterator() {
        this.childIterator = childScorer.iterator();
      }

      @Override
      public int nextDoc() throws IOException {
        // Loop until we hit a parentDoc that is accepted
        while(true) {
          if (nextChildDoc == NO_MORE_DOCS) {
            return parentDoc = NO_MORE_DOCS;
          }

          // Gather all children sharing the same parent as nextChildDoc
          parentDoc = parentBits.nextSetBit(nextChildDoc);
          if(parentDoc == NO_MORE_DOCS) {
            return parentDoc;
          }

          // parentDoc should intentionally never be -1. If it is, investigate why.
          assert parentDoc != -1;

          // LUCENE-6553: Lucene v5.3.0 completely reworked 'acceptDocs'. Now need to rely on getLiveDocs() to have
          // access to deleted docs. Do not accept parentDoc if it was deleted, skip all of its children.
          Bits liveDocs = readerContext.reader().getLiveDocs();
          if ((liveDocs != null && !liveDocs.get(parentDoc))) {
            // Parent doc not accepted; skip child docs until we hit a new parent doc:
            do {
              nextChildDoc = childIterator.nextDoc();
            } while (nextChildDoc <= parentDoc);
            continue;
          }

          maxScore = Float.NEGATIVE_INFINITY;
          totalFreq = 0;
          do {
            final int childFreq = childScorer.freq();
            final float childScore = childScorer.score();
            if (childScore > maxScore) {
              maxScore = childScore;
              maxScoringDoc = nextChildDoc;
            }
            totalFreq += childFreq;
            nextChildDoc = childIterator.nextDoc();
          } while (nextChildDoc <= parentDoc);
          return maxScoringDoc;
        }
      }

      @Override
      public int advance(int parentTarget) throws IOException {
        if (parentTarget == NO_MORE_DOCS) {
          return parentDoc = NO_MORE_DOCS;
        }

        if (parentTarget == 0) {
          // Callers should only be passing in a docID from
          // the parent space, so this means this parent
          // has no children (it got docID 0), so it cannot
          // possibly match.  We must handle this case
          // separately otherwise we pass invalid -1 to
          // prevSetBit below:
          return nextDoc();
        }

        prevParentDoc = parentBits.prevSetBit(parentTarget - 1);
        assert prevParentDoc >= parentDoc;
        if (prevParentDoc > nextChildDoc) {
          nextChildDoc = childIterator.advance(prevParentDoc);
        }

        final int nd = nextDoc();
        return nd;
      }

      @Override
      public int docID() {
        return maxScoringDoc;
      }

      @Override
      public long cost() {
        return childIterator.cost();
      }
    }
  }
}
