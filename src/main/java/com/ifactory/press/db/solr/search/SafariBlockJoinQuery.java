package com.ifactory.press.db.solr.search;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.solr.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;

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

    private final Filter parentsFilter;
    private final Query childQuery;

    // If we are rewritten, this is the original childQuery we
    // were passed; we use this for .equals() and
    // .hashCode().  This makes rewritten query equal the
    // original, so that user does not have to .rewrite() their
    // query before searching:
    private final Query origChildQuery;

    /**
     * Create a ToParentBlockJoinQuery.
     *
     * @param childQuery Query matching child documents.
     * @param parentsFilter Filter (must produce FixedBitSet per-segment, like
     * {@link QueryBitSetProducer}) identifying the parent documents.
     * @param scoreMode How to aggregate multiple child scores into a single
     * parent score.
     *
     */
    public SafariBlockJoinQuery(Query childQuery, Filter parentsFilter) {
        super();
        this.origChildQuery = childQuery;
        this.childQuery = childQuery;
        this.parentsFilter = parentsFilter;
    }

    private SafariBlockJoinQuery(Query origChildQuery, Query childQuery, Filter parentsFilter) {
        super();
        this.origChildQuery = origChildQuery;
        this.childQuery = childQuery;
        this.parentsFilter = parentsFilter;
    }

    //@Override  rfhi 
    public Weight createWeight(IndexSearcher searcher) throws IOException {
        //return new BlockJoinWeight(this, childQuery.createWeight(searcher), parentsFilter);
        return new BlockJoinWeight(this, childQuery.createWeight(searcher, true, 0.8f), parentsFilter);  // rfhi createWeight was createWeight(searcher)
    }

    private static class BlockJoinWeight extends Weight {

        private final Query joinQuery;
        private final Weight childWeight;
        private final Filter parentsFilter;

        public BlockJoinWeight(Query joinQuery, Weight childWeight, Filter parentsFilter) {
            super(joinQuery);  //super() rfhi
            this.joinQuery = joinQuery;
            this.childWeight = childWeight;
            this.parentsFilter = parentsFilter;
        }

        /* @Override   // rfhi
        public Query getQuery() {
            return joinQuery;
        } */

        @Override
        public float getValueForNormalization() throws IOException {
            return childWeight.getValueForNormalization() * joinQuery.getBoost() * joinQuery.getBoost();
        }

        @Override
        public void normalize(float norm, float topLevelBoost) {
            childWeight.normalize(norm, topLevelBoost * joinQuery.getBoost());
        }

        // NOTE: unlike Lucene's TPBJQ, acceptDocs applies to *both* child and parent documents
        @Override
        public Scorer scorer(LeafReaderContext readerContext, Bits acceptDocs) throws IOException {

            //final Scorer childScorer = childWeight.scorer(readerContext, acceptDocs);
            final Scorer childScorer = childWeight.scorer(readerContext); // accept docs need to go in
            if (childScorer == null) {
                // No matches
                return null;
            }

            final int firstChildDoc = childScorer.nextDoc();
            if (firstChildDoc == DocIdSetIterator.NO_MORE_DOCS) {
                // No matches
                return null;
            }

            // NOTE: we cannot pass acceptDocs here because this
            // will (most likely, justifiably) cause the filter to
            // not return a FixedBitSet but rather a
            // BitsFilteredDocIdSet.  Instead, we filter by
            // acceptDocs when we score:
            final DocIdSet parents = parentsFilter.getDocIdSet(readerContext, null);

            if (parents == null) {
                // No matches
                return null;
            }
            if (!(parents.bits() instanceof FixedBitSet)) {
                throw new IllegalStateException("parentFilter must return FixedBitSet; got " + parents);
            }

            return new BlockJoinScorer(this, childScorer,  (FixedBitSet)parents.bits(), firstChildDoc, acceptDocs); // (FixedBitSet)parents  rfhi
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            BlockJoinScorer scorer = (BlockJoinScorer) scorer(context, context.reader().getLiveDocs());
            if (scorer != null && scorer.advance(doc) == doc) {
                return scorer.explain(context.docBase);
            }
            return Explanation.noMatch("Not a match");
        }

        //@Override
        public boolean scoresDocsOutOfOrder() {
            return false;
        }

        @Override
        public void extractTerms(Set<Term> set) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Scorer scorer(LeafReaderContext lrc) throws IOException {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }

    static class BlockJoinScorer extends Scorer {

        private final Scorer childScorer;
        private final FixedBitSet parentBits;
        private final Bits acceptDocs;
        private int parentDoc = -1;
        private int prevParentDoc;
        private int totalFreq;
        private int nextChildDoc;
        private int maxScoringDoc;
        private float maxScore;

        public BlockJoinScorer(Weight weight, Scorer childScorer, FixedBitSet parentBits, int firstChildDoc, Bits acceptDocs) {
            super(weight);
            //System.out.println("Q.init firstChildDoc=" + firstChildDoc);
            this.parentBits = parentBits;
            this.childScorer = childScorer;
            this.acceptDocs = acceptDocs;
            nextChildDoc = firstChildDoc;
        }

        @Override
        public Collection<ChildScorer> getChildren() {
            return Collections.singleton(new ChildScorer(childScorer, "BLOCK_JOIN"));
        }

        int getParentDoc() {
            return parentDoc;
        }

        // rfhi took out override, is this needed?
        public int nextDoc() throws IOException {
            //System.out.println("Q.nextDoc() nextChildDoc=" + nextChildDoc);
            // Loop until we hit a parentDoc that's accepted
            while (true) {
                if (nextChildDoc == DocIdSetIterator.NO_MORE_DOCS) {
                    //System.out.println("  end");
                    return parentDoc = DocIdSetIterator.NO_MORE_DOCS;
                }

                // Gather all children sharing the same parent as
                // nextChildDoc
                parentDoc = parentBits.nextSetBit(nextChildDoc);

                //System.out.println("  parentDoc=" + parentDoc);
                assert parentDoc != -1;

                //System.out.println("  nextChildDoc=" + nextChildDoc);
                if ((acceptDocs != null && !acceptDocs.get(parentDoc))
                        // shouldn't happen, but it did.  I'm not sure if this is a consequence of our allowing 
                        // parents to be a child -- I don't think so -- it seems more likely the index can just get in 
                        // a state where there are children with no parent, and that could cause this?
                        || parentDoc == -1) {
                    // Parent doc not accepted; skip child docs until
                    // we hit a new parent doc:
                    do {
                        nextChildDoc = childScorer.iterator().nextDoc(); // rfhi added .iterator()
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
                    nextChildDoc = childScorer.iterator().nextDoc();
                } while (nextChildDoc <= parentDoc);

                //System.out.println("  return parentDoc=" + parentDoc + " childDocUpto=" + childDocUpto);
                return maxScoringDoc;
            }
        }

        @Override
        public int docID() {
            return maxScoringDoc;
        }

        @Override
        public float score() throws IOException {
            return maxScore;
        }

        @Override
        public int freq() {
            return totalFreq;
        }

        // @Override  rfhi took out override, is this needed?  This may not be called if some other method is called instead
        public int advance(int parentTarget) throws IOException {

            //System.out.println("Q.advance parentTarget=" + parentTarget);
            if (parentTarget == DocIdSetIterator.NO_MORE_DOCS) {
                return parentDoc = DocIdSetIterator.NO_MORE_DOCS;
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

            //System.out.println("  rolled back to prevParentDoc=" + prevParentDoc + " vs parentDoc=" + parentDoc);
            assert prevParentDoc >= parentDoc;
            if (prevParentDoc > nextChildDoc) {
                nextChildDoc = childScorer.iterator().advance(prevParentDoc);
                // System.out.println("  childScorer advanced to child docID=" + nextChildDoc);
                //} else {
                //System.out.println("  skip childScorer advance");
            }

            final int nd = nextDoc();
            //System.out.println("  return nextParentDoc=" + nd);
            return nd;
        }

        public Explanation explain(int docBase) throws IOException {
            int start = docBase + prevParentDoc + 1; // +1 b/c prevParentDoc is previous parent doc
            int end = docBase + parentDoc - 1; // -1 b/c parentDoc is parent doc
            return Explanation.match(
                    score(), String.format(Locale.ROOT, "Score based on child doc range from %d to %d", start, end)
            );
        }

        
        public long cost() {
            return childScorer.iterator().cost();  // rfhi added iterator
        }

        @Override
        public DocIdSetIterator iterator() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

    }

    //@Override rfhi more extract terms to check out  IndexSearcher and Float
    public void extractTerms(Set<Term> terms) {
        Weight w = null;
        try {
            w = childQuery.createWeight(null, true, 0.8f);
        } catch (IOException ex) {
            Logger.getLogger(SafariBlockJoinQuery.class.getName()).log(Level.SEVERE, null, ex);
        }
        w.extractTerms(terms);
    }

    @Override
    public Query rewrite(IndexReader reader) throws IOException {
        final Query childRewrite = childQuery.rewrite(reader);
        if (childRewrite != childQuery) {
            Query rewritten = new SafariBlockJoinQuery(origChildQuery,
                    childRewrite,
                    parentsFilter);
            rewritten.setBoost(getBoost());
            return rewritten;
        } else {
            return this;
        }
    }

    @Override
    public String toString(String field) {
        return "ToParentBlockJoinQuery (" + childQuery.toString() + ")";
    }

    @Override
    public boolean equals(Object _other) {
        if (_other instanceof SafariBlockJoinQuery) {
            final SafariBlockJoinQuery other = (SafariBlockJoinQuery) _other;
            return origChildQuery.equals(other.origChildQuery)
                    && parentsFilter.equals(other.parentsFilter);
                    //&& super.equals(other); //rfhi no super call - abstract //TODO
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int hash = 1;  // rfhi this is abstract to begin with, so can't call super
        hash = prime * hash + origChildQuery.hashCode();  // rfhi should we add or mult?
        hash = prime * hash + parentsFilter.hashCode();
        return hash;
    }
}
