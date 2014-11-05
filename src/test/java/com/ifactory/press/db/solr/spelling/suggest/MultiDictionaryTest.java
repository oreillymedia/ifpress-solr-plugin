package com.ifactory.press.db.solr.spelling.suggest;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MultiDictionaryTest {

  @Test
  public void testStripAfflatus() {
    assertEquals("", MultiDictionary.stripAfflatus(""));
    assertEquals("", MultiDictionary.stripAfflatus(" "));
    assertEquals("", MultiDictionary.stripAfflatus("''"));

    assertEquals("word", MultiDictionary.stripAfflatus(" word"));
    assertEquals("word", MultiDictionary.stripAfflatus("word "));
    assertEquals("word", MultiDictionary.stripAfflatus("...word---"));

    assertEquals("a.out", MultiDictionary.stripAfflatus("...a.out---"));

    assertEquals("123", MultiDictionary.stripAfflatus("(123)"));
    assertEquals("c", MultiDictionary.stripAfflatus("𐌸c𐌸"));

  }

}
