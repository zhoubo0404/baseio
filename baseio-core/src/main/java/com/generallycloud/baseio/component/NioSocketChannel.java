/*
 * Copyright 2015-2017 GenerallyCloud.com
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.generallycloud.baseio.component;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import com.generallycloud.baseio.buffer.ByteBuf;
import com.generallycloud.baseio.buffer.ByteBufAllocator;
import com.generallycloud.baseio.buffer.EmptyByteBuf;
import com.generallycloud.baseio.collection.Attributes;
import com.generallycloud.baseio.collection.AttributesImpl;
import com.generallycloud.baseio.common.CloseUtil;
import com.generallycloud.baseio.common.ReleaseUtil;
import com.generallycloud.baseio.common.StringUtil;
import com.generallycloud.baseio.common.ThrowableUtil;
import com.generallycloud.baseio.component.ChannelContext.HeartBeatLogger;
import com.generallycloud.baseio.component.ssl.SslHandler;
import com.generallycloud.baseio.concurrent.ExecutorEventLoop;
import com.generallycloud.baseio.concurrent.LinkedQueue;
import com.generallycloud.baseio.concurrent.ScspLinkedQueue;
import com.generallycloud.baseio.log.Logger;
import com.generallycloud.baseio.log.LoggerFactory;
import com.generallycloud.baseio.protocol.DefaultFuture;
import com.generallycloud.baseio.protocol.Future;
import com.generallycloud.baseio.protocol.ProtocolCodec;
import com.generallycloud.baseio.protocol.SslFuture;

public final class NioSocketChannel extends AttributesImpl
        implements NioEventLoopTask, Attributes, Closeable {

    private static final ClosedChannelException CLOSED_CHANNEL       = ThrowableUtil
            .unknownStackTrace(new ClosedChannelException(), NioSocketChannel.class,
                    "channel closed");
    private static final ClosedChannelException CLOSED_WHEN_FLUSH    = ThrowableUtil
            .unknownStackTrace(new ClosedChannelException(), NioSocketChannel.class, "flush(...)");
    private static final InetSocketAddress      ERROR_SOCKET_ADDRESS = new InetSocketAddress(0);
    private static final Logger                 logger               = LoggerFactory
            .getLogger(NioSocketChannel.class);
    private ByteBufAllocator                    allocator;
    private SocketChannel                       channel;
    private String                              channelDesc;
    private Integer                             channelId;
    private ReentrantLock                       closeLock            = new ReentrantLock();
    private ChannelContext                      context;
    private long                                creationTime         = System.currentTimeMillis();
    private Future[]                            currentWriteFutures;
    private int                                 currentWriteFuturesLen;
    private final boolean                       enableSsl;
    private final NioEventLoop                  eventLoop;
    private ExecutorEventLoop                   executorEventLoop;
    private long                                lastAccess;
    private String                              localAddr;
    private int                                 localPort;
    private boolean                             opened               = true;
    private ProtocolCodec                       protocolCodec;
    private transient Future                    readFuture;
    private ByteBuf                             remainingBuf;
    private String                              remoteAddr;
    private String                              remoteAddrPort;
    private int                                 remotePort;
    private final SelectionKey                  selectionKey;
    private SSLEngine                           sslEngine;
    private transient SslFuture                 sslReadFuture;
    private LinkedQueue<Future>                 writeFutures;
    private IoEventHandle                       ioEventHandle;

    NioSocketChannel(ChannelContext context, ByteBufAllocator allocator) {
        this.enableSsl = false;
        this.context = context;
        this.allocator = allocator;
        this.eventLoop = null;
        this.selectionKey = null;
    }

    NioSocketChannel(NioEventLoop eventLoop, SelectionKey selectionKey, ChannelContext context,
            int channelId) {
        NioEventLoopGroup group = eventLoop.getGroup();
        this.eventLoop = eventLoop;
        this.context = context;
        this.channelId = channelId;
        this.selectionKey = selectionKey;
        this.enableSsl = context.isEnableSsl();
        this.allocator = eventLoop.allocator();
        this.protocolCodec = context.getProtocolCodec();
        this.currentWriteFutures = new Future[group.getWriteBuffers()];
        this.executorEventLoop = context.getExecutorEventLoopGroup().getNext();
        this.channel = (SocketChannel) selectionKey.channel();
        this.lastAccess = creationTime + group.getIdleTime();
        this.writeFutures = new ScspLinkedQueue<>(new DefaultFuture(EmptyByteBuf.get()));
    }

    private void accept(ByteBuf buffer) throws Exception {
        final ProtocolCodec codec = this.protocolCodec;
        final NioEventLoop eventLoop = this.eventLoop;
        final IoEventHandle eventHandle = this.ioEventHandle;
        final ByteBufAllocator allocator = this.allocator;
        final HeartBeatLogger heartBeatLogger = context.getHeartBeatLogger();
        final boolean enableWorkEventLoop = context.isEnableWorkEventLoop();
        Future future = getReadFuture();
        if (future == null) {
            future = codec.decode(this, buffer);
        }
        try {
            for (;;) {
                if (!future.read(this, buffer)) {
                    setReadFuture(future);
                    if (buffer.hasRemaining()) {
                        ByteBuf remaining = allocator.allocate(buffer.remaining());
                        remaining.read(buffer);
                        remaining.flip();
                        setRemainingBuf(remaining);
                    }
                    break;
                }
                future.release(eventLoop);
                if (future.isSilent()) {
                    if (!buffer.hasRemaining()) {
                        setReadFuture(null);
                        break;
                    }
                } else if (future.isHeartbeat()) {
                    if (future.isPING()) {
                        heartBeatLogger.logRequest(this);
                        Future f = codec.createPONGPacket(this, future);
                        if (f == null) {
                            return;
                        }
                        this.flush(f);
                    } else {
                        heartBeatLogger.logResponse(this);
                    }
                } else {
                    if (enableWorkEventLoop) {
                        accept(eventHandle, future);
                    } else {
                        try {
                            eventHandle.accept(this, future);
                        } catch (Exception e) {
                            eventHandle.exceptionCaught(this, future, e);
                        }
                    }
                }
                if (!buffer.hasRemaining()) {
                    setReadFuture(null);
                    break;
                }
                future = codec.decode(this, buffer);
            }
        } catch (Throwable e) {
            future.release(eventLoop);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("exception occurred when do decode," + e.getMessage(), e);
        }
    }

    private void accept(final IoEventHandle eventHandle, final Future future) {
        getExecutorEventLoop().dispatch(new Runnable() {
            @Override
            public void run() {
                try {
                    eventHandle.accept(NioSocketChannel.this, future);
                } catch (Exception e) {
                    eventHandle.exceptionCaught(NioSocketChannel.this, future, e);
                }
            }
        });

    }

    public ByteBufAllocator allocator() {
        return allocator;
    }

    @Override
    public void close() throws IOException {
        if (!isOpened()) {
            return;
        }
        if (inEventLoop()) {
            close0();
        } else {
            eventLoop.dispatch(new NioEventLoopTask() {

                @Override
                public void fireEvent(NioEventLoop eventLoop) {
                    NioSocketChannel.this.close0();
                }
            });
        }
    }

    private void close0() {
        if (!isOpened()) {
            return;
        }
        ReentrantLock lock = getCloseLock();
        lock.lock();
        try {
            closeSSL();
            try {
                write();
            } catch (Exception e) {}
            releaseFutures();
            CloseUtil.close(channel);
            selectionKey.attach(null);
            selectionKey.cancel();
            fireClosed();
            opened = false;
        } finally {
            lock.unlock();
        }
    }

    private void closeSSL() {
        if (isEnableSsl()) {
            sslEngine.closeOutbound();
            if (getContext().getSslContext().isClient()) {
                writeFutures.offer(new DefaultFuture(EmptyByteBuf.get(), true));
            }
            try {
                sslEngine.closeInbound();
            } catch (SSLException e) {}
        }
    }

    private void exceptionCaught(Future future, Exception ex) {
        future.release(eventLoop);
        try {
            getIoEventHandle().exceptionCaught(this, future, ex);
        } catch (Throwable e) {
            logger.error(ex.getMessage(), ex);
            logger.error(e.getMessage(), e);
        }
    }

    public void finishHandshake(Exception e) {
        if (getContext().getSslContext().isClient()) {
            ChannelConnector connector = (ChannelConnector) getContext().getChannelService();
            connector.finishConnect(this, e);
        }
    }

    private void fireClosed() {
        NioSocketChannel channel = this;
        eventLoop.removeChannel(channel);
        for (ChannelEventListener l : getContext().getChannelEventListeners()) {
            try {
                l.channelClosed(channel);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public void fireEvent(NioEventLoop eventLoop) throws IOException {
        if (!isOpened()) {
            close();
            return;
        }
        write();
    }

    public void fireOpend() {
        //FIXME ..如果这时候连接关闭了如何处理
        //请勿使用remote.getRemoteHost(),可能出现阻塞
        InetSocketAddress remote = getRemoteSocketAddress0();
        InetSocketAddress local = getLocalSocketAddress0();
        remoteAddr = remote.getAddress().getHostAddress();
        remotePort = remote.getPort();
        remoteAddrPort = remoteAddr + ":" + remotePort;
        localAddr = local.getAddress().getHostAddress();
        localPort = local.getPort();
        ChannelContext context = getContext();
        if (context.isEnableSsl()) {
            this.sslEngine = context.getSslContext().newEngine(remoteAddr, remotePort);
        }
        if (isEnableSsl() && context.getSslContext().isClient()) {
            flushFuture(new DefaultFuture(EmptyByteBuf.get(), true));
        }
        eventLoop.putChannel(this);
        for (ChannelEventListener l : getContext().getChannelEventListeners()) {
            try {
                l.channelOpened(this);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        if (ioEventHandle == null) {
            ioEventHandle = context.getIoEventHandle();
        }
    }

    /**
     * flush未encode的future
     * @param f
     */
    public void flush(Future future) {
        if (future == null || future.flushed()) {
            return;
        }
        if (!isOpened()) {
            exceptionCaught(future, CLOSED_WHEN_FLUSH);
            return;
        }
        try {
            future.flush();
            future.setNeedSsl(context.isEnableSsl());
            getProtocolCodec().encode(this, future);
            flushFuture(future);
        } catch (Exception e) {
            exceptionCaught(future, e);
        }
    }

    public void flush(List<Future> futures) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        if (!isOpened()) {
            for (Future f : futures) {
                exceptionCaught(f, CLOSED_WHEN_FLUSH);
            }
            return;
        }
        try {
            boolean enableSsl = getContext().isEnableSsl();
            ProtocolCodec codec = getProtocolCodec();
            for (Future f : futures) {
                f.flush();
                f.setNeedSsl(enableSsl);
                codec.encode(this, f);
            }
        } catch (Exception e) {
            for (Future f : futures) {
                exceptionCaught(f, e);
            }
            CloseUtil.close(this);
            return;
        }
        flushFutures(futures);
    }

    /**
     * flush已encode的future
     * @param future
     */
    public void flushFuture(Future future) {
        final LinkedQueue<Future> writeFutures = this.writeFutures;
        if (inEventLoop()) {
            if (!isOpened()) {
                exceptionCaught(future, CLOSED_WHEN_FLUSH);
                return;
            }
            if (currentWriteFuturesLen == 0 && writeFutures.size() == 0) {
                write(future);
            } else {
                writeFutures.offer(future);
                try {
                    write();
                } catch (Throwable t) {
                    CloseUtil.close(this);
                }
            }
        } else {
            ReentrantLock lock = getCloseLock();
            lock.lock();
            try {
                if (!isOpened()) {
                    exceptionCaught(future, CLOSED_WHEN_FLUSH);
                    return;
                }
                writeFutures.offer(future);
                if (writeFutures.size() != 1) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            eventLoop.dispatch(this);
        }
    }

    //FIXME ..使用该方法貌似会性能下降？查找原因
    public void flushFutures(List<Future> futures) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        if (inEventLoop()) {
            if (!isOpened()) {
                for (Future f : futures) {
                    exceptionCaught(f, CLOSED_WHEN_FLUSH);
                }
                return;
            }
            final int futuresSize = futures.size();
            final LinkedQueue<Future> writeFutures = this.writeFutures;
            if (writeFutures.size() == 0) {
                final Future[] currentWriteFutures = this.currentWriteFutures;
                final int maxLen = currentWriteFutures.length;
                int currentWriteFuturesLen = this.currentWriteFuturesLen;
                if (currentWriteFuturesLen == 0) {
                    if (futuresSize > maxLen) {
                        for (int i = 0; i < maxLen; i++) {
                            currentWriteFutures[i] = futures.get(i);
                        }
                        for (int i = maxLen; i < futuresSize; i++) {
                            writeFutures.offer(futures.get(i));
                        }
                        this.currentWriteFuturesLen = maxLen;
                    } else {
                        for (int i = 0; i < futuresSize; i++) {
                            currentWriteFutures[i] = futures.get(i);
                        }
                        this.currentWriteFuturesLen = futuresSize;
                    }
                } else {
                    final int currentRemain = maxLen - currentWriteFuturesLen;
                    if (futuresSize > currentRemain) {
                        for (int i = 0; i < currentRemain; i++) {
                            currentWriteFutures[i + currentWriteFuturesLen] = futures.get(i);
                        }
                        for (int i = currentRemain; i < futuresSize; i++) {
                            writeFutures.offer(futures.get(i));
                        }
                        this.currentWriteFuturesLen = maxLen;
                    } else {
                        for (int i = 0; i < futuresSize; i++) {
                            currentWriteFutures[i + currentWriteFuturesLen] = futures.get(i);
                        }
                        this.currentWriteFuturesLen += futuresSize;
                    }
                }
                try {
                    write();
                } catch (Throwable t) {
                    CloseUtil.close(this);
                }
            } else {
                for (Future f : futures) {
                    writeFutures.offer(f);
                }
                try {
                    write();
                } catch (Throwable t) {
                    CloseUtil.close(this);
                }
            }
        } else {
            ReentrantLock lock = getCloseLock();
            lock.lock();
            try {
                if (!isOpened()) {
                    for (Future f : futures) {
                        exceptionCaught(f, CLOSED_WHEN_FLUSH);
                    }
                    return;
                }
                final LinkedQueue<Future> writeFutures = this.writeFutures;
                for (Future f : futures) {
                    writeFutures.offer(f);
                }
                if (writeFutures.size() != futures.size()) {
                    return;
                }
            } catch (Exception e) {
                //will happen ?
                for (Future f : futures) {
                    exceptionCaught(f, e);
                }
                return;
            } finally {
                lock.unlock();
            }
            eventLoop.dispatch(this);
        }
    }

    public Integer getChannelId() {
        return channelId;
    }

    private ReentrantLock getCloseLock() {
        return closeLock;
    }

    public ChannelContext getContext() {
        return context;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public Charset getEncoding() {
        return getContext().getEncoding();
    }

    public NioEventLoop getEventLoop() {
        return eventLoop;
    }

    public ExecutorEventLoop getExecutorEventLoop() {
        return executorEventLoop;
    }
    
    public IoEventHandle getIoEventHandle() {
        return ioEventHandle;
    }

    public long getLastAccessTime() {
        return lastAccess;
    }

    public String getLocalAddr() {
        return localAddr;
    }

    public int getLocalPort() {
        return localPort;
    }

    private InetSocketAddress getLocalSocketAddress0() {
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException e) {
            return ERROR_SOCKET_ADDRESS;
        }
    }

    public <T> T getOption(SocketOption<T> name) throws IOException {
        return channel.getOption(name);
    }

    public ProtocolCodec getProtocolCodec() {
        return protocolCodec;
    }

    public Object getProtocolId() {
        return protocolCodec.getProtocolId();
    }

    public Future getReadFuture() {
        return readFuture;
    }

    public ByteBuf getRemainingBuf() {
        return remainingBuf;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public String getRemoteAddrPort() {
        return remoteAddrPort;
    }

    public int getRemotePort() {
        return remotePort;
    }

    private InetSocketAddress getRemoteSocketAddress0() {
        try {
            return (InetSocketAddress) channel.getRemoteAddress();
        } catch (Exception e) {}
        return ERROR_SOCKET_ADDRESS;
    }

    public SSLEngine getSSLEngine() {
        return sslEngine;
    }

    public SslHandler getSslHandler() {
        return eventLoop.getSslHandler();
    }

    public SslFuture getSslReadFuture() {
        return sslReadFuture;
    }

    public int getWriteFutureSize() {
        return writeFutures.size();
    }

    @Override
    public int hashCode() {
        return remoteAddrPort.hashCode();
    }

    public boolean inEventLoop() {
        return eventLoop.inEventLoop();
    }

    private void interestRead(SelectionKey key) {
        if (SelectionKey.OP_READ != key.interestOps()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void interestWrite(SelectionKey key) {
        if ((SelectionKey.OP_READ | SelectionKey.OP_WRITE) != key.interestOps()) {
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    public boolean isBlocking() {
        return channel.isBlocking();
    }

    public boolean isClosed() {
        return !opened;
    }

    public boolean isEnableSsl() {
        return enableSsl;
    }

    public boolean isOpened() {
        return opened;
    }

    protected void read(ByteBuf buf) throws Exception {
        lastAccess = System.currentTimeMillis();
        buf.clear();
        if (!isEnableSsl()) {
            readRemainingBuf(buf);
        }
        int length = channel.read(buf.nioBuffer());
        if (length < 1) {
            if (length == -1) {
                CloseUtil.close(this);
            }
            return;
        }
        buf.reverse();
        buf.flip();
        if (enableSsl) {
            SslFuture sslTemporary = eventLoop.getSslTemporary();
            for (;;) {
                if (!buf.hasRemaining()) {
                    return;
                }
                SslFuture future = getSslReadFuture();
                boolean setFutureNull = true;
                if (future == null) {
                    future = sslTemporary.reset();
                    setFutureNull = false;
                }
                if (!future.read(this, buf)) {
                    if (!setFutureNull) {
                        if (future == sslTemporary) {
                            future = future.copy(this);
                        }
                        this.setSslReadFuture(future);
                    }
                    return;
                }
                if (setFutureNull) {
                    this.setSslReadFuture(null);
                }
                SslHandler sslHandler = getSslHandler();
                ByteBuf product;
                try {
                    product = sslHandler.unwrap(this, future.getByteBuf());
                } finally {
                    future.release(eventLoop);
                }
                if (product == null) {
                    continue;
                }
                accept(product);
            }
        } else {
            accept(buf);
        }
    }

    public void readRemainingBuf(ByteBuf dst) {
        ByteBuf remainingBuf = this.remainingBuf;
        if (remainingBuf == null) {
            return;
        }
        dst.read(remainingBuf);
        remainingBuf.release(remainingBuf.getReleaseVersion());
        this.remainingBuf = null;
    }

    private void releaseFutures() {
        if (currentWriteFuturesLen > 0) {
            for (int i = 0; i < currentWriteFuturesLen; i++) {
                exceptionCaught(currentWriteFutures[i], CLOSED_CHANNEL);
            }
        }
        NioEventLoop eventLoop = this.eventLoop;
        ReleaseUtil.release(readFuture, eventLoop);
        ReleaseUtil.release(sslReadFuture, eventLoop);
        ReleaseUtil.release(remainingBuf);
        LinkedQueue<Future> writeFutures = this.writeFutures;
        if (writeFutures.size() == 0) {
            return;
        }
        Future future = writeFutures.poll();
        for (; future != null;) {
            exceptionCaught(future, CLOSED_CHANNEL);
            ReleaseUtil.release(future, eventLoop);
            future = writeFutures.poll();
        }
    }

    public <T> void setOption(SocketOption<T> name, T value) throws IOException {
        channel.setOption(name, value);
    }

    public void setProtocolCodec(ProtocolCodec protocolCodec) {
        this.protocolCodec = protocolCodec;
    }

    // FIXME 这里有问题

    public void setReadFuture(Future readFuture) {
        this.readFuture = readFuture;
    }

    public void setRemainingBuf(ByteBuf remainingBuf) {
        this.remainingBuf = remainingBuf;
    }
    
    public void setIoEventHandle(IoEventHandle ioEventHandle) {
        this.ioEventHandle = ioEventHandle;
    }

    public void setSslReadFuture(SslFuture future) {
        this.sslReadFuture = future;
    }

    @Override
    public String toString() {
        if (channelDesc == null) {
            String idStr = Long.toHexString(channelId);
            idStr = "0x" + StringUtil.getZeroString(8 - idStr.length()) + idStr;
            channelDesc = new StringBuilder("[Id(").append(idStr).append(")R/")
                    .append(getRemoteAddr()).append(":").append(getRemotePort()).append("; L:")
                    .append(getLocalPort()).append("]").toString();
        }
        return channelDesc;
    }

    protected void write() throws IOException {
        final NioEventLoop eventLoop = this.eventLoop;
        final SelectionKey selectionKey = this.selectionKey;
        final Future[] currentWriteFutures = this.currentWriteFutures;
        final LinkedQueue<Future> writeFutures = this.writeFutures;
        final ByteBuffer[] writeBuffers = eventLoop.getWriteBuffers();
        final int maxLen = currentWriteFutures.length;
        for (;;) {
            int currentWriteFuturesLen = this.currentWriteFuturesLen;
            for (; currentWriteFuturesLen < maxLen; currentWriteFuturesLen++) {
                Future future = writeFutures.poll();
                if (future == null) {
                    break;
                }
                currentWriteFutures[currentWriteFuturesLen] = future;
            }
            if (currentWriteFuturesLen == 0) {
                interestRead(selectionKey);
                return;
            }
            //FIXME ...是否要清空buffers
            for (int i = 0; i < currentWriteFuturesLen; i++) {
                Future future = currentWriteFutures[i];
                if (future.isNeedSsl()) {
                    future.setNeedSsl(false);
                    // FIXME 部分情况下可以不在业务线程做wrapssl
                    ByteBuf old = future.getByteBuf();
                    long version = old.getReleaseVersion();
                    SslHandler handler = eventLoop.getSslHandler();
                    try {
                        ByteBuf newBuf = handler.wrap(this, old);
                        newBuf.nioBuffer();
                        future.setByteBuf(newBuf);
                    } finally {
                        old.release(version);
                    }
                }
                writeBuffers[i] = future.getByteBuf().nioBuffer();
            }
            if (currentWriteFuturesLen == 1) {
                ByteBuffer nioBuf = writeBuffers[0];
                channel.write(nioBuf);
                if (nioBuf.hasRemaining()) {
                    currentWriteFutures[0].getByteBuf().reverse();
                    interestWrite(selectionKey);
                    return;
                } else {
                    Future future = currentWriteFutures[0];
                    currentWriteFutures[0] = null;
                    try {
                        future.release(eventLoop);
                    } catch (Throwable e) {
                        logger.error(e.getMessage(), e);
                    }
                    this.currentWriteFuturesLen = 0;
                    interestRead(selectionKey);
                    return;
                }
            } else {
                channel.write(writeBuffers, 0, currentWriteFuturesLen);
                for (int i = 0; i < currentWriteFuturesLen; i++) {
                    Future future = currentWriteFutures[i];
                    if (writeBuffers[i].hasRemaining()) {
                        int remain = currentWriteFuturesLen - i;
                        if (remain > 16) {
                            System.arraycopy(currentWriteFutures, i, currentWriteFutures, 0,
                                    remain);
                        } else {
                            for (int j = 0; j < remain; j++) {
                                currentWriteFutures[j] = currentWriteFutures[i + j];
                            }
                        }
                        for (int j = currentWriteFuturesLen - i; j < maxLen; j++) {
                            currentWriteFutures[j] = null;
                        }
                        future.getByteBuf().reverse();
                        this.currentWriteFuturesLen = remain;
                        interestWrite(selectionKey);
                        return;
                    } else {
                        try {
                            future.release(eventLoop);
                        } catch (Throwable e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                for (int j = 0; j < currentWriteFuturesLen; j++) {
                    currentWriteFutures[j] = null;
                }
                if (currentWriteFuturesLen != maxLen) {
                    this.currentWriteFuturesLen = 0;
                    interestRead(selectionKey);
                    return;
                }
                this.currentWriteFuturesLen = 0;
            }
        }
    }

    private void write(Future future) {
        try {
            if (future.isNeedSsl()) {
                future.setNeedSsl(false);
                // FIXME 部分情况下可以不在业务线程做wrapssl
                ByteBuf old = future.getByteBuf();
                long version = old.getReleaseVersion();
                SslHandler handler = eventLoop.getSslHandler();
                try {
                    ByteBuf newBuf = handler.wrap(this, old);
                    newBuf.nioBuffer();
                    future.setByteBuf(newBuf);
                } finally {
                    old.release(version);
                }
            }
            ByteBuf buf = future.getByteBuf();
            channel.write(buf.nioBuffer());
            buf.reverse();
            if (buf.hasRemaining()) {
                currentWriteFuturesLen = 1;
                currentWriteFutures[0] = future;
                interestWrite(selectionKey);
                return;
            } else {
                try {
                    future.release(eventLoop);
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
                interestRead(selectionKey);
            }
        } catch (Exception e) {
            CloseUtil.close(this);
            exceptionCaught(future, e);
        }
    }

}
