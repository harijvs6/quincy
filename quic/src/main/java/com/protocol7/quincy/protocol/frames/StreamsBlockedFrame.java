package com.protocol7.quincy.protocol.frames;

import com.protocol7.quincy.Varint;
import io.netty.buffer.ByteBuf;

public class StreamsBlockedFrame extends Frame {

  private static final byte BIDI_TYPE = 0x16;
  private static final byte UNI_TYPE = 0x17;

  public static StreamsBlockedFrame parse(final ByteBuf bb) {
    final byte type = bb.readByte();
    if (type != BIDI_TYPE && type != UNI_TYPE) {
      throw new IllegalArgumentException("Illegal frame type");
    }

    final boolean bidi = type == BIDI_TYPE;
    final long streamLimit = Varint.readAsLong(bb);

    return new StreamsBlockedFrame(streamLimit, bidi);
  }

  private final long streamsLimit;
  private final boolean bidi;

  public StreamsBlockedFrame(final long streamsLimit, final boolean bidi) {
    super(FrameType.STREAMS_BLOCKED);

    this.streamsLimit = streamsLimit;
    this.bidi = bidi;
  }

  public long getStreamsLimit() {
    return streamsLimit;
  }

  public boolean isBidi() {
    return bidi;
  }

  @Override
  public void write(final ByteBuf bb) {
    if (bidi) {
      bb.writeByte(BIDI_TYPE);
    } else {
      bb.writeByte(UNI_TYPE);
    }

    Varint.write(streamsLimit, bb);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (bidi ? 1231 : 1237);
    result = prime * result + (int) (streamsLimit ^ (streamsLimit >>> 32));
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    StreamsBlockedFrame other = (StreamsBlockedFrame) obj;
    if (bidi != other.bidi) return false;
    if (streamsLimit != other.streamsLimit) return false;
    return true;
  }
}
