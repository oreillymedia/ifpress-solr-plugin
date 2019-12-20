package com.ifactory.press.db.solr.highlight;

import java.util.Arrays;
import java.util.Comparator;

import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.Passage;

public class HighlightFormatter extends DefaultPassageFormatter {

  private boolean shouldPreserveField;
  
  public HighlightFormatter () {
    super("<b>", "</b>", "... ", false);
  }

  public HighlightFormatter(String preTag, String postTag, String ellipsis, boolean htmlEncoder, boolean shouldPreserveField) {
    super (preTag, postTag, ellipsis, htmlEncoder);
    this.shouldPreserveField = shouldPreserveField;
  }
  
  /**
   * Sort the passages by *score* not by offset.
   */
  @Override
  public String format(Passage passages[], String content) {
    Arrays.sort(passages, new Comparator<Passage>() {
      @Override
      public int compare(Passage p1, Passage p2) {
        return (int) (1000.0 * (p2.getScore() - p1.getScore()));
      }
    });

    // If this is a preservedField, reset offsets to make sure non-highlighted field data is not cut off
    if(shouldPreserveField) {
      for(Passage passage : passages) {
        passage.setStartOffset(0);
        passage.setEndOffset(content.length());
      }
    }

    return super.format(passages, content);
  }

  /** 
   * Appends original text to the response.
   * @param dest resulting text, with &lt; and &amp; encoded using entities, if escape==true
   * @param content original text content
   * @param start index of the first character in content
   * @param end index of the character following the last character in content
   */
  @Override
  protected void append(StringBuilder dest, String content, int start, int end) {
    if (escape) {
      for (int i = start; i < end; i++) {
        char ch = content.charAt(i);
        switch(ch) {
          case '&':
            dest.append("&amp;");
            break;
          case '<':
            dest.append("&lt;");
            break;
          default:
            dest.append(ch);
        }
      }
    } else {
      dest.append(content, start, end);
    }
  }
}

