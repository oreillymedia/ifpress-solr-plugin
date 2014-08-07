package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;

/**
 * MultiDictionary: terms are taken from a number of wrapped {@link HighFrequencyDictionary}'s, each of which has distinct
 * thresholds and weight. The terms include only those whose weight lies between that dictionary's 
 * minWeight and maxWeight (inclusive of the endpoints), and each term's weight 
 * is multiplied by the weight of its dictionary.
 */
public class MultiDictionary implements Dictionary {

    private final List<WeightedDictionary> dicts;
    
    public MultiDictionary () {
        dicts = new ArrayList<WeightedDictionary>();
    }
    
    public void addDictionary (Dictionary dict, long minWeight, long maxWeight, float weight) {
        dicts.add(new WeightedDictionary(dict, minWeight, maxWeight, weight));
    }

    @Override
    public InputIterator getEntryIterator() throws IOException {
        return new MultiInputIterator();
    }
    
    final static class WeightedDictionary {
        final long minWeight;
        final long maxWeight;
        final float weight;
        final Dictionary dict;
        
        WeightedDictionary (Dictionary dict, long minWeight, long maxWeight, float weight) {
            this.dict = dict;
            this.minWeight = minWeight;
            this.maxWeight = maxWeight;
            this.weight = weight;
        }
    }
    
    final class MultiInputIterator implements InputIterator {
        
        private int cur;
        private WeightedDictionary curDict;
        private InputIterator curInput;
        
        public MultiInputIterator() throws IOException {
            cur = -1;
            nextDict ();
        }
        
        private WeightedDictionary nextDict () throws IOException {
            if (++cur < dicts.size()) {
                curDict = dicts.get(cur);
                curInput = curDict.dict.getEntryIterator();
            } else {
                curDict = null;
                curInput = null;
            }
            return curDict;
        }

        @Override
        public BytesRef next() throws IOException {
            BytesRef nextTerm;
            for (;;) {
                nextTerm = curInput.next();
                if (nextTerm == null) {
                    if (nextDict() == null) {
                        return null;
                    }
                    continue;
                }
                // check thresholds - note that the minWeight test may be redundant with HighFrequencyDictionary's
                // threshold; a possible performance optimization would be to create a specialized version of this
                // class for use w/HFD that skips that test...
                if (curInput.weight() > curDict.maxWeight || curInput.weight() < curDict.minWeight) {
                    continue;
                }
                break;
            }
            return nextTerm;
        }

        @Override
        public long weight() {
            // TODO possible performance optimization would avoid this when weight == 1.0?
            return (long) (curInput.weight() * curDict.weight);
        }

        @Override
        public Comparator<BytesRef> getComparator() {
            return curInput.getComparator();
        }

        @Override
        public BytesRef payload() {
            return curInput.payload();
        }

        @Override
        public boolean hasPayloads() {
            return curInput.hasPayloads();
        }

        @Override
        public Set<BytesRef> contexts() {
            return curInput.contexts();
        }

        @Override
        public boolean hasContexts() {
            return curInput.hasContexts();
        }
        
    }

}
