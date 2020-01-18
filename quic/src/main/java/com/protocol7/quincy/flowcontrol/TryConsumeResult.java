package com.protocol7.quincy.flowcontrol;

public class TryConsumeResult {
  private final boolean success;
  private final long connectionOffset;
  private final long connectionMaxBytes;
  private final long streamOffset;
  private final long streamMaxBytes;
  private final long maxStreams;
  private final long streams;

  public TryConsumeResult(
      final boolean success,
      final long connectionOffset,
      final long connectionMaxBytes,
      final long streamOffset,
      final long streamMaxBytes,
      final long maxStreams,
      final long streams) {
    this.success = success;
    this.connectionOffset = connectionOffset;
    this.connectionMaxBytes = connectionMaxBytes;
    this.streamOffset = streamOffset;
    this.streamMaxBytes = streamMaxBytes;
    this.maxStreams = maxStreams;
    this.streams = streams;
  }

  public boolean isSuccess() {
    return success;
  }

  public long getConnectionOffset() {
    return connectionOffset;
  }

  public long getConnectionMaxBytes() {
    return connectionMaxBytes;
  }

  public long getStreamOffset() {
    return streamOffset;
  }

  public long getStreamMaxBytes() {
    return streamMaxBytes;
  }

  public long getMaxStreams() {
    return maxStreams;
  }

  public long getStreams() {
    return streams;
  }
}
