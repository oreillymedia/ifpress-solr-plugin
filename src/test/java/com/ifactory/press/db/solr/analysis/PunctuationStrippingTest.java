package com.ifactory.press.db.solr.analysis;

import static org.junit.Assert.*;

import java.io.IOException;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.Test;

public class PunctuationStrippingTest {

    @Test
    public void testStripPunctuation() throws IOException {
        TokenStream tokens = new MockTokenStream("Now is -- um ! @ # $ % ^ & * ( ) - _ + = [ ] { } : ; ' \" < > , . / ? | \\ - the time".split(" "));
        TokenStream stripped = new PunctuationStrippingTokenFilter(tokens);
        assertTokenStream(stripped, "Now", "is", "um", "the", "time");
    }

    @Test
    public void testMixedTokens() throws IOException {
        TokenStream tokens = new MockTokenStream("'Now is *the* time!'".split(" "));
        TokenStream stripped = new PunctuationStrippingTokenFilter(tokens);
        assertTokenStream(stripped, "'Now", "is", "*the*", "time!'");
    }

    private void assertTokenStream(TokenStream stripped, String... tokens) throws IOException {
        CharTermAttribute termAtt = stripped.addAttribute(CharTermAttribute.class);
        PositionIncrementAttribute posIncAtt = stripped.addAttribute(PositionIncrementAttribute.class);
        for (String token : tokens) {
            assertTrue(stripped.incrementToken());
            assertEquals(1, posIncAtt.getPositionIncrement());
            assertEquals(token, termAtt.toString());
        }
        assertFalse(stripped.incrementToken());
    }

    static final class MockTokenStream extends TokenStream {

        private final String[] tokens;
        private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
        private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
        private int i;

        MockTokenStream(String... tokens) {
            this.tokens = tokens;
            i = 0;
        }

        @Override
        public boolean incrementToken() throws IOException {
            if (i >= tokens.length) {
                return false;
            }
            posIncrAtt.setPositionIncrement(1);
            termAtt.setEmpty();
            termAtt.append(tokens[i++]);
            return true;
        }

    }

}
