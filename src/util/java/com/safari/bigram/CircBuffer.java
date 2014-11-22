package com.safari.bigram;

public class CircBuffer<T> {
    private T[] buf;
    private int rpos;
    private int wpos;
    
    @SuppressWarnings("unchecked")
    CircBuffer (int size) {
      buf = (T[]) new Object[size + 1];
      rpos = 0;
      wpos = 0;
    }
    
    // TODO- get fancier about locking; use AtomicInteger; use threadlocal pointers for lock-free operation
    synchronized T pop() {
      if (rpos == wpos) {
        return null;
      }
      T t = buf[rpos++];
      if (rpos == buf.length) {
        rpos = 0;
      }
      return t;
    }
    
    synchronized boolean push(T t) {
      if ((wpos + 1) % buf.length == rpos) {
        // buffer full
        return false;
      }
      buf[wpos++] = t;
      if (wpos == buf.length){
        wpos = 0;
      }
      return true;
    }

    synchronized boolean push(T[] tarray, int off, int len) {
      int delta = rpos - wpos;
      int capacity = delta > 0 ? delta - 1 : buf.length + delta - 1;
      if (capacity < len) {
        return false;
      }
      //System.err.println (String.format("%d %d %d -> %d %d %d", tarray.length, off, len, buf.length, wpos, rpos));
      if (delta > 0) {
        System.arraycopy(tarray, off, buf, wpos, len);
      } else {
        int remainder = buf.length - wpos;
        if (remainder >= len) {
          System.arraycopy(tarray, off, buf, wpos, len);
        } else {
          System.arraycopy(tarray, off, buf, wpos, remainder);
          System.arraycopy(tarray, off + remainder, buf, 0, len - remainder);
        }
      }
      wpos = (wpos + len) % buf.length;
      return true;
    }
    
    boolean isEmpty () {
      return rpos == wpos;
    }
}