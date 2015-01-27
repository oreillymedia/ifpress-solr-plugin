package com.ifactory.press.db.solr.highlight;

import org.apache.lucene.search.postingshighlight.DefaultPassageFormatter;

public class HighlightFormatter extends DefaultPassageFormatter {
  
  public HighlightFormatter () {
    super("<b>", "</b>", "... ", false);
  }

  public HighlightFormatter(String preTag, String postTag, String ellipsis, boolean equals) {
    super (preTag, postTag, ellipsis, equals);
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

