package com.ifactory.press.db.solr.search;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FilterWeight;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.util.BitSet;

/**
 * Derived from the standard Lucene (parent) block join ((by copy-paste, because
 * the class structure doesn't allow for easy overriding), allowing the parent
 * to be its own child, and returning the top-scoring child (or the parent, if
 * it is top-scorer) as the representative of the group formed by the join,
 * rather than always returning the parent.
 *
 * The other main difference to Lucene's TPBJQ is that externally-applied
 * filters (like Solr's fq) filter both child *and* parent docs. In Lucene's
 * version of this query, filters apply only to the parent docs.
 *
 * @see ToParentBlockJoinQuery
 */
public class SafariBlockJoinQuery extends Query {

    private final BitSetProducer parentsFilter;
    private final Query childQuery;
    private final ScoreMode scoreMode;
    
    public SafariBlockJoinQuery(Query childQuery, BitSetProducer parentsFilter, ScoreMode scoreMode) {
        super();
        this.childQuery = childQuery;
        this.parentsFilter = parentsFilter;
        this.scoreMode = scoreMode;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
        return new SafariBlockJoinQuery.BlockJoinWeight(this, childQuery.createWeight(searcher, needsScores), parentsFilter, needsScores ? scoreMode : ScoreMode.None);
    }

    private static class ParentApproximation extends DocIdSetIterator {

        private final DocIdSetIterator childApproximation;
        private final BitSet parentBits;
        private int doc = -1;

        ParentApproximation(DocIdSetIterator childApproximation, BitSet parentBits) {
            this.childApproximation = childApproximation;
            this.parentBits = parentBits;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            if (target >= parentBits.length()) {
                return doc = NO_MORE_DOCS;
            }
            final int firstChildTarget = target == 0 ? 0 : parentBits.prevSetBit(target - 1) + 1;
            int childDoc = childApproximation.docID();
            if (childDoc < firstChildTarget) {
                childDoc = childApproximation.advance(firstChildTarget);
            }
            if (childDoc >= parentBits.length() - 1) {
                return doc = NO_MORE_DOCS;
            }
            return doc = parentBits.nextSetBit(childDoc + 1);
        }

        @Override
        public long cost() {
            return childApproximation.cost();
        }
    }

    private static class ParentTwoPhase extends TwoPhaseIterator {

        private final SafariBlockJoinQuery.ParentApproximation parentApproximation;
        private final DocIdSetIterator childApproximation;
        private final TwoPhaseIterator childTwoPhase;

        ParentTwoPhase(SafariBlockJoinQuery.ParentApproximation parentApproximation, TwoPhaseIterator childTwoPhase) {
            super(parentApproximation);
            this.parentApproximation = parentApproximation;
            this.childApproximation = childTwoPhase.approximation();
            this.childTwoPhase = childTwoPhase;
        }

        @Override
        public boolean matches() throws IOException {
            assert childApproximation.docID() < parentApproximation.docID();
            do {
                if (childTwoPhase.matches()) {
                    return true;
                }
            } while (childApproximation.nextDoc() < parentApproximation.docID());
            return false;
        }

        @Override
        public float matchCost() {
            // TODO: how could we compute a match cost?
            return childTwoPhase.matchCost() + 10;
        }
    }

    private static class BlockJoinWeight extends FilterWeight {

        private final BitSetProducer parentsFilter;
        private final ScoreMode scoreMode;

        public BlockJoinWeight(Query joinQuery, Weight childWeight, BitSetProducer parentsFilter, ScoreMode scoreMode) {
            super(joinQuery, childWeight);
            this.parentsFilter = parentsFilter;
            this.scoreMode = scoreMode;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            final ScorerSupplier scorerSupplier = scorerSupplier(context);
            if (scorerSupplier == null) {
                return null;
            }
            return scorerSupplier.get(false);
        }

        // NOTE: acceptDocs applies (and is checked) only in the
        // parent document space
        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
            final ScorerSupplier childScorerSupplier = in.scorerSupplier(context);
            if (childScorerSupplier == null) {
                return null;
            }

            // NOTE: this does not take accept docs into account, the responsibility
            // to not match deleted docs is on the scorer
            final BitSet parents = parentsFilter.getBitSet(context);
            if (parents == null) {
                // No matches
                return null;
            }

            return new ScorerSupplier() {

                @Override
                public Scorer get(boolean randomAccess) throws IOException {
                    return new SafariBlockJoinQuery.BlockJoinScorer(SafariBlockJoinQuery.BlockJoinWeight.this, childScorerSupplier.get(randomAccess), parents, scoreMode);
                }

                @Override
                public long cost() {
                    return childScorerSupplier.cost();
                }
            };
        }

       @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            ToSafParentBlockJoinQuery.BlockJoinScorer scorer = (ToSafParentBlockJoinQuery.BlockJoinScorer) scorer(context);
            if (scorer != null && scorer.iterator().advance(doc) == doc) {
                return scorer.explain(context, in);
            }
            return Explanation.noMatch("Not a match");
        }


    }

    

    

    static class BlockJoinScorer extends Scorer {

        private final Scorer childScorer;
        private final BitSet parentBits;
        private final ScoreMode scoreMode;
        private final DocIdSetIterator childApproximation;
        private final TwoPhaseIterator childTwoPhase;
        private final SafariBlockJoinQuery.ParentApproximation parentApproximation;
        private final SafariBlockJoinQuery.ParentTwoPhase parentTwoPhase;
        private float score;
        private int freq;

        public BlockJoinScorer(Weight weight, Scorer childScorer, BitSet parentBits, ScoreMode scoreMode) {
            super(weight);
            //System.out.println("Q.init firstChildDoc=" + firstChildDoc);
            this.parentBits = parentBits;
            this.childScorer = childScorer;
            this.scoreMode = scoreMode;
            childTwoPhase = childScorer.twoPhaseIterator();
            if (childTwoPhase == null) {
                childApproximation = childScorer.iterator();
                parentApproximation = new SafariBlockJoinQuery.ParentApproximation(childApproximation, parentBits);
                parentTwoPhase = null;
            } else {
                childApproximation = childTwoPhase.approximation();
                parentApproximation = new SafariBlockJoinQuery.ParentApproximation(childTwoPhase.approximation(), parentBits);
                parentTwoPhase = new SafariBlockJoinQuery.ParentTwoPhase(parentApproximation, childTwoPhase);
            }
        }

        @Override
        public Collection<Scorer.ChildScorer> getChildren() {
            return Collections.singleton(new Scorer.ChildScorer(childScorer, "BLOCK_JOIN"));
        }

        @Override
        public DocIdSetIterator iterator() {
            if (parentTwoPhase == null) {
                // the approximation is exact
                return parentApproximation;
            } else {
                return TwoPhaseIterator.asDocIdSetIterator(parentTwoPhase);
            }
        }

        @Override
        public TwoPhaseIterator twoPhaseIterator() {
            return parentTwoPhase;
        }

        @Override
        public int docID() {
            return parentApproximation.docID();
        }

        @Override
        public float score() throws IOException {
            setScoreAndFreq();
            return score;
        }

        @Override
        public int freq() throws IOException {
            setScoreAndFreq();
            return freq;
        }

        private void setScoreAndFreq() throws IOException {
            if (childApproximation.docID() >= parentApproximation.docID()) {
                return;
            }
            double score = scoreMode == ScoreMode.None ? 0 : childScorer.score();
            int freq = 1;
            while (childApproximation.nextDoc() < parentApproximation.docID()) {
                if (childTwoPhase == null || childTwoPhase.matches()) {
                    final float childScore = childScorer.score();
                    freq += 1;
                    switch (scoreMode) {
                        case Total:
                        case Avg:
                            score += childScore;
                            break;
                        case Min:
                            score = Math.min(score, childScore);
                            break;
                        case Max:
                            score = Math.max(score, childScore);
                            break;
                        case None:
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
            }
            if (childApproximation.docID() == parentApproximation.docID() && (childTwoPhase == null || childTwoPhase.matches())) {
                throw new IllegalStateException("Child query must not match same docs with parent filter. "
                        + "Combine them as must clauses (+) to find a problem doc. "
                        + "docId=" + parentApproximation.docID() + ", " + childScorer.getClass());
            }
            if (scoreMode == ScoreMode.Avg) {
                score /= freq;
            }
            this.score = (float) score;
            this.freq = freq;
        }

        public Explanation explain(LeafReaderContext context, Weight childWeight) throws IOException {
            int prevParentDoc = parentBits.prevSetBit(parentApproximation.docID() - 1);
            int start = context.docBase + prevParentDoc + 1; // +1 b/c prevParentDoc is previous parent doc
            int end = context.docBase + parentApproximation.docID() - 1; // -1 b/c parentDoc is parent doc

            Explanation bestChild = null;
            int matches = 0;
            for (int childDoc = start; childDoc <= end; childDoc++) {
                Explanation child = childWeight.explain(context, childDoc - context.docBase);
                if (child.isMatch()) {
                    matches++;
                    if (bestChild == null || child.getValue() > bestChild.getValue()) {
                        bestChild = child;
                    }
                }
            }

            assert freq() == matches;
            return Explanation.match(score(), String.format(Locale.ROOT,
                    "Score based on %d child docs in range from %d to %d, best match:", matches, start, end), bestChild
            );
        }
    }

    @Override
    public Query rewrite(IndexReader reader) throws IOException {
        final Query childRewrite = childQuery.rewrite(reader);
        if (childRewrite != childQuery) {
            return new ToSafParentBlockJoinQuery(childRewrite,
                    parentsFilter,
                    scoreMode);
        } else {
            return super.rewrite(reader);
        }
    }

    @Override
    public String toString(String field) {
        return "SafariBlockJoinQuery (" + childQuery.toString() + ")";
    }

    @Override
    public boolean equals(Object other) {
        return sameClassAs(other)
                && equalsTo(getClass().cast(other));
    }

    private boolean equalsTo(SafariBlockJoinQuery other) {
        return childQuery.equals(other.childQuery)
                && parentsFilter.equals(other.parentsFilter)
                && scoreMode == other.scoreMode;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int hash = classHash();
        hash = prime * hash + childQuery.hashCode();
        hash = prime * hash + scoreMode.hashCode();
        hash = prime * hash + parentsFilter.hashCode();
        return hash;
    }
}
