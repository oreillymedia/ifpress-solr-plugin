package com.ifactory.press.db.solr.spelling.suggest;

import org.apache.lucene.util.BytesRef;

import java.util.HashSet;
import java.util.Set;

public class Suggestion {

  private String text;
  private String payload;
  private Set<BytesRef> contexts;
  private long weight;

  public Suggestion(BytesRef text, Set<BytesRef> contexts, long weight, BytesRef payload) {
    this.text = text != null ? text.utf8ToString() : null;
    this.contexts = contexts != null ? contexts : new HashSet<BytesRef>();
    this.weight = weight;
    this.payload = payload != null ? payload.utf8ToString() : null;
  }

  public Suggestion(String rawText, String[] rawContexts, long weight, BytesRef payload) {
    this.text = rawText;
    this.contexts = new HashSet<>();
    this.weight = weight;
    this.payload = payload != null ? payload.utf8ToString() : null;
    if(rawContexts != null) {
      for (String context : rawContexts) {
        contexts.add(new BytesRef(context));
      }
    }
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }

  public Set<BytesRef> getContexts() {
    return contexts;
  }

  public void setContexts(Set<BytesRef> contexts) {
    this.contexts = contexts;
  }

  public long getWeight() {
    return weight;
  }

  public void setWeight(long weight) {
    this.weight = weight;
  }

  /**
   * Overridden equals() method that only uses text and contexts variables for equality check.
   * @param other the other Object to be checked for equality to this Suggestion
   * @return true if 'other' is an instance of Suggestion and has the same text and
   * contexts as this Suggestion object.
   */
  @Override
  public boolean equals(Object other) {
    if(this == other) {
      return true;
    }

    if(!(other instanceof Suggestion)) {
      return false;
    }

    Suggestion otherSuggestion = (Suggestion)other;

    // Intentionally only using text and context to signify that two suggestions are equal.
    return this.text.equals(otherSuggestion.text) && this.contexts.equals(otherSuggestion.contexts);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + text.hashCode();
    result = 31 * result + contexts.hashCode();
    return result;
  }
}
