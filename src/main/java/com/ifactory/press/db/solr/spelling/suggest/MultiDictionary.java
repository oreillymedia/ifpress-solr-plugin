package com.ifactory.press.db.solr.spelling.suggest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.search.spell.Dictionary;
import org.apache.lucene.search.spell.HighFrequencyDictionary;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.util.BytesRef;

import com.ifactory.press.db.solr.spelling.suggest.SafeInfixSuggester.Context;

/**
 * MultiDictionary: terms are taken from a number of wrapped
 * {@link HighFrequencyDictionary}'s, each of which has distinct thresholds and
 * weight. The terms include only those whose weight lies between that
 * dictionary's minWeight and maxWeight (inclusive of the endpoints), and each
 * term's weight is multiplied by the weight of its dictionary.
 */
public class MultiDictionary implements Dictionary {

  private final List<WeightedDictionary> dicts;
  
  private final int maxTermLength;

  public MultiDictionary(int maxTermLength) {
    dicts = new ArrayList<WeightedDictionary>();
    this.maxTermLength = maxTermLength;
  }

  public void addDictionary(Dictionary dict, long minWeight, long maxWeight, float weight) {
    dicts.add(new WeightedDictionary(dict, minWeight, maxWeight, weight));
  }

  @Override
  public InputIterator getEntryIterator() throws IOException {
    return new MultiInputIterator();
  }

  public static String stripAfflatus(String s) {
    // strip off non-letters and digits (incl. ideographics and all surrogates)
    int i = 0;
    int length = s.length();
    if (length == 0) {
      return s;
    }
    for (; i < length; i++) {
      char c = s.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        break;
      }
    }
    int j = length - 1;
    for (; j > i; j--) {
      char c = s.charAt(j);
      if (Character.isLetterOrDigit(c)) {
        break;
      }
    }
    if (j - i == length) {
      return s;
    }
    return s.substring(i, j + 1);
  }

  final static class WeightedDictionary {
    final long minWeight;
    final long maxWeight;
    final float weight;
    final Dictionary dict;

    WeightedDictionary(Dictionary dict, long minWeight, long maxWeight, float weight) {
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
    private BytesRef scratch; // TODO: move to BytesRefBuilder as we upgrade
    private Set<BytesRef> contexts;

    public MultiInputIterator() throws IOException {
      cur = -1;
      scratch = new BytesRef(maxTermLength);
      nextDict();
    }

    private WeightedDictionary nextDict() throws IOException {
      if (++cur < dicts.size()) {
        curDict = dicts.get(cur);
        curInput = curDict.dict.getEntryIterator();
        if (curInput.hasContexts()) {
          contexts = new HashSet<BytesRef> (curInput.contexts());
          contexts.add(new BytesRef(new byte[] { (byte) Context.SHOW.ordinal() }));
        } else {
          contexts =  Collections.singleton(new BytesRef(new byte[] { (byte) Context.SHOW.ordinal() }));
        }
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
        if (curInput == null) {
          return null;
        }
        nextTerm = curInput.next();
        if (nextTerm == null) {
          nextDict();
          continue;
        }
        // check thresholds - note that the minWeight test may be redundant with
        // HighFrequencyDictionary's
        // threshold; a possible performance optimization would be to create a
        // specialized version of this
        // class for use w/HFD that skips that test...
        if (curInput.weight() > curDict.maxWeight || curInput.weight() < curDict.minWeight) {
          continue;
        }
        break;
      }
      return stripAfflatus(nextTerm);
    }

    private BytesRef stripAfflatus(BytesRef nextTerm) {
      // strip off non-letters and digits (incl. ideographics and all
      // surrogates)
      String s0 = nextTerm.utf8ToString();
      String s = MultiDictionary.stripAfflatus(s0);
      if (s == s0) {
        return nextTerm;
      }
      scratch.copyChars(s);
      return scratch;
    }

    @Override
    public long weight() {
      // TODO possible performance optimization would avoid this when weight ==
      // 1.0?
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
      return contexts;
    }

    @Override
    public boolean hasContexts() {
      return true;
    }

  }

}
