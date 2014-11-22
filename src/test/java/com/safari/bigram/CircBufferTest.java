package com.safari.bigram;

import static org.junit.Assert.*;

import org.junit.Test;

public class CircBufferTest {

  @Test
  public void testNull() {
    CircBuffer<String> buf = new CircBuffer<String>(10);
    assertNull (buf.pop());
    assertTrue (buf.isEmpty());
    assertTrue (buf.push(null));  // nulls are ok
    assertFalse (buf.isEmpty());
    assertNull (buf.pop());
    assertTrue (buf.isEmpty());
  }
  
  @Test
  public void testPushPop() {
    CircBuffer<String> buf = new CircBuffer<String>(10);
    assertTrue (buf.push ("apple"));
    assertTrue (buf.push ("banana"));
    assertEquals ("apple", buf.pop());
    assertTrue (buf.push ("cherry"));
    assertEquals ("banana", buf.pop());
    assertEquals ("cherry", buf.pop());
    assertNull (buf.pop());
  }
  
  @Test
  public void testFull() {
    CircBuffer<String> buf = new CircBuffer<String>(2);
    assertTrue (buf.push ("apple"));
    assertTrue (buf.push ("banana"));
    assertFalse (buf.push ("cherry"));
    assertEquals ("apple", buf.pop());
    assertEquals ("banana", buf.pop());
    assertNull (buf.pop());
  }
  
  @Test
  public void testWraparound() {
    CircBuffer<String> buf = new CircBuffer<String>(10);
    String [] b = new String[20];
    for (int i = 0; i < 20; i++) {
      b[i] = "x" + i;
    }
    assertFalse (buf.push(b, 0, 20)); // too big to fit
    assertTrue (buf.push(b, 0, 10)); // fill the buffer
    assertFalse (buf.push(b, 0, 1)); // full
    for (int i = 0; i < 5; i++) {
      assertEquals ("x" + i, buf.pop());
    }
    assertFalse (buf.push(b, 10, 10)); // too much
    assertTrue(buf.push(b, 10, 5)); // OK, full again, position half way
    for (int i = 5; i < 9; i++) {
      assertEquals ("x" + i, buf.pop());
    }
    assertFalse (buf.push(b, 10, 10)); // too much
    assertTrue(buf.push(b, 15, 4));
    assertFalse(buf.push("xxx")); // full
  }
  
  @Test
  public void testLargeBuffer() {
    CircBuffer<String> buf = new CircBuffer<String>(10);
    String [] b = new String[5];
    for (int i = 0; i < 5; i++) {
      b[i] = "x" + i;
    }
    assertTrue (buf.push ("one"));
    assertEquals ("one", buf.pop());
    assertTrue (buf.push ("two"));
    assertTrue (buf.push(b, 0, 5)); 
    assertFalse (buf.push(b, 0, 5));
    assertTrue (buf.push(b, 0, 4));
    assertEquals ("two", buf.pop());
    for (int i = 0; i < 5; i++) {
      assertEquals ("x" + i, buf.pop());
    }
    for (int i = 0; i < 4; i++) {
      assertEquals ("x" + i, buf.pop());
    }
    assertTrue (buf.isEmpty());
  }
  
  @Test
  public void testEmptyWrap () {
    // make sure we can wrap around when the buffer is empty
    CircBuffer<String> buf = new CircBuffer<String>(5);
    String [] b = new String[5];
    for (int i = 0; i < 5; i++) {
      b[i] = "x" + i;
    }
    assertTrue (buf.push ("one"));
    assertTrue (buf.push ("two"));
    assertEquals ("one", buf.pop());
    assertEquals ("two", buf.pop());
    assertTrue (buf.isEmpty());
    assertTrue (buf.push (b, 0, 5));
    for (int i = 0; i < 5; i++) {
      assertEquals ("x" + i, buf.pop());
    }
    assertTrue (buf.isEmpty());
  }
  
}
