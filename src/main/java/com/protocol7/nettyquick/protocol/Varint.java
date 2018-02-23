package com.protocol7.nettyquick.protocol;

import java.util.Arrays;
import java.util.Random;

import com.google.common.primitives.Longs;
import com.protocol7.nettyquick.utils.Bytes;
import com.protocol7.nettyquick.utils.Rnd;
import io.netty.buffer.ByteBuf;

public class Varint {

  public static Varint random() {
    // TODO ensure max 4611686018427387903
    return new Varint(Rnd.rndLong());
  }

  public static Varint read(ByteBuf bb) {
    int first = (bb.readByte() & 0xFF);
    int size = ((first & 0b11000000) & 0xFF) ;
    int rest = ((first & 0b00111111) & 0xFF) ;

    int len = (int)Math.pow(2, size >> 6) - 1;

    byte[] b = new byte[len];
    bb.readBytes(b);
    byte[] pad = new byte[7-len];
    byte[] bs = Bytes.concat(pad, new byte[]{(byte)rest}, b);

    return new Varint(Longs.fromByteArray(bs));

  }

  public Varint(final long value) {
    // TODO validate value
    this.value = value;
  }

  private final long value;

  public long getValue() {
    return value;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final Varint varint = (Varint) o;

    return value == varint.value;

  }

  @Override
  public int hashCode() {
    return (int) (value ^ (value >>> 32));
  }

  public void write(ByteBuf bb) {
    int from;
    int mask;
    if (value > 1073741823) {
      from = 0;
      mask = 0b11000000;
    } else if (value > 16383) {
      from = 4;
      mask = 0b10000000;
    } else if (value > 63) {
      from = 6;
      mask = 0b01000000;
    } else {
      from = 7;
      mask = 0b00000000;
    }
    byte[] bs = Longs.toByteArray(value);
    byte[] b = Arrays.copyOfRange(bs, from, 8);
    b[0] = (byte)(b[0] | mask);

    bb.writeBytes(b);
  }
}