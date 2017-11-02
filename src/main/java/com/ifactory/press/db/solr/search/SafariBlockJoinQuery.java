package com.ifactory.press.db.solr.search;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Filter;
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
     *
     */
    public SafariBlockJoinQuery(Query childQuery, Filter parentsFilter) { //parentsFilter changed to Query
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

    @Override
    public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
        return new BlockJoinWeight(this, childQuery.createWeight(searcher, needsScores), parentsFilter);
    }

    private static class BlockJoinWeight extends Weight {

        private final Query joinQuery;
        private final Weight childWeight;
        private final Filter parentsFilter;
        private Bits acceptDocs;

        public BlockJoinWeight(Query joinQuery, Weight childWeight, Filter parentsFilter) {
            super(joinQuery);
            this.joinQuery = joinQuery;
            this.childWeight = childWeight;
            this.parentsFilter = parentsFilter;
        }

        /* @Override    // rivey was final in superclass, cannot be overridden but can be called and does the same thing
        public Query getQuery() {  // if we have confidence then this can be deleted
            return joinQuery;
        }*/

        @Override
        public float getValueForNormalization() throws IOException {
            return childWeight.getValueForNormalization() * joinQuery.getBoost() * joinQuery.getBoost();
        }

        @Override
        public void normalize(float norm, float topLevelBoost) {
            childWeight.normalize(norm, topLevelBoost * joinQuery.getBoost());
        }

        // NOTE: unlike Lucene's TPBJQ, acceptDocs applies to *both* child and parent documents
        @Override  // rivey does this need to be pushed to a composition  
        public Scorer scorer(LeafReaderContext readerContext) throws IOException {
            
            final Scorer childScorer = childWeight.scorer(readerContext);//, acceptDocs);
            if (childScorer == null) {
                // No matches
                return null;
            }
            
            final int firstChildDoc = childScorer.iterator().nextDoc();  // rivey iterator added
            if (firstChildDoc == DocIdSetIterator.NO_MORE_DOCS) {
                // No matches
                return null;
            }

            // NOTE: we cannot pass acceptDocs here because this
            // will (most likely, justifiably) cause the filter to
            // not return a FixedBitSet but rather a
            // BitsFilteredDocIdSet.  Instead, we filter by
            // acceptDocs when we score:
            
            final DocIdSet parents = parentsFilter.getDocIdSet(readerContext, acceptDocs);

            if (parents == null) {
                // No matches
                return null;
            }
            if (!(parents.bits() instanceof FixedBitSet)) {
                throw new IllegalStateException("parentFilter must return FixedBitSet; got " + parents);
            }

            return new BlockJoinScorer(this, childScorer, (FixedBitSet) parents.bits(), firstChildDoc, acceptDocs);  // rivey //TODO Initialize AcceptDocs
        }

        /* @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            BlockJoinScorer scorer = (BlockJoinScorer) scorer(context, context.reader().getLiveDocs());
            if (scorer != null && scorer.advance(doc) == doc) {
                return scorer.explain(context.docBase);
            }
            return Explanation.noMatch("Not a match");
        } */

        /* @Override
        public boolean scoresDocsOutOfOrder() {
            return false;
        } */   //rivey - find this //TODO
        @Override
        public void extractTerms(Set<Term> set) {  // rivey // TODO   Verify this!!! 
            this.extractTerms(set);
        }

        @Override
        public Explanation explain(LeafReaderContext lrc, int i) throws IOException {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }
    }

    static class BlockJoinScorer extends Scorer {

        private final Scorer childScorer;
        
        private final Bits acceptDocs;
        private int prevParentDoc;
        private int totalFreq;
        private final int nextChildDoc;
        private int maxScoringDoc;
        private float maxScore;
        DocIdSetIterator safDocSetIterator = null;
        private final int parentDoc = -1;

        public BlockJoinScorer(Weight weight, Scorer childScorer, FixedBitSet parentBits, int firstChildDoc, Bits acceptDocs) {
            super(weight);
            //System.out.println("Q.init firstChildDoc=" + firstChildDoc);
            
            this.childScorer = childScorer;
            this.acceptDocs = acceptDocs;
            nextChildDoc = firstChildDoc;
            safDocSetIterator = new SafariDocIdSetIterator(weight, childScorer, parentBits, firstChildDoc, acceptDocs);
        }

        @Override
        public Collection<ChildScorer> getChildren() {
            return Collections.singleton(new ChildScorer(childScorer, "BLOCK_JOIN"));
        }

        int getParentDoc() {
            return parentDoc;
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

        public Explanation explain(int docBase) throws IOException {
            int start = docBase + prevParentDoc + 1; // +1 b/c prevParentDoc is previous parent doc
            int end = docBase + parentDoc - 1; // -1 b/c parentDoc is parent doc
            return Explanation.match(
                    score(), String.format(Locale.ROOT, "Score based on child doc range from %d to %d", start, end)
            );
        }

        // removed iterator so this may be needed somewhere else //TODO 
        public long cost() {
            return childScorer.iterator().cost();
        }

        @Override  // rivey - iterator method added here
        public DocIdSetIterator iterator() {
            return safDocSetIterator;  
        }

    }

    /* @Override  // rivey - not called but VERIFY THIS
    public void extractTerms(Set<Term> terms) {
        childQuery.extractTerms(terms);
    } */

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
                    && parentsFilter.equals(other.parentsFilter)
                    && super.equals(other);
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
}
