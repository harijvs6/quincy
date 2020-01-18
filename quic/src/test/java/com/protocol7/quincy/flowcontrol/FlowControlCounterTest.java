package com.protocol7.quincy.flowcontrol;

import static org.junit.Assert.*;

import org.junit.Test;

public class FlowControlCounterTest {

  private final long maxConn = 15;
  private final long maxStream = 10;
  private final long maxUniStreams = 2;
  private final long maxBidiStreams = 2;

  private final FlowControlCounter fcm =
      new FlowControlCounter(maxConn, maxStream, maxUniStreams, maxBidiStreams);
  private final long sid = 4;
  private final long sid2 = 5;
  private final long bidiSid1 = 8;
  private final long bidiSid2 = 9;
  private final long bidiSid3 = 12;
  private final long uniSid1 = 10;
  private final long uniSid2 = 11;
  private final long uniSid3 = 14;

  @Test
  public void tryConsumeConnection() {
    assertConsume(fcm.tryConsume(sid, 7), true, 7, maxConn, 7, maxStream);
    assertConsume(fcm.tryConsume(sid2, 8), true, 15, maxConn, 8, maxStream);
    assertConsume(fcm.tryConsume(sid, 8), false, 16, maxConn, 8, maxStream);

    fcm.setConnectionMaxBytes(20);
    assertConsume(fcm.tryConsume(sid, 9), true, 17, 20, 9, maxStream);
  }

  @Test
  public void tryConsumeStream() {
    assertConsume(fcm.tryConsume(sid, 9), true, 9, maxConn, 9, maxStream);
    assertConsume(fcm.tryConsume(sid, 11), false, 11, maxConn, 11, maxStream);

    fcm.setStreamMaxBytes(sid, 20);
    assertConsume(fcm.tryConsume(sid, 11), true, 11, maxConn, 11, 20);
  }

  @Test
  public void tryConsumeOutOfOrder() {
    assertConsume(fcm.tryConsume(sid, 8), true, 8, maxConn, 8, maxStream);
    assertConsume(fcm.tryConsume(sid, 7), true, 8, maxConn, 8, maxStream);
  }

  @Test
  public void resetStream() {
    assertConsume(fcm.tryConsume(sid, 2), true, 2, maxConn, 2, maxStream);
    fcm.resetStream(sid, 5);
    assertConsume(fcm.tryConsume(sid2, 1), true, 6, maxConn, 1, maxStream);
  }

  @Test
  public void resetStreamOutOfOrder() {
    assertConsume(fcm.tryConsume(sid, 2), true, 2, maxConn, 2, maxStream);
    fcm.resetStream(sid, 5);

    // get a stream offset that is smaller orr equal the finished value
    assertConsume(fcm.tryConsume(sid, 5), true, 5, maxConn, 5, maxStream);
  }

  @Test(expected = IllegalStateException.class)
  public void offsetForResetStream() {
    assertConsume(fcm.tryConsume(sid, 2), true, 2, maxConn, 2, maxStream);
    fcm.resetStream(sid, 5);
    fcm.tryConsume(sid, 6);
  }

  @Test
  public void tryConsumeTooSmallConnectionSet() {
    assertConsume(fcm.tryConsume(sid, 8), true, 8, maxConn, 8, maxStream);

    fcm.setConnectionMaxBytes(9); // must be ignored as the current max is larger
    assertConsume(fcm.tryConsume(sid, 10), true, 10, maxConn, 10, maxStream);
  }

  @Test(expected = IllegalArgumentException.class)
  public void tryConsumeNegative() {
    fcm.tryConsume(sid, -8);
  }

  @Test(expected = IllegalArgumentException.class)
  public void setMaxConnectionNegative() {
    fcm.setConnectionMaxBytes(-1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void setMaxStreamNegative() {
    fcm.setStreamMaxBytes(sid, -1);
  }

  @Test
  public void tryConsumeMaxUniStreams() {
    fcm.tryConsume(uniSid1, 2);
    fcm.tryConsume(uniSid2, 2);
    assertConsume(fcm.tryConsume(uniSid3, 2), false, 2, 2);
    assertConsume(fcm.tryConsume(bidiSid1, 2), true, 1, 2);

    fcm.setMaxStreams(3, false);
    assertConsume(fcm.tryConsume(uniSid3, 2), true, 3, 3);
  }

  @Test
  public void tryConsumeMaxBidiStreams() {
    fcm.tryConsume(bidiSid1, 2);
    fcm.tryConsume(bidiSid2, 2);
    assertConsume(fcm.tryConsume(bidiSid3, 2), false, 2, 2);
    assertConsume(fcm.tryConsume(uniSid1, 2), true, 1, 2);

    fcm.setMaxStreams(3, true);
    assertConsume(fcm.tryConsume(bidiSid3, 2), true, 3, 3);
  }

  private void assertConsume(
      final TryConsumeResult actual,
      final boolean success,
      final long connectionOffset,
      final long connectionMax,
      final long streamOffset,
      final long streamMax) {
    assertEquals(success, actual.isSuccess());
    assertEquals("Connection offset", connectionOffset, actual.getConnectionOffset());
    assertEquals("Connection max", connectionMax, actual.getConnectionMaxBytes());
    assertEquals("Stream offset", streamOffset, actual.getStreamOffset());
    assertEquals("Stream max", streamMax, actual.getStreamMaxBytes());
  }

  private void assertConsume(
      final TryConsumeResult actual,
      final boolean success,
      final long streams,
      final long streamsMax) {
    assertEquals(success, actual.isSuccess());
    assertEquals("Streams", streams, actual.getStreams());
    assertEquals("Streams max", streamsMax, actual.getMaxStreams());
  }
}
