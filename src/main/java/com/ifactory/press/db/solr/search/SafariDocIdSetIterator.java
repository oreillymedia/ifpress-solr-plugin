package com.ifactory.press.db.solr.search;

import java.io.IOException;
import java.util.BitSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;


/**
 *
 * @author rivey
 */
public class SafariDocIdSetIterator extends DocIdSetIterator {

    private final Scorer childScorer;
    private final BitSet parentBits;
    private final Bits acceptDocs;
    private int parentDoc = -1;
    private int prevParentDoc;
    private int totalFreq;
    private int nextChildDoc;
    private int maxScoringDoc;
    private float maxScore;

    public SafariDocIdSetIterator(Weight weight, Scorer childScorer, BitSet parentBits, int firstChildDoc, Bits acceptDocs) {
        //super(weight);
        //System.out.println("Q.init firstChildDoc=" + firstChildDoc);
        this.parentBits = parentBits;
        this.childScorer = childScorer;
        this.acceptDocs = acceptDocs;
        nextChildDoc = firstChildDoc;
    }

    @Override
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
                    nextChildDoc = childScorer.iterator().nextDoc();
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
                nextChildDoc = childScorer.iterator().nextDoc();  // rivey added iterator
            } while (nextChildDoc <= parentDoc);

            //System.out.println("  return parentDoc=" + parentDoc + " childDocUpto=" + childDocUpto);
            return maxScoringDoc;
        }
    }

    @Override
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

        prevParentDoc = parentBits.previousSetBit(parentTarget - 1);

        //System.out.println("  rolled back to prevParentDoc=" + prevParentDoc + " vs parentDoc=" + parentDoc);
        assert prevParentDoc >= parentDoc;
        if (prevParentDoc > nextChildDoc) {
            nextChildDoc = childScorer.iterator().advance(prevParentDoc);  // rivey added iterator advance pushed into composition
            // System.out.println("  childScorer advanced to child docID=" + nextChildDoc);
            //} else {
            //System.out.println("  skip childScorer advance");
        }

        final int nd = nextDoc();
        //System.out.println("  return nextParentDoc=" + nd);
        return nd;
    }

    @Override
    public int docID() {
        return maxScoringDoc;
    }

    @Override
    public long cost() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

}
