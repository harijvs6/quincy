/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.protocol7.quincy.http3;

import static com.protocol7.quincy.http3.HpackUtil.equalsConstantTime;
import static com.protocol7.quincy.http3.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static com.protocol7.quincy.http3.Http2CodecUtil.MAX_HEADER_LIST_SIZE;
import static com.protocol7.quincy.http3.Http2CodecUtil.MAX_HEADER_TABLE_SIZE;
import static com.protocol7.quincy.http3.Http2CodecUtil.MIN_HEADER_LIST_SIZE;
import static com.protocol7.quincy.http3.Http2CodecUtil.MIN_HEADER_TABLE_SIZE;
import static com.protocol7.quincy.http3.Http2CodecUtil.headerListSizeExceeded;
import static com.protocol7.quincy.http3.Http2Error.PROTOCOL_ERROR;
import static com.protocol7.quincy.http3.Http2Exception.connectionError;
import static io.netty.util.internal.MathUtil.findNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

import com.protocol7.quincy.http3.HpackUtil.IndexType;
import com.protocol7.quincy.http3.Http2HeadersEncoder.SensitivityDetector;
import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import java.util.Arrays;
import java.util.Map;

final class HpackEncoder {
  // a linked hash map of header fields
  private final HeaderEntry[] headerFields;
  private final HeaderEntry head =
      new HeaderEntry(
          -1, AsciiString.EMPTY_STRING, AsciiString.EMPTY_STRING, Integer.MAX_VALUE, null);
  private final HpackHuffmanEncoder hpackHuffmanEncoder = new HpackHuffmanEncoder();
  private final byte hashMask;
  private final boolean ignoreMaxHeaderListSize;
  private long size;
  private long maxHeaderTableSize;
  private long maxHeaderListSize;

  /** Creates a new encoder. */
  HpackEncoder() {
    this(false);
  }

  /** Creates a new encoder. */
  HpackEncoder(final boolean ignoreMaxHeaderListSize) {
    this(ignoreMaxHeaderListSize, 16);
  }

  /** Creates a new encoder. */
  HpackEncoder(final boolean ignoreMaxHeaderListSize, final int arraySizeHint) {
    this.ignoreMaxHeaderListSize = ignoreMaxHeaderListSize;
    maxHeaderTableSize = DEFAULT_HEADER_TABLE_SIZE;
    maxHeaderListSize = MAX_HEADER_LIST_SIZE;
    // Enforce a bound of [2, 128] because hashMask is a byte. The max possible value of hashMask is
    // one less
    // than the length of this array, and we want the mask to be > 0.
    headerFields = new HeaderEntry[findNextPositivePowerOfTwo(max(2, min(arraySizeHint, 128)))];
    hashMask = (byte) (headerFields.length - 1);
    head.before = head.after = head;
  }

  /**
   * Encode the header field into the header block.
   *
   * <p><strong>The given {@link CharSequence}s must be immutable!</strong>
   */
  public void encodeHeaders(
      final int streamId,
      final ByteBuf out,
      final Http2Headers headers,
      final SensitivityDetector sensitivityDetector)
      throws Http2Exception {
    if (ignoreMaxHeaderListSize) {
      encodeHeadersIgnoreMaxHeaderListSize(out, headers, sensitivityDetector);
    } else {
      encodeHeadersEnforceMaxHeaderListSize(streamId, out, headers, sensitivityDetector);
    }
  }

  private void encodeHeadersEnforceMaxHeaderListSize(
      final int streamId,
      final ByteBuf out,
      final Http2Headers headers,
      final SensitivityDetector sensitivityDetector)
      throws Http2Exception {
    long headerSize = 0;
    // To ensure we stay consistent with our peer check the size is valid before we potentially
    // modify HPACK state.
    for (final Map.Entry<CharSequence, CharSequence> header : headers) {
      final CharSequence name = header.getKey();
      final CharSequence value = header.getValue();
      // OK to increment now and check for bounds after because this value is limited to unsigned
      // int and will not
      // overflow.
      headerSize += HpackHeaderField.sizeOf(name, value);
      if (headerSize > maxHeaderListSize) {
        headerListSizeExceeded(streamId, maxHeaderListSize, false);
      }
    }
    encodeHeadersIgnoreMaxHeaderListSize(out, headers, sensitivityDetector);
  }

  private void encodeHeadersIgnoreMaxHeaderListSize(
      final ByteBuf out, final Http2Headers headers, final SensitivityDetector sensitivityDetector)
      throws Http2Exception {
    for (final Map.Entry<CharSequence, CharSequence> header : headers) {
      final CharSequence name = header.getKey();
      final CharSequence value = header.getValue();
      encodeHeader(
          out,
          name,
          value,
          sensitivityDetector.isSensitive(name, value),
          HpackHeaderField.sizeOf(name, value));
    }
  }

  /**
   * Encode the header field into the header block.
   *
   * <p><strong>The given {@link CharSequence}s must be immutable!</strong>
   */
  private void encodeHeader(
      final ByteBuf out,
      final CharSequence name,
      final CharSequence value,
      final boolean sensitive,
      final long headerSize) {
    // If the header value is sensitive then it must never be indexed
    if (sensitive) {
      final int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, IndexType.NEVER, nameIndex);
      return;
    }

    // If the peer will only use the static table
    if (maxHeaderTableSize == 0) {
      final int staticTableIndex = HpackStaticTable.getIndex(name, value);
      if (staticTableIndex == -1) {
        final int nameIndex = HpackStaticTable.getIndex(name);
        encodeLiteral(out, name, value, IndexType.NONE, nameIndex);
      } else {
        encodeInteger(out, 0x80, 7, staticTableIndex);
      }
      return;
    }

    // If the headerSize is greater than the max table size then it must be encoded literally
    if (headerSize > maxHeaderTableSize) {
      final int nameIndex = getNameIndex(name);
      encodeLiteral(out, name, value, IndexType.NONE, nameIndex);
      return;
    }

    final HeaderEntry headerField = getEntry(name, value);
    if (headerField != null) {
      final int index = getIndex(headerField.index) + HpackStaticTable.length;
      // Section 6.1. Indexed Header Field Representation
      encodeInteger(out, 0x80, 7, index);
    } else {
      final int staticTableIndex = HpackStaticTable.getIndex(name, value);
      if (staticTableIndex != -1) {
        // Section 6.1. Indexed Header Field Representation
        encodeInteger(out, 0x80, 7, staticTableIndex);
      } else {
        ensureCapacity(headerSize);
        encodeLiteral(out, name, value, IndexType.INCREMENTAL, getNameIndex(name));
        add(name, value, headerSize);
      }
    }
  }

  /** Set the maximum table size. */
  public void setMaxHeaderTableSize(final ByteBuf out, final long maxHeaderTableSize)
      throws Http2Exception {
    if (maxHeaderTableSize < MIN_HEADER_TABLE_SIZE || maxHeaderTableSize > MAX_HEADER_TABLE_SIZE) {
      throw connectionError(
          PROTOCOL_ERROR,
          "Header Table Size must be >= %d and <= %d but was %d",
          MIN_HEADER_TABLE_SIZE,
          MAX_HEADER_TABLE_SIZE,
          maxHeaderTableSize);
    }
    if (this.maxHeaderTableSize == maxHeaderTableSize) {
      return;
    }
    this.maxHeaderTableSize = maxHeaderTableSize;
    ensureCapacity(0);
    // Casting to integer is safe as we verified the maxHeaderTableSize is a valid unsigned int.
    encodeInteger(out, 0x20, 5, maxHeaderTableSize);
  }

  /** Return the maximum table size. */
  public long getMaxHeaderTableSize() {
    return maxHeaderTableSize;
  }

  public void setMaxHeaderListSize(final long maxHeaderListSize) throws Http2Exception {
    if (maxHeaderListSize < MIN_HEADER_LIST_SIZE || maxHeaderListSize > MAX_HEADER_LIST_SIZE) {
      throw connectionError(
          PROTOCOL_ERROR,
          "Header List Size must be >= %d and <= %d but was %d",
          MIN_HEADER_LIST_SIZE,
          MAX_HEADER_LIST_SIZE,
          maxHeaderListSize);
    }
    this.maxHeaderListSize = maxHeaderListSize;
  }

  public long getMaxHeaderListSize() {
    return maxHeaderListSize;
  }

  /**
   * Encode integer according to <a href="https://tools.ietf.org/html/rfc7541#section-5.1">Section
   * 5.1</a>.
   */
  private static void encodeInteger(final ByteBuf out, final int mask, final int n, final int i) {
    encodeInteger(out, mask, n, (long) i);
  }

  /**
   * Encode integer according to <a href="https://tools.ietf.org/html/rfc7541#section-5.1">Section
   * 5.1</a>.
   */
  private static void encodeInteger(final ByteBuf out, final int mask, final int n, final long i) {
    assert n >= 0 && n <= 8 : "N: " + n;
    final int nbits = 0xFF >>> (8 - n);
    if (i < nbits) {
      out.writeByte((int) (mask | i));
    } else {
      out.writeByte(mask | nbits);
      long length = i - nbits;
      for (; (length & ~0x7F) != 0; length >>>= 7) {
        out.writeByte((int) ((length & 0x7F) | 0x80));
      }
      out.writeByte((int) length);
    }
  }

  /** Encode string literal according to Section 5.2. */
  private void encodeStringLiteral(final ByteBuf out, final CharSequence string) {
    final int huffmanLength = hpackHuffmanEncoder.getEncodedLength(string);
    if (huffmanLength < string.length()) {
      encodeInteger(out, 0x80, 7, huffmanLength);
      hpackHuffmanEncoder.encode(out, string);
    } else {
      encodeInteger(out, 0x00, 7, string.length());
      if (string instanceof AsciiString) {
        // Fast-path
        final AsciiString asciiString = (AsciiString) string;
        out.writeBytes(asciiString.array(), asciiString.arrayOffset(), asciiString.length());
      } else {
        // Only ASCII is allowed in http2 headers, so its fine to use this.
        // https://tools.ietf.org/html/rfc7540#section-8.1.2
        out.writeCharSequence(string, CharsetUtil.ISO_8859_1);
      }
    }
  }

  /** Encode literal header field according to Section 6.2. */
  private void encodeLiteral(
      final ByteBuf out,
      final CharSequence name,
      final CharSequence value,
      final IndexType indexType,
      final int nameIndex) {
    final boolean nameIndexValid = nameIndex != -1;
    switch (indexType) {
      case INCREMENTAL:
        encodeInteger(out, 0x40, 6, nameIndexValid ? nameIndex : 0);
        break;
      case NONE:
        encodeInteger(out, 0x00, 4, nameIndexValid ? nameIndex : 0);
        break;
      case NEVER:
        encodeInteger(out, 0x10, 4, nameIndexValid ? nameIndex : 0);
        break;
      default:
        throw new Error("should not reach here");
    }
    if (!nameIndexValid) {
      encodeStringLiteral(out, name);
    }
    encodeStringLiteral(out, value);
  }

  private int getNameIndex(final CharSequence name) {
    int index = HpackStaticTable.getIndex(name);
    if (index == -1) {
      index = getIndex(name);
      if (index >= 0) {
        index += HpackStaticTable.length;
      }
    }
    return index;
  }

  /**
   * Ensure that the dynamic table has enough room to hold 'headerSize' more bytes. Removes the
   * oldest entry from the dynamic table until sufficient space is available.
   */
  private void ensureCapacity(final long headerSize) {
    while (maxHeaderTableSize - size < headerSize) {
      final int index = length();
      if (index == 0) {
        break;
      }
      remove();
    }
  }

  /** Return the number of header fields in the dynamic table. Exposed for testing. */
  int length() {
    return size == 0 ? 0 : head.after.index - head.before.index + 1;
  }

  /** Return the size of the dynamic table. Exposed for testing. */
  long size() {
    return size;
  }

  /** Return the header field at the given index. Exposed for testing. */
  HpackHeaderField getHeaderField(@SuppressWarnings("checkstyle:finalparameters") int index) {
    HeaderEntry entry = head;
    while (index-- >= 0) {
      entry = entry.before;
    }
    return entry;
  }

  /**
   * Returns the header entry with the lowest index value for the header field. Returns null if
   * header field is not in the dynamic table.
   */
  private HeaderEntry getEntry(final CharSequence name, final CharSequence value) {
    if (length() == 0 || name == null || value == null) {
      return null;
    }
    final int h = AsciiString.hashCode(name);
    final int i = index(h);
    for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
      // To avoid short circuit behavior a bitwise operator is used instead of a boolean operator.
      if (e.hash == h
          && (equalsConstantTime(name, e.name) & equalsConstantTime(value, e.value)) != 0) {
        return e;
      }
    }
    return null;
  }

  /**
   * Returns the lowest index value for the header field name in the dynamic table. Returns -1 if
   * the header field name is not in the dynamic table.
   */
  private int getIndex(final CharSequence name) {
    if (length() == 0 || name == null) {
      return -1;
    }
    final int h = AsciiString.hashCode(name);
    final int i = index(h);
    for (HeaderEntry e = headerFields[i]; e != null; e = e.next) {
      if (e.hash == h && equalsConstantTime(name, e.name) != 0) {
        return getIndex(e.index);
      }
    }
    return -1;
  }

  /** Compute the index into the dynamic table given the index in the header entry. */
  private int getIndex(final int index) {
    return index == -1 ? -1 : index - head.before.index + 1;
  }

  /**
   * Add the header field to the dynamic table. Entries are evicted from the dynamic table until the
   * size of the table and the new header field is less than the table's maxHeaderTableSize. If the
   * size of the new entry is larger than the table's maxHeaderTableSize, the dynamic table will be
   * cleared.
   */
  private void add(final CharSequence name, final CharSequence value, final long headerSize) {
    // Clear the table if the header field size is larger than the maxHeaderTableSize.
    if (headerSize > maxHeaderTableSize) {
      clear();
      return;
    }

    // Evict oldest entries until we have enough maxHeaderTableSize.
    while (maxHeaderTableSize - size < headerSize) {
      remove();
    }

    final int h = AsciiString.hashCode(name);
    final int i = index(h);
    final HeaderEntry old = headerFields[i];
    final HeaderEntry e = new HeaderEntry(h, name, value, head.before.index - 1, old);
    headerFields[i] = e;
    e.addBefore(head);
    size += headerSize;
  }

  /** Remove and return the oldest header field from the dynamic table. */
  private HpackHeaderField remove() {
    if (size == 0) {
      return null;
    }
    final HeaderEntry eldest = head.after;
    final int h = eldest.hash;
    final int i = index(h);
    HeaderEntry prev = headerFields[i];
    HeaderEntry e = prev;
    while (e != null) {
      final HeaderEntry next = e.next;
      if (e == eldest) {
        if (prev == eldest) {
          headerFields[i] = next;
        } else {
          prev.next = next;
        }
        eldest.remove();
        size -= eldest.size();
        return eldest;
      }
      prev = e;
      e = next;
    }
    return null;
  }

  /** Remove all entries from the dynamic table. */
  private void clear() {
    Arrays.fill(headerFields, null);
    head.before = head.after = head;
    size = 0;
  }

  /** Returns the index into the hash table for the hash code h. */
  private int index(final int h) {
    return h & hashMask;
  }

  /** A linked hash map HpackHeaderField entry. */
  private static final class HeaderEntry extends HpackHeaderField {
    // These fields comprise the doubly linked list used for iteration.
    HeaderEntry before, after;

    // These fields comprise the chained list for header fields with the same hash.
    HeaderEntry next;
    int hash;

    // This is used to compute the index in the dynamic table.
    int index;

    /** Creates new entry. */
    HeaderEntry(
        final int hash,
        final CharSequence name,
        final CharSequence value,
        final int index,
        final HeaderEntry next) {
      super(name, value);
      this.index = index;
      this.hash = hash;
      this.next = next;
    }

    /** Removes this entry from the linked list. */
    private void remove() {
      before.after = after;
      after.before = before;
      before = null; // null references to prevent nepotism in generational GC.
      after = null;
      next = null;
    }

    /** Inserts this entry before the specified existing entry in the list. */
    private void addBefore(final HeaderEntry existingEntry) {
      after = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
    }
  }
}
