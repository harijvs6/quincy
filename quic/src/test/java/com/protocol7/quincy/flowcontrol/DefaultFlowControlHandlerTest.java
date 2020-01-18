package com.protocol7.quincy.flowcontrol;

import static java.util.Optional.of;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import com.protocol7.quincy.PipelineContext;
import com.protocol7.quincy.protocol.ConnectionId;
import com.protocol7.quincy.protocol.PacketNumber;
import com.protocol7.quincy.protocol.Payload;
import com.protocol7.quincy.protocol.TransportError;
import com.protocol7.quincy.protocol.frames.DataBlockedFrame;
import com.protocol7.quincy.protocol.frames.Frame;
import com.protocol7.quincy.protocol.frames.FrameType;
import com.protocol7.quincy.protocol.frames.MaxDataFrame;
import com.protocol7.quincy.protocol.frames.MaxStreamDataFrame;
import com.protocol7.quincy.protocol.frames.MaxStreamsFrame;
import com.protocol7.quincy.protocol.frames.StreamDataBlockedFrame;
import com.protocol7.quincy.protocol.frames.StreamFrame;
import com.protocol7.quincy.protocol.frames.StreamsBlockedFrame;
import com.protocol7.quincy.protocol.packets.FullPacket;
import com.protocol7.quincy.protocol.packets.Packet;
import com.protocol7.quincy.protocol.packets.ShortPacket;
import org.junit.Test;

public class DefaultFlowControlHandlerTest {

  private final DefaultFlowControlHandler handler = new DefaultFlowControlHandler(15, 10, 2, 2);
  private final PipelineContext ctx = mock(PipelineContext.class);
  private final long sid = 123;
  private final long sid2 = 456;
  private final long bidiSid1 = 8;
  private final long bidiSid2 = 9;
  private final long bidiSid3 = 12;
  private final long bidiSid4 = 16;
  private final long uniSid1 = 10;
  private final long uniSid2 = 11;
  private final long uniSid3 = 14;
  private final long uniSid4 = 15;

  @Test
  public void tryConsume() {
    assertTrue(handler.tryConsume(sid, 10, ctx));
    verifyZeroInteractions(ctx);

    // blocked on stream limit
    assertFalse(handler.tryConsume(sid, 11, ctx));
    verify(ctx).send(new StreamDataBlockedFrame(sid, 10));
  }

  @Test
  public void tryConsumeRefillStreamData() {
    assertTrue(handler.tryConsume(sid, 10, ctx));
    verifyZeroInteractions(ctx);

    // running out of stream tokens
    assertFalse(handler.tryConsume(sid, 12, ctx));
    verify(ctx).send(new StreamDataBlockedFrame(sid, 10));

    // increase stream tokens
    Packet packet = p(new MaxStreamDataFrame(sid, 12));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // we can now consumer stream tokens
    assertTrue(handler.tryConsume(sid, 12, ctx));
    verifyZeroInteractions(ctx);

    // but not this many
    assertFalse(handler.tryConsume(sid, 13, ctx));
    verify(ctx).send(new StreamDataBlockedFrame(sid, 12));

    // must not send any additional data blocked frames until new size
    assertFalse(handler.tryConsume(sid, 13, ctx));
    verifyZeroInteractions(ctx);

    // reset data blocked frames
    packet = p(new MaxStreamDataFrame(sid, 13));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // we must now get a new data blocked frame
    assertFalse(handler.tryConsume(sid, 14, ctx));
    verify(ctx).send(new StreamDataBlockedFrame(sid, 13));
  }

  @Test
  public void tryConsumeRefillStreams() {
    // first stream
    assertTrue(handler.tryConsume(uniSid1, 2, ctx));
    verifyZeroInteractions(ctx);

    // second stream
    assertTrue(handler.tryConsume(uniSid2, 2, ctx));
    verifyZeroInteractions(ctx);

    // running out of streams
    assertFalse(handler.tryConsume(uniSid3, 3, ctx));
    verify(ctx).send(new StreamsBlockedFrame(2, false));

    // bidi streams still available
    assertTrue(handler.tryConsume(bidiSid1, 1, ctx));
    verifyZeroInteractions(ctx);

    // increase number of uni streams
    Packet packet = p(new MaxStreamsFrame(3, false));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // we can now create more streams
    assertTrue(handler.tryConsume(uniSid3, 2, ctx));
    verifyZeroInteractions(ctx);

    // not this many
    assertFalse(handler.tryConsume(uniSid4, 3, ctx));
    verify(ctx).send(new StreamsBlockedFrame(3, false));

    // allow bidi stream
    assertTrue(handler.tryConsume(bidiSid2, 2, ctx));
    verifyZeroInteractions(ctx);

    // not this many
    assertFalse(handler.tryConsume(bidiSid3, 2, ctx));
    verify(ctx).send(new StreamsBlockedFrame(2, true));
  }

  @Test
  public void tryConsumeRefillConnection() {
    assertTrue(handler.tryConsume(sid, 10, ctx));
    verifyZeroInteractions(ctx);

    assertFalse(handler.tryConsume(sid2, 6, ctx));
    verify(ctx).send(new DataBlockedFrame(15));

    Packet packet = p(new MaxDataFrame(16));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    assertTrue(handler.tryConsume(sid2, 6, ctx));
    verifyZeroInteractions(ctx);

    assertFalse(handler.tryConsume(sid2, 7, ctx));
    verify(ctx).send(new DataBlockedFrame(16));

    // must not send any additional data blocked frames until new size
    assertFalse(handler.tryConsume(sid2, 7, ctx));
    verifyZeroInteractions(ctx);

    // reset data blocked frames
    packet = p(new MaxDataFrame(17));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // we must now get a new data blocked frame
    assertFalse(handler.tryConsume(sid2, 8, ctx));
    verify(ctx).send(new DataBlockedFrame(17));
  }

  @Test
  public void streamFrames() {
    Packet packet = p(new StreamFrame(sid, 0, false, new byte[3]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // going over 50% of the max stream offset, send a new max stream offset
    packet = p(new StreamFrame(sid, 3, false, new byte[3]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).send(new MaxStreamDataFrame(sid, 20));
    verify(ctx).next(packet);

    // going over 50% of the max connection offset, send a new max connection offset
    packet = p(new StreamFrame(sid, 6, false, new byte[3]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).send(new MaxDataFrame(30));
    verify(ctx).next(packet);

    // user more than flow control allow, must close connection
    packet = p(new StreamFrame(sid, 10, false, new byte[11]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx)
        .closeConnection(eq(TransportError.FLOW_CONTROL_ERROR), eq(FrameType.STREAM), anyString());
    verify(ctx).next(packet);
  }

  @Test
  public void maxStreamsFrames() {
    // first uni stream
    Packet packet = p(new StreamFrame(uniSid1, 0, false, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // second uni stream with fin bit set. should send MAX_STREAMS frame
    packet = p(new StreamFrame(uniSid1, 0, true, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).send(new MaxStreamsFrame(3, false));
    verify(ctx).next(packet);

    // first bidi stream
    packet = p(new StreamFrame(bidiSid1, 0, false, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // second bidi stream with fin bit set. should send MAX_STREAMS frame
    packet = p(new StreamFrame(bidiSid2, 0, true, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).send(new MaxStreamsFrame(3, true));
    verify(ctx).next(packet);

    // open more streams
    packet = p(new StreamFrame(bidiSid3, 0, false, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx).next(packet);

    // not more than this. close connection
    packet = p(new StreamFrame(bidiSid4, 0, false, new byte[1]));
    handler.onReceivePacket(packet, ctx);
    verify(ctx)
        .closeConnection(eq(TransportError.STREAM_LIMIT_ERROR), eq(FrameType.STREAM), anyString());
    verify(ctx).next(packet);
  }

  private FullPacket p(final Frame frame) {
    return new ShortPacket(false, of(ConnectionId.random()), PacketNumber.MIN, new Payload(frame));
  }
}
