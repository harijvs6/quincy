package com.protocol7.quincy.flowcontrol;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;

import com.protocol7.quincy.protocol.StreamId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FlowControlCounter {

  // TODO make sure max bytes does not grow forever

  private final AtomicLong connectionMaxBytes;
  private final long defaultStreamMaxBytes;
  private final AtomicLong maxUniStreams;
  private final AtomicLong maxBidiStreams;
  private final AtomicLong uniStreams;
  private final AtomicLong bidiStreams;

  private class StreamCounter {
    public boolean finished = false;
    public final AtomicLong maxOffset = new AtomicLong(defaultStreamMaxBytes);
    public final AtomicLong offset = new AtomicLong(0);
  }

  // TODO this will grow forever. Consider how we can garbage collect finished streams while not
  // recreating them on out-of-order packets
  private final Map<Long, StreamCounter> streams = new ConcurrentHashMap<>();

  public FlowControlCounter(
      final long connectionMaxBytes,
      final long streamMaxBytes,
      final long maxUniStreams,
      final long maxBidiStreams) {
    this.connectionMaxBytes = new AtomicLong(connectionMaxBytes);
    this.defaultStreamMaxBytes = streamMaxBytes;
    this.maxUniStreams = new AtomicLong(maxUniStreams);
    this.maxBidiStreams = new AtomicLong(maxBidiStreams);
    this.uniStreams = new AtomicLong(0);
    this.bidiStreams = new AtomicLong(0);
  }

  private long calculateConnectionOffset() {
    return streams.values().stream().mapToLong(c -> c.offset.get()).sum();
  }

  // remove need to syncronize
  public synchronized TryConsumeResult tryConsume(final long sid, final long offset) {
    checkArgument(offset > 0);

    final boolean bidi = StreamId.isBidirectional(sid);
    // first check if we can successfully consume
    final StreamCounter stream =
        streams.computeIfAbsent(
            sid,
            ignored -> {
              if ((bidi && (bidiStreams.get() == maxBidiStreams.get()))
                  || (!bidi && (uniStreams.get() == maxUniStreams.get()))) {
                return null;
              }
              final long streams =
                  bidi ? bidiStreams.incrementAndGet() : uniStreams.incrementAndGet();
              return new StreamCounter();
            });

    if (stream == null) {
      return new TryConsumeResult(
          false,
          0,
          0,
          0,
          0,
          bidi ? maxBidiStreams.get() : maxUniStreams.get(),
          bidi ? bidiStreams.get() : uniStreams.get());
    }
    final long streamMax = stream.maxOffset.get();
    final AtomicLong streamConsumed = stream.offset;
    final long connOffset = calculateConnectionOffset();

    final long streamDelta = offset - streamConsumed.get();

    final long resultingConnOffset;
    final long resultingStreamOffset;
    final boolean success;
    if (streamDelta < 0) {
      // out of order, always successful
      success = true;
      resultingConnOffset = connOffset;
      resultingStreamOffset = streamConsumed.get();
    } else if (streamDelta > 0 && stream.finished) {
      // trying to increase offset for finished stream, bail
      throw new IllegalStateException("Stream finished");
    } else if (offset > streamMax || connOffset + streamDelta > connectionMaxBytes.get()) {
      success = false;
      resultingConnOffset = connOffset + streamDelta;
      resultingStreamOffset = offset;
    } else {
      success = true;
      streamConsumed.updateAndGet(current -> max(current, offset));
      resultingConnOffset = connOffset + streamDelta;
      resultingStreamOffset = streamConsumed.get();
    }

    return new TryConsumeResult(
        success,
        resultingConnOffset,
        connectionMaxBytes.get(),
        resultingStreamOffset,
        streamMax,
        bidi ? maxBidiStreams.get() : maxUniStreams.get(),
        bidi ? bidiStreams.get() : uniStreams.get());
  }

  public void resetStream(final long sid, final long finalOffset) {
    final StreamCounter stream = streams.computeIfAbsent(sid, ignored -> new StreamCounter());
    stream.offset.updateAndGet(current -> max(current, finalOffset));
    stream.finished = true;
  }

  public void setConnectionMaxBytes(final long connectionMaxBytes) {
    checkArgument(connectionMaxBytes > 0);

    this.connectionMaxBytes.updateAndGet(current -> max(connectionMaxBytes, current));
  }

  public void setMaxStreams(final long maxStreams, final boolean bidi) {
    checkArgument(maxStreams > 0);

    if (!bidi) {
      this.maxUniStreams.updateAndGet(current -> max(maxStreams, current));
    } else {
      this.maxBidiStreams.updateAndGet(current -> max(maxStreams, current));
    }
  }

  public long increaseStreamMax(final long sid) {
    final StreamCounter stream = streams.computeIfAbsent(sid, ignored -> new StreamCounter());
    final AtomicLong streamMax = stream.maxOffset;

    // double
    return streamMax.addAndGet(streamMax.get());
  }

  public long increaseConnectionMax() {
    // double
    return connectionMaxBytes.addAndGet(connectionMaxBytes.get());
  }

  public long increaseMaxStreams(final boolean bidi) {
    if (!bidi) {
      return maxUniStreams.addAndGet(maxUniStreams.get());
    } else {
      return maxBidiStreams.addAndGet(maxBidiStreams.get());
    }
  }

  public void setStreamMaxBytes(final long sid, final long streamMaxBytes) {
    checkArgument(streamMaxBytes > 0);

    final StreamCounter stream = streams.computeIfAbsent(sid, ignored -> new StreamCounter());
    final AtomicLong streamMax = stream.maxOffset;

    streamMax.updateAndGet(current -> max(streamMaxBytes, current));
  }
}
