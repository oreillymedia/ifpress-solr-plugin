package com.ifactory.press.db.solr.analysis;

import java.io.IOException;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

/**
 * Strips tokens that consist entirely of ASCII punctuation characters.  Discards position information
 * for those tokens, acting as if they had never existed.
 */
public final class PunctuationStrippingTokenFilter extends TokenFilter {
  
  private final Pattern punctuationPattern;
  private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  private boolean first = true;
  
  protected PunctuationStrippingTokenFilter(TokenStream input) {
    super(input);
    punctuationPattern = Pattern.compile("[\\[\\]\"':;,\\.<>;/\\?\\\\\\|{}+=\\-_\\(\\)\\*&^%$#@!~`]+");
  }

  @Override
  public boolean incrementToken() throws IOException {
    while (input.incrementToken()) {
      if (accept()) {
        if (first) {
          // first token having posinc=0 is illegal.
          if (posIncrAtt.getPositionIncrement() == 0) {
            posIncrAtt.setPositionIncrement(1);
          }
          first = false;
        }
        return true;
      }
    }
    return false;
  }

  protected boolean accept () {
    return ! punctuationPattern.matcher(termAtt).matches();
  }
  
}
