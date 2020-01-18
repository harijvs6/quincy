package com.protocol7.quincy.flowcontrol;

import com.google.common.annotations.VisibleForTesting;
import com.protocol7.quincy.PipelineContext;
import com.protocol7.quincy.protocol.StreamId;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultFlowControlHandler implements FlowControlHandler {

  private final FlowControlCounter receiveCounter;
  private final FlowControlCounter sendCounter;
  private final AtomicBoolean connectionBlocked = new AtomicBoolean(false);
  private final Set<Long> blockedStreams = new HashSet<>();

  public DefaultFlowControlHandler(
      final long connectionMaxBytes,
      final long streamMaxBytes,
      final long maxUniStreams,
      final long maxBidiStreams) {
    receiveCounter =
        new FlowControlCounter(connectionMaxBytes, streamMaxBytes, maxUniStreams, maxBidiStreams);
    sendCounter =
        new FlowControlCounter(connectionMaxBytes, streamMaxBytes, maxUniStreams, maxBidiStreams);
  }

  @Override
  public void beforeSendPacket(final Packet packet, final PipelineContext ctx) {
    if (packet instanceof FullPacket) {
      final FullPacket fullPacket = (FullPacket) packet;
      for (final Frame frame : fullPacket.getPayload().getFrames()) {
        if (frame.getType() == FrameType.STREAM) {
          final StreamFrame sf = (StreamFrame) frame;

          if (!tryConsume(sf.getStreamId(), sf.getOffset() + sf.getData().length, ctx)) {
            throw new IllegalStateException("Stream or connection blocked");
          }
        }
      }
    }

    ctx.next(packet);
  }

  @VisibleForTesting
  protected boolean tryConsume(final long sid, final long offset, final PipelineContext ctx) {
    final TryConsumeResult result = sendCounter.tryConsume(sid, offset);

    if (result.isSuccess()) {
      return true;
    } else {
      final List<Frame> frames = new ArrayList<>();
      final boolean bidi = StreamId.isBidirectional(sid);
      if (result.getStreams() > result.getMaxStreams()) {
        frames.add(new StreamsBlockedFrame(result.getMaxStreams(), bidi));
      }
      if (result.getConnectionOffset() > result.getConnectionMaxBytes()
          && !connectionBlocked.get()) {
        frames.add(new DataBlockedFrame(result.getConnectionMaxBytes()));
        connectionBlocked.set(true);
      }
      if (result.getStreamOffset() > result.getStreamMaxBytes() && !blockedStreams.contains(sid)) {
        frames.add(new StreamDataBlockedFrame(sid, result.getStreamMaxBytes()));
        blockedStreams.add(sid);
      }
      if (!frames.isEmpty()) {
        ctx.send(frames.toArray(new Frame[0]));
      }
      return false;
    }
  }

  public void onReceivePacket(final Packet packet, final PipelineContext ctx) {
    if (packet instanceof FullPacket) {
      final FullPacket fp = (FullPacket) packet;
      // listen for flow control frames
      for (final Frame frame : fp.getPayload().getFrames()) {
        if (frame.getType() == FrameType.MAX_STREAM_DATA) {
          final MaxStreamDataFrame msd = (MaxStreamDataFrame) frame;
          sendCounter.setStreamMaxBytes(msd.getStreamId(), msd.getMaxStreamData());
          blockedStreams.remove(msd.getStreamId());
        } else if (frame.getType() == FrameType.MAX_DATA) {
          final MaxDataFrame mdf = (MaxDataFrame) frame;
          sendCounter.setConnectionMaxBytes(mdf.getMaxData());
          connectionBlocked.set(false);
        } else if (frame.getType() == FrameType.STREAM) {
          final StreamFrame sf = (StreamFrame) frame;
          final long sid = sf.getStreamId();
          final TryConsumeResult result =
              receiveCounter.tryConsume(sid, sf.getOffset() + sf.getData().length);

          if (result.isSuccess()) {
            final List<Frame> frames = new ArrayList<>();
            if (1.0 * result.getConnectionOffset() / result.getConnectionMaxBytes() > 0.5) {
              final long newMax = receiveCounter.increaseConnectionMax();
              frames.add(new MaxDataFrame(newMax));
            }
            if (1.0 * result.getStreamOffset() / result.getStreamMaxBytes() > 0.5) {
              final long newMax = receiveCounter.increaseStreamMax(sid);
              frames.add(new MaxStreamDataFrame(sid, newMax));
            }
            // TODO right place?. stream not yet closed
            if (sf.isFin()) {
              final boolean bidi = StreamId.isBidirectional(sid);
              final long newMax = receiveCounter.increaseMaxStreams(bidi);
              frames.add(new MaxStreamsFrame(newMax, bidi));
            }
            if (!frames.isEmpty()) {
              ctx.send(frames.toArray(new Frame[0]));
            }
          } else {
            if (result.getStreams() == result.getMaxStreams()) {
              ctx.closeConnection(
                  TransportError.STREAM_LIMIT_ERROR, FrameType.STREAM, "Stream limit error");
            } else {
              ctx.closeConnection(
                  TransportError.FLOW_CONTROL_ERROR, FrameType.STREAM, "Flow control error");
            }
          }
        } else if (frame.getType() == FrameType.MAX_STREAMS) {
          final MaxStreamsFrame msf = (MaxStreamsFrame) frame;
          receiveCounter.setMaxStreams(msf.getMaxStreams(), msf.isBidi());
        }
      }
    }

    ctx.next(packet);
  }
}
