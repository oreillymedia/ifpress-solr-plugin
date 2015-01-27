package com.ifactory.press.db.solr.analysis;

import java.util.Map;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.TokenFilterFactory;

public class PunctuationStrippingTokenFilterFactory extends TokenFilterFactory {

  public PunctuationStrippingTokenFilterFactory(Map<String, String> args) {
    super(args);
    if (!args.isEmpty()) {
      throw new IllegalArgumentException("Unknown parameters: " + args);
    }
  }

  @Override
  public TokenStream create(TokenStream input) {
    return new PunctuationStrippingTokenFilter(input);
  }

}
