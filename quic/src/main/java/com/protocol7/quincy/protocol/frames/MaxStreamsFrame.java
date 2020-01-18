package com.protocol7.quincy.protocol.frames;

import com.protocol7.quincy.Varint;
import io.netty.buffer.ByteBuf;

public class MaxStreamsFrame extends Frame {

  private static final byte BIDI_TYPE = 0x12;
  private static final byte UNI_TYPE = 0x13;

  public static MaxStreamsFrame parse(final ByteBuf bb) {
    final byte type = bb.readByte();
    if (type != BIDI_TYPE && type != UNI_TYPE) {
      throw new IllegalArgumentException("Illegal frame type");
    }

    final boolean bidi = type == BIDI_TYPE;
    final long maxStreams = Varint.readAsLong(bb);
    return new MaxStreamsFrame(maxStreams, bidi);
  }

  private final long maxStreams;
  private final boolean bidi;

  public MaxStreamsFrame(final long maxStreams, final boolean bidi) {
    super(FrameType.MAX_STREAMS);

    this.maxStreams = maxStreams;
    this.bidi = bidi;
  }

  public long getMaxStreams() {
    return maxStreams;
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

    Varint.write(maxStreams, bb);
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + (bidi ? 1231 : 1237);
    result = prime * result + (int) (maxStreams ^ (maxStreams >>> 32));
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    MaxStreamsFrame other = (MaxStreamsFrame) obj;
    if (bidi != other.bidi) return false;
    if (maxStreams != other.maxStreams) return false;
    return true;
  }
}
