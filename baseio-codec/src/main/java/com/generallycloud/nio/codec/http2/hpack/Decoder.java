package com.generallycloud.nio.codec.http2.hpack;

import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.MAX_HEADER_LIST_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.MAX_HEADER_TABLE_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.MIN_HEADER_LIST_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.MIN_HEADER_TABLE_SIZE;
import static com.generallycloud.nio.codec.http2.hpack.Http2CodecUtil.headerListSizeExceeded;
import static com.generallycloud.nio.codec.http2.hpack.Http2Error.COMPRESSION_ERROR;
import static com.generallycloud.nio.codec.http2.hpack.Http2Error.PROTOCOL_ERROR;
import static com.generallycloud.nio.codec.http2.hpack.Http2Exception.connectionError;
import static com.generallycloud.nio.codec.http2.hpack.ThrowableUtil.unknownStackTrace;

import com.generallycloud.nio.buffer.ByteBuf;
import com.generallycloud.nio.codec.http2.future.Http2Header;
import com.generallycloud.nio.codec.http2.hpack.HpackUtil.IndexType;

public final class Decoder {
    private static final Http2Exception DECODE_DECOMPRESSION_EXCEPTION = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - decompression failure"), Decoder.class, "decode(...)");
    private static final Http2Exception DECODE_ULE_128_DECOMPRESSION_EXCEPTION = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - decompression failure"), Decoder.class, "decodeULE128(...)");
    private static final Http2Exception DECODE_ILLEGAL_INDEX_VALUE = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - illegal index value"), Decoder.class, "decode(...)");
    private static final Http2Exception INDEX_HEADER_ILLEGAL_INDEX_VALUE = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - illegal index value"), Decoder.class, "indexHeader(...)");
    private static final Http2Exception READ_NAME_ILLEGAL_INDEX_VALUE = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - illegal index value"), Decoder.class, "readName(...)");
    private static final Http2Exception INVALID_MAX_DYNAMIC_TABLE_SIZE = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - invalid max dynamic table size"), Decoder.class,
            "setDynamicTableSize(...)");
    private static final Http2Exception MAX_DYNAMIC_TABLE_SIZE_CHANGE_REQUIRED = unknownStackTrace(
            connectionError(COMPRESSION_ERROR, "HPACK - max dynamic table size change required"), Decoder.class,
            "decode(...)");
    private static final byte READ_HEADER_REPRESENTATION = 0;
    private static final byte READ_MAX_DYNAMIC_TABLE_SIZE = 1;
    private static final byte READ_INDEXED_HEADER = 2;
    private static final byte READ_INDEXED_HEADER_NAME = 3;
    private static final byte READ_LITERAL_HEADER_NAME_LENGTH_PREFIX = 4;
    private static final byte READ_LITERAL_HEADER_NAME_LENGTH = 5;
    private static final byte READ_LITERAL_HEADER_NAME = 6;
    private static final byte READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX = 7;
    private static final byte READ_LITERAL_HEADER_VALUE_LENGTH = 8;
    private static final byte READ_LITERAL_HEADER_VALUE = 9;

    private final DynamicTable dynamicTable;
    private final HuffmanDecoder huffmanDecoder;
    private long maxHeaderListSize;
    private long maxDynamicTableSize;
    private long encoderMaxDynamicTableSize;
    private boolean maxDynamicTableSizeChangeRequired;
    private String	EMPTY_STRING = "";

    public Decoder() {
        this(32);
    }

    public Decoder(int initialHuffmanDecodeCapacity) {
        this(initialHuffmanDecodeCapacity, DEFAULT_HEADER_TABLE_SIZE);
    }

    /**
     * Exposed Used for testing only! Default values used in the initial settings frame are overriden intentionally
     * for testing but violate the RFC if used outside the scope of testing.
     */
    Decoder(int initialHuffmanDecodeCapacity, int maxHeaderTableSize) {
        maxHeaderListSize = DEFAULT_HEADER_LIST_SIZE;
        maxDynamicTableSize = encoderMaxDynamicTableSize = maxHeaderTableSize;
        maxDynamicTableSizeChangeRequired = false;
        dynamicTable = new DynamicTable(maxHeaderTableSize);
        huffmanDecoder = new HuffmanDecoder(initialHuffmanDecodeCapacity);
    }

    /**
     * Decode the header block into header fields.
     * <p>
     * This method assumes the entire header block is contained in {@code in}.
     */
    public void decode(int streamId, ByteBuf in, Http2Headers headers) throws Http2Exception {
        int index = 0;
        long headersLength = 0;
        int nameLength = 0;
        int valueLength = 0;
        byte state = READ_HEADER_REPRESENTATION;
        boolean huffmanEncoded = false;
        String name = null;
        IndexType indexType = IndexType.NONE;
        while (in.hasRemaining()) {
            switch (state) {
                case READ_HEADER_REPRESENTATION:
                    byte b = in.get();
                    if (maxDynamicTableSizeChangeRequired && (b & 0xE0) != 0x20) {
                        // Encoder MUST signal maximum dynamic table size change
                        throw MAX_DYNAMIC_TABLE_SIZE_CHANGE_REQUIRED;
                    }
                    if (b < 0) {
                        // Indexed Header Field
                        index = b & 0x7F;
                        switch (index) {
                            case 0:
                                throw DECODE_ILLEGAL_INDEX_VALUE;
                            case 0x7F:
                                state = READ_INDEXED_HEADER;
                                break;
                            default:
                                headersLength = indexHeader(streamId, index, headers, headersLength);
                        }
                    } else if ((b & 0x40) == 0x40) {
                        // Literal Header Field with Incremental Indexing
                        indexType = IndexType.INCREMENTAL;
                        index = b & 0x3F;
                        switch (index) {
                            case 0:
                                state = READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
                                break;
                            case 0x3F:
                                state = READ_INDEXED_HEADER_NAME;
                                break;
                            default:
                                // Index was stored as the prefix
                                name = readName(index);
                                state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                        }
                    } else if ((b & 0x20) == 0x20) {
                        // Dynamic Table Size Update
                        index = b & 0x1F;
                        if (index == 0x1F) {
                            state = READ_MAX_DYNAMIC_TABLE_SIZE;
                        } else {
                            setDynamicTableSize(index);
                            state = READ_HEADER_REPRESENTATION;
                        }
                    } else {
                        // Literal Header Field without Indexing / never Indexed
                        indexType = ((b & 0x10) == 0x10) ? IndexType.NEVER : IndexType.NONE;
                        index = b & 0x0F;
                        switch (index) {
                            case 0:
                                state = READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
                                break;
                            case 0x0F:
                                state = READ_INDEXED_HEADER_NAME;
                                break;
                            default:
                            // Index was stored as the prefix
                            name = readName(index);
                            state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                        }
                    }
                    break;

                case READ_MAX_DYNAMIC_TABLE_SIZE:
                    setDynamicTableSize(decodeULE128(in, index));
                    state = READ_HEADER_REPRESENTATION;
                    break;

                case READ_INDEXED_HEADER:
                    headersLength = indexHeader(streamId, decodeULE128(in, index), headers, headersLength);
                    state = READ_HEADER_REPRESENTATION;
                    break;

                case READ_INDEXED_HEADER_NAME:
                    // Header Name matches an entry in the Header Table
                    name = readName(decodeULE128(in, index));
                    state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                    break;

                case READ_LITERAL_HEADER_NAME_LENGTH_PREFIX:
                    b = in.get();
                    huffmanEncoded = (b & 0x80) == 0x80;
                    index = b & 0x7F;
                    if (index == 0x7f) {
                        state = READ_LITERAL_HEADER_NAME_LENGTH;
                    } else {
                        if (index > maxHeaderListSize - headersLength) {
                            headerListSizeExceeded(streamId, maxHeaderListSize);
                        }
                        nameLength = index;
                        state = READ_LITERAL_HEADER_NAME;
                    }
                    break;

                case READ_LITERAL_HEADER_NAME_LENGTH:
                    // Header Name is a Literal String
                    nameLength = decodeULE128(in, index);

                    if (nameLength > maxHeaderListSize - headersLength) {
                        headerListSizeExceeded(streamId, maxHeaderListSize);
                    }
                    state = READ_LITERAL_HEADER_NAME;
                    break;

                case READ_LITERAL_HEADER_NAME:
                    // Wait until entire name is readable
                    if (in.remaining() < nameLength) {
                        throw notEnoughDataException(in);
                    }

                    name = readStringLiteral(in, nameLength, huffmanEncoded);

                    state = READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                    break;

                case READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX:
                    b = in.get();
                    huffmanEncoded = (b & 0x80) == 0x80;
                    index = b & 0x7F;
                    switch (index) {
                        case 0x7f:
                            state = READ_LITERAL_HEADER_VALUE_LENGTH;
                            break;
                        case 0:
                            headersLength = insertHeader(streamId, headers, name, EMPTY_STRING, indexType,
                                                         headersLength);
                            state = READ_HEADER_REPRESENTATION;
                            break;
                        default:
                            // Check new header size against max header size
                            if ((long) index + nameLength > maxHeaderListSize - headersLength) {
                                headerListSizeExceeded(streamId, maxHeaderListSize);
                            }
                            valueLength = index;
                            state = READ_LITERAL_HEADER_VALUE;
                    }

                    break;

                case READ_LITERAL_HEADER_VALUE_LENGTH:
                    // Header Value is a Literal String
                    valueLength = decodeULE128(in, index);

                    // Check new header size against max header size
                    if ((long) valueLength + nameLength > maxHeaderListSize - headersLength) {
                        headerListSizeExceeded(streamId, maxHeaderListSize);
                    }
                    state = READ_LITERAL_HEADER_VALUE;
                    break;

                case READ_LITERAL_HEADER_VALUE:
                    // Wait until entire value is readable
                    if (in.remaining() < valueLength) {
                        throw notEnoughDataException(in);
                    }

                    String value = readStringLiteral(in, valueLength, huffmanEncoded);
                    headersLength = insertHeader(streamId, headers, name, value, indexType, headersLength);
                    state = READ_HEADER_REPRESENTATION;
                    break;

                default:
                    throw new Error("should not reach here state: " + state);
            }
        }
    }

    /**
     * Set the maximum table size. If this is below the maximum size of the dynamic table used by
     * the encoder, the beginning of the next header block MUST signal this change.
     */
    public void setMaxHeaderTableSize(long maxHeaderTableSize) throws Http2Exception {
        if (maxHeaderTableSize < MIN_HEADER_TABLE_SIZE || maxHeaderTableSize > MAX_HEADER_TABLE_SIZE) {
            throw connectionError(PROTOCOL_ERROR, "Header Table Size must be >= %d and <= %d but was %d",
                    MIN_HEADER_TABLE_SIZE, MAX_HEADER_TABLE_SIZE, maxHeaderTableSize);
        }
        maxDynamicTableSize = maxHeaderTableSize;
        if (maxDynamicTableSize < encoderMaxDynamicTableSize) {
            // decoder requires less space than encoder
            // encoder MUST signal this change
            maxDynamicTableSizeChangeRequired = true;
            dynamicTable.setCapacity(maxDynamicTableSize);
        }
    }

    public void setMaxHeaderListSize(long maxHeaderListSize) throws Http2Exception {
        if (maxHeaderListSize < MIN_HEADER_LIST_SIZE || maxHeaderListSize > MAX_HEADER_LIST_SIZE) {
            throw connectionError(PROTOCOL_ERROR, "Header List Size must be >= %d and <= %d but was %d",
                    MIN_HEADER_TABLE_SIZE, MAX_HEADER_TABLE_SIZE, maxHeaderListSize);
        }
        this.maxHeaderListSize = maxHeaderListSize;
    }

    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * Return the maximum table size. This is the maximum size allowed by both the encoder and the
     * decoder.
     */
    public long getMaxHeaderTableSize() {
        return dynamicTable.capacity();
    }

    /**
     * Return the number of header fields in the dynamic table. Exposed for testing.
     */
    int length() {
        return dynamicTable.length();
    }

    /**
     * Return the size of the dynamic table. Exposed for testing.
     */
    long size() {
        return dynamicTable.size();
    }

    /**
     * Return the header field at the given index. Exposed for testing.
     */
    Http2Header getHeaderField(int index) {
        return dynamicTable.getEntry(index + 1);
    }

    private void setDynamicTableSize(int dynamicTableSize) throws Http2Exception {
        if (dynamicTableSize > maxDynamicTableSize) {
            throw INVALID_MAX_DYNAMIC_TABLE_SIZE;
        }
        encoderMaxDynamicTableSize = dynamicTableSize;
        maxDynamicTableSizeChangeRequired = false;
        dynamicTable.setCapacity(dynamicTableSize);
    }

    private String readName(int index) throws Http2Exception {
        if (index <= StaticTable.length) {
            Http2Header headerField = StaticTable.getEntry(index);
            return headerField.getName();
        }
        if (index - StaticTable.length <= dynamicTable.length()) {
            Http2Header headerField = dynamicTable.getEntry(index - StaticTable.length);
            return headerField.getName();
        }
        throw READ_NAME_ILLEGAL_INDEX_VALUE;
    }

    private long indexHeader(int streamId, int index, Http2Headers headers, long headersLength) throws Http2Exception {
        if (index <= StaticTable.length) {
            Http2Header headerField = StaticTable.getEntry(index);
            return addHeader(streamId, headers, headerField.getName(), headerField.getValue(), headersLength);
        }
        if (index - StaticTable.length <= dynamicTable.length()) {
            Http2Header headerField = dynamicTable.getEntry(index - StaticTable.length);
            return addHeader(streamId, headers, headerField.getName(), headerField.getValue(), headersLength);
        }
        throw INDEX_HEADER_ILLEGAL_INDEX_VALUE;
    }

    private long insertHeader(int streamId, Http2Headers headers, String name, String value,
                              IndexType indexType, long headerSize) throws Http2Exception {
        headerSize = addHeader(streamId, headers, name, value, headerSize);

        switch (indexType) {
            case NONE:
            case NEVER:
                break;

            case INCREMENTAL:
                dynamicTable.add(new Http2Header(name, value));
                break;

            default:
                throw new Error("should not reach here");
        }

        return headerSize;
    }

    private long addHeader(int streamId, Http2Headers headers, String name, String value,
                           long headersLength) throws Http2Exception {
        headersLength += name.length() + value.length();
        if (headersLength > maxHeaderListSize) {
            headerListSizeExceeded(streamId, maxHeaderListSize);
        }
        headers.add(name, value);
        return headersLength;
    }

    private String readStringLiteral(ByteBuf in, int length, boolean huffmanEncoded) throws Http2Exception {
        if (huffmanEncoded) {
            return huffmanDecoder.decode(in, length);
        }
        byte[] buf = new byte[length];
        in.get(buf);
        return new String(buf);
    }

    private static IllegalArgumentException notEnoughDataException(ByteBuf in) {
        return new IllegalArgumentException("decode only works with an entire header block! " + in);
    }

    // Unsigned Little Endian Base 128 Variable-Length Integer Encoding
    private static int decodeULE128(ByteBuf in, int result) throws Http2Exception {
        assert result <= 0x7f && result >= 0;
        final int writerIndex = in.limit();
        for (int readerIndex = in.position(), shift = 0;
             readerIndex < writerIndex; ++readerIndex, shift += 7) {
            byte b = in.get(readerIndex);
            if (shift == 28 && ((b & 0x80) != 0 || b > 6)) {
                // the maximum value that can be represented by a signed 32 bit number is:
                // 0x7f + 0x7f + (0x7f << 7) + (0x7f << 14) + (0x7f << 21) + (0x6 << 28)
                // this means any more shifts will result in overflow so we should break out and throw an error.
                in.position(readerIndex + 1);
                break;
            }

            if ((b & 0x80) == 0) {
                in.position(readerIndex + 1);
                return result + ((b & 0x7F) << shift);
            }
            result += (b & 0x7F) << shift;
        }

        throw DECODE_ULE_128_DECOMPRESSION_EXCEPTION;
    }
}