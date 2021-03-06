/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
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
package org.reaktivity.nukleus.tls.internal.stream;

import static java.nio.ByteBuffer.allocateDirect;
import static java.util.Objects.requireNonNull;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NEED_WRAP;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
import static javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW;
import static org.agrona.LangUtil.rethrowUnchecked;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.nukleus.tls.internal.TlsConfiguration;
import org.reaktivity.nukleus.tls.internal.types.Flyweight;
import org.reaktivity.nukleus.tls.internal.types.OctetsFW;
import org.reaktivity.nukleus.tls.internal.types.control.RouteFW;
import org.reaktivity.nukleus.tls.internal.types.control.TlsRouteExFW;
import org.reaktivity.nukleus.tls.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.tls.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.tls.internal.types.stream.DataFW;
import org.reaktivity.nukleus.tls.internal.types.stream.EndFW;
import org.reaktivity.nukleus.tls.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.tls.internal.types.stream.TlsBeginExFW;
import org.reaktivity.nukleus.tls.internal.types.stream.WindowFW;

public final class ServerStreamFactory implements StreamFactory
{
    private static final ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.allocate(0);
    private static final int MAXIMUM_HEADER_SIZE = 5 + 20 + 256;    // TODO version + MAC + padding
    private static final int MAXIMUM_PAYLOAD_LENGTH = (1 << Short.SIZE) - 1;
    private static final LongConsumer NOP = (x) -> {};

    private final RouteFW routeRO = new RouteFW();
    private final TlsRouteExFW tlsRouteExRO = new TlsRouteExFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();

    private final TlsBeginExFW.Builder tlsBeginExRW = new TlsBeginExFW.Builder();

    private final OctetsFW outNetOctetsRO = new OctetsFW();
    private final OctetsFW outAppOctetsRO = new OctetsFW();

    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final SSLContext context;
    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final BufferPool networkPool;
    private final BufferPool applicationPool;
    private final LongSupplier supplyStreamId;
    private final LongSupplier supplyCorrelationId;
    private final int handshakeBudget;

    private final Long2ObjectHashMap<ServerHandshake> correlations;
    private final MessageFunction<RouteFW> wrapRoute;
    private final ByteBuffer inAppByteBuffer;
    private final ByteBuffer outAppByteBuffer;
    private final ByteBuffer outNetByteBuffer;
    private final DirectBuffer outNetBuffer;

    private final Function<RouteFW, LongSupplier> supplyWriteFrameCounter;
    private final Function<RouteFW, LongSupplier> supplyReadFrameCounter;
    private final Function<RouteFW, LongConsumer> supplyWriteBytesAccumulator;
    private final Function<RouteFW, LongConsumer> supplyReadBytesAccumulator;

    public ServerStreamFactory(
        TlsConfiguration config,
        SSLContext context,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongSupplier supplyStreamId,
        LongSupplier supplyCorrelationId,
        Long2ObjectHashMap<ServerHandshake> correlations,
        Function<RouteFW, LongSupplier> supplyReadFrameCounter,
        Function<RouteFW, LongConsumer> supplyReadBytesAccumulator,
        Function<RouteFW, LongSupplier> supplyWriteFrameCounter,
        Function<RouteFW, LongConsumer> supplyWriteBytesAccumulator)
    {
        this.context = requireNonNull(context);
        this.router = requireNonNull(router);
        this.writeBuffer = requireNonNull(writeBuffer);
        this.networkPool = requireNonNull(bufferPool);
        this.applicationPool = requireNonNull(bufferPool).duplicate();
        this.supplyStreamId = requireNonNull(supplyStreamId);
        this.supplyCorrelationId = requireNonNull(supplyCorrelationId);
        this.correlations = requireNonNull(correlations);
        this.handshakeBudget = Math.min(config.handshakeWindowBytes(), networkPool.slotCapacity());

        this.wrapRoute = this::wrapRoute;
        this.inAppByteBuffer = allocateDirect(writeBuffer.capacity());
        this.outAppByteBuffer = allocateDirect(writeBuffer.capacity());
        this.outNetByteBuffer = allocateDirect(Math.min(writeBuffer.capacity(), MAXIMUM_PAYLOAD_LENGTH));
        this.outNetBuffer = new UnsafeBuffer(outNetByteBuffer);

        this.supplyWriteFrameCounter = supplyWriteFrameCounter;
        this.supplyReadFrameCounter = supplyReadFrameCounter;
        this.supplyWriteBytesAccumulator = supplyWriteBytesAccumulator;
        this.supplyReadBytesAccumulator = supplyReadBytesAccumulator;
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer throttle)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long sourceRef = begin.sourceRef();

        MessageConsumer newStream;

        if (sourceRef == 0L)
        {
            newStream = newConnectReplyStream(begin, throttle);
        }
        else
        {
            newStream = newAcceptStream(begin, throttle);
        }

        return newStream;
    }

    private MessageConsumer newAcceptStream(
        final BeginFW begin,
        final MessageConsumer networkThrottle)
    {
        final long networkRef = begin.sourceRef();
        final String acceptName = begin.source().asString();
        final long authorization = begin.authorization();

        final MessagePredicate filter = (t, b, o, l) ->
        {
            final RouteFW route = routeRO.wrap(b, o, l);
            return networkRef == route.sourceRef() &&
                    acceptName.equals(route.source().asString());
        };

        final RouteFW route = router.resolve(authorization, filter, this::wrapRoute);

        MessageConsumer newStream = null;

        if (route != null)
        {
            final long networkId = begin.streamId();
            final SSLEngine tlsEngine = context.createSSLEngine();

            tlsEngine.setUseClientMode(false);
//            tlsEngine.setNeedClientAuth(true);

            final LongSupplier writeFrameCounter = supplyWriteFrameCounter.apply(route);
            final LongSupplier readFrameCounter = supplyReadFrameCounter.apply(route);
            final LongConsumer writeBytesAccumulator = supplyWriteBytesAccumulator.apply(route);
            final LongConsumer readBytesAccumulator = supplyReadBytesAccumulator.apply(route);

            newStream = new ServerAcceptStream(
                tlsEngine,
                networkThrottle,
                networkId,
                authorization,
                networkRef,
                writeFrameCounter,
                readFrameCounter,
                writeBytesAccumulator,
                readBytesAccumulator
                )::handleStream;
        }

        return newStream;
    }

    private MessageConsumer newConnectReplyStream(
        final BeginFW begin,
        final MessageConsumer throttle)
    {
        final long throttleId = begin.streamId();

        return new ServerConnectReplyStream(throttle, throttleId)::handleStream;
    }

    private RouteFW wrapRoute(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        return routeRO.wrap(buffer, index, index + length);
    }

    private final class ServerAcceptStream
    {
        private final SSLEngine tlsEngine;

        private final MessageConsumer networkThrottle;
        private final long networkId;
        private final long networkRef;

        private final LongSupplier writeFrameCounter;
        private final LongSupplier readFrameCounter;
        private final LongConsumer writeBytesAccumulator;
        private final LongConsumer readBytesAccumulator;

        private String networkReplyName;
        private MessageConsumer networkReply;
        private long networkReplyId;

        private int networkSlot = NO_SLOT;
        private int networkSlotOffset;

        private MessageConsumer applicationTarget;
        private long applicationId;
        private final long authorization;

        private MessageConsumer streamState;
        private ServerHandshake handshake;

        private int networkBudget;
        private int networkPadding;

        private int applicationSlot = NO_SLOT;
        private int applicationSlotOffset;

        private int applicationBudget;
        private int applicationPadding;
        private long applicationCorrelationId;

        private long networkCorrelationId;

        private LongConsumer networkReplyDoneHandler = NOP;
        private long networkTraceId;

        @Override
        public String toString()
        {
            return String.format("%s [networkBudget=%d networkPadding=%d]",
                                 getClass().getSimpleName(), networkBudget, networkPadding);
        }

        private ServerAcceptStream(
            SSLEngine tlsEngine,
            MessageConsumer networkThrottle,
            long networkId,
            long authorization,
            long networkRef,
            LongSupplier writeFrameCounter,
            LongSupplier readFrameCounter,
            LongConsumer writeBytesAccumulator,
            LongConsumer readBytesAccumulator)
        {
            this.tlsEngine = tlsEngine;
            this.networkThrottle = networkThrottle;
            this.networkId = networkId;
            this.authorization = authorization;
            this.networkRef = networkRef;
            this.writeFrameCounter = writeFrameCounter;
            this.readFrameCounter = readFrameCounter;
            this.writeBytesAccumulator = writeBytesAccumulator;
            this.readBytesAccumulator = readBytesAccumulator;
            this.streamState = this::beforeBegin;
        }

        private void handleStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                doReset(networkThrottle, networkId);
            }
        }

        private void handleBegin(
            BeginFW begin)
        {
            try
            {
                final String networkReplyName = begin.source().asString();
                this.networkCorrelationId = begin.correlationId();

                final MessageConsumer networkReply = router.supplyTarget(networkReplyName);
                final long newNetworkReplyId = supplyStreamId.getAsLong();

                final ServerHandshake newHandshake = new ServerHandshake(tlsEngine, networkThrottle, networkId,
                        networkReplyName, networkReply, newNetworkReplyId,
                        this::handleStatus,
                        this::handleNetworkReplyDone, this::setNetworkReplyDoneHandler,
                        this::getNetworkBudget, this::getNetworkPadding,
                        this::setNetworkBudget,
                        writeFrameCounter,
                        readFrameCounter,
                        writeBytesAccumulator,
                        readBytesAccumulator);

                networkBudget += handshakeBudget;
                doWindow(networkThrottle, networkId, networkBudget, networkPadding);

                doBegin(networkReply, newNetworkReplyId, 0L, 0L, networkCorrelationId);
                router.setThrottle(networkReplyName, newNetworkReplyId, newHandshake::handleThrottle);

                this.streamState = newHandshake::afterBegin;
                this.networkReplyName = networkReplyName;
                this.networkReply = networkReply;
                this.networkReplyId = newNetworkReplyId;
                this.handshake = newHandshake;

                tlsEngine.setHandshakeApplicationProtocolSelector(this::selectApplicationProtocol);

                tlsEngine.beginHandshake();
            }
            catch (SSLException ex)
            {
                doReset(networkThrottle, networkId);
                doAbort(networkReply, networkReplyId, 0L);
            }
        }

        private String selectApplicationProtocol(
            SSLEngine tlsEngine,
            List<String> clientProtocols)
        {
            final MessagePredicate alpnFilter = (t, b, o, l) ->
            {
                final RouteFW route = routeRO.wrap(b, o, l);
                if (networkRef == route.sourceRef() && networkReplyName.equals(route.source().asString()))
                {
                    ExtendedSSLSession tlsSession = (ExtendedSSLSession) tlsEngine.getHandshakeSession();

                    List<SNIServerName> sniServerNames = tlsSession.getRequestedServerNames();
                    String peerHost = null;
                    if (sniServerNames.size() > 0)
                    {
                        SNIHostName sniHostName = (SNIHostName) sniServerNames.get(0);
                        peerHost = sniHostName.getAsciiName();
                    }

                    final TlsRouteExFW routeEx = route.extension().get(tlsRouteExRO::wrap);
                    final String routeHostname = routeEx.hostname().asString();
                    final String routeProtocol = routeEx.applicationProtocol().asString();

                    if (routeHostname == null || Objects.equals(peerHost, routeHostname))
                    {
                        return routeProtocol == null || clientProtocols.contains(routeProtocol);
                    }
                }
                return false;
            };

            RouteFW route = router.resolve(authorization, alpnFilter, wrapRoute);
            if (route != null)
            {
                final TlsRouteExFW routeEx = route.extension().get(tlsRouteExRO::wrap);
                String applicationProtocol = routeEx.applicationProtocol().asString();
                // If the route is default (i.e. no application protocol), need to behave as if there is no ALPN
                // So return an empty String to opt out ALPN negotiation
                return applicationProtocol == null ? "" : applicationProtocol;
            }
            return null;
        }

        private void afterHandshake(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                handleData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                doReset(networkThrottle, networkId);
                break;
            }
        }

        private void handleData(
            DataFW data)
        {
            final int dataLength = data.length();
            networkTraceId = data.trace();

            readFrameCounter.getAsLong();
            readBytesAccumulator.accept(dataLength);

            networkBudget -= dataLength + data.padding();

            if (networkSlot == NO_SLOT)
            {
                networkSlot = networkPool.acquire(networkId);
            }

            try
            {
                if (networkSlot == NO_SLOT || networkBudget < 0)
                {
                    doCloseInbound(tlsEngine);
                    doReset(networkThrottle, networkId);
                    doAbort(applicationTarget, applicationId, authorization);
                    networkSlotOffset = 0;
                }
                else
                {
                    final OctetsFW payload = data.payload();
                    final int payloadSize = payload.sizeof();

                    final MutableDirectBuffer inNetBuffer = networkPool.buffer(networkSlot);
                    inNetBuffer.putBytes(networkSlotOffset, payload.buffer(), payload.offset(), payloadSize);
                    networkSlotOffset += payloadSize;

                    unwrapNetworkBufferData();
                }
            }
            catch (SSLException ex)
            {
                doReset(networkThrottle, networkId);
                doAbort(applicationTarget, applicationId, authorization);
                networkSlotOffset = 0;
            }
            finally
            {
                if (networkSlotOffset == 0 & networkSlot != NO_SLOT)
                {
                    networkPool.release(networkSlot);
                    networkSlot = NO_SLOT;
                }
            }
        }

        private void unwrapNetworkBufferData() throws SSLException
        {
            assert (networkSlotOffset != 0);

            if (applicationSlot == NO_SLOT)
            {
                applicationSlot = applicationPool.acquire(applicationId);
            }

            try
            {
                if (applicationSlot == NO_SLOT)
                {
                    doCloseInbound(tlsEngine);
                    doReset(networkThrottle, networkId);
                    doAbort(applicationTarget, applicationId, authorization);
                    networkSlotOffset = 0;
                }
                else
                {
                    final MutableDirectBuffer inNetBuffer = networkPool.buffer(networkSlot);
                    final ByteBuffer inNetByteBuffer = networkPool.byteBuffer(networkSlot);
                    final int inNetByteBufferPosition = inNetByteBuffer.position();
                    inNetByteBuffer.limit(inNetByteBuffer.position() + networkSlotOffset);

                    loop:
                    while (inNetByteBuffer.hasRemaining() && !tlsEngine.isInboundDone())
                    {
                        final ByteBuffer outAppByteBuffer = applicationPool.byteBuffer(applicationSlot);
                        outAppByteBuffer.position(outAppByteBuffer.position() + applicationSlotOffset);

                        SSLEngineResult result = tlsEngine.unwrap(inNetByteBuffer, outAppByteBuffer);

                        switch (result.getStatus())
                        {
                        case BUFFER_OVERFLOW:
                        case BUFFER_UNDERFLOW:
                            final int totalBytesConsumed = inNetByteBuffer.position() - inNetByteBufferPosition;
                            final int totalBytesRemaining = inNetByteBuffer.remaining();
                            alignSlotBuffer(inNetBuffer, totalBytesConsumed, totalBytesRemaining);
                            networkSlotOffset = totalBytesRemaining;
                            if (networkSlotOffset == networkPool.slotCapacity() &&
                                    result.getStatus() == BUFFER_UNDERFLOW)
                            {
                                networkSlotOffset = 0;
                                doReset(networkThrottle, networkId);
                                doAbort(applicationTarget, applicationId, authorization);
                                doCloseInbound(tlsEngine);
                            }
                            else
                            {
                                final int networkCredit =
                                        Math.max(networkPool.slotCapacity() - networkSlotOffset - networkBudget, 0);

                                if (networkCredit > 0)
                                {
                                    networkBudget += networkCredit;
                                    doWindow(networkThrottle, networkId, networkCredit, networkPadding);
                                }
                            }
                            break loop;
                        default:
                            networkSlotOffset = 0;
                            applicationSlotOffset += result.bytesProduced();
                            handleStatus(result.getHandshakeStatus(), r -> {});
                            break;
                        }
                    }

                    handleFlushAppData();
                }
            }
            catch (SSLException ex)
            {
                networkSlotOffset = 0;
                applicationSlotOffset = 0;
                throw ex;
            }
            finally
            {
                if (applicationSlotOffset == 0 && applicationSlot != NO_SLOT)
                {
                    applicationPool.release(applicationSlot);
                    applicationSlot = NO_SLOT;
                }
            }
        }

        private void handleEnd(
            EndFW end)
        {
            if (!tlsEngine.isInboundDone())
            {
                try
                {
                    doCloseInbound(tlsEngine);
                    doEnd(applicationTarget, applicationId, end.trace(), authorization);
                }
                catch (SSLException ex)
                {
                    doAbort(applicationTarget, applicationId, authorization);
                }
            }
        }

        private void handleAbort(
            AbortFW abort)
        {
            try
            {
                doCloseInbound(tlsEngine);
            }
            catch (SSLException ex)
            {
                // Ignore and clean up below
            }
            finally
            {
                doAbort(applicationTarget, applicationId, abort.trace(), authorization);
            }
        }

        private void handleStatus(
            HandshakeStatus status,
            Consumer<SSLEngineResult> resultHandler)
        {
            loop:
            for (;;)
            {
                switch (status)
                {
                case NEED_TASK:
                    for (Runnable runnable = tlsEngine.getDelegatedTask();
                            runnable != null;
                            runnable = tlsEngine.getDelegatedTask())
                    {
                        runnable.run();
                    }

                    status = tlsEngine.getHandshakeStatus();
                    break;
                case NEED_WRAP:
                    try
                    {
                        outNetByteBuffer.clear();
                        SSLEngineResult result = tlsEngine.wrap(EMPTY_BYTE_BUFFER, outNetByteBuffer);
                        // resultHandler flushes data to network and adjusts networkReplyBudget
                        resultHandler.accept(result);
                        status = result.getHandshakeStatus();

                        if (status == NEED_WRAP && result.bytesProduced() == 0)
                        {
                            break loop;
                        }
                    }
                    catch (SSLException ex)
                    {
                        // lambda interface cannot throw checked exception
                        rethrowUnchecked(ex);
                    }
                    break;
                case FINISHED:
                    handleFinished();
                    status = tlsEngine.getHandshakeStatus();
                    break;
                default:
                    break loop;
                }
            }
        }

        private void handleFinished()
        {
            ExtendedSSLSession tlsSession = (ExtendedSSLSession) tlsEngine.getSession();
            List<SNIServerName> sniServerNames = tlsSession.getRequestedServerNames();

            String tlsHostname0 = null;
            if (sniServerNames.size() > 0)
            {
                SNIHostName sniHostName = (SNIHostName) sniServerNames.get(0);
                tlsHostname0 = sniHostName.getAsciiName();
            }
            String tlsHostname = tlsHostname0;

            String tlsApplicationProtocol0 = tlsEngine.getApplicationProtocol();
            if (tlsApplicationProtocol0 != null && tlsApplicationProtocol0.isEmpty())
            {
                tlsApplicationProtocol0 = null;
            }
            final String tlsApplicationProtocol = tlsApplicationProtocol0;

            final MessagePredicate filter = (t, b, o, l) ->
            {
                final RouteFW route = routeRO.wrap(b, o, l);
                final TlsRouteExFW routeEx = route.extension().get(tlsRouteExRO::wrap);
                final String hostname = routeEx.hostname().asString();
                final String applicationProtocol = routeEx.applicationProtocol().asString();

                return networkRef == route.sourceRef() &&
                        networkReplyName.equals(route.source().asString()) &&
                        (hostname == null || Objects.equals(tlsHostname, hostname)) &&
                        (applicationProtocol == null || Objects.equals(tlsApplicationProtocol, applicationProtocol));
            };

            final RouteFW route = router.resolve(authorization, filter, wrapRoute);

            if (route != null)
            {
                final String applicationName = route.target().asString();
                final MessageConsumer applicationTarget = router.supplyTarget(applicationName);
                final long applicationRef = route.targetRef();

                final long newCorrelationId = supplyCorrelationId.getAsLong();
                correlations.put(newCorrelationId, handshake);

                final long newApplicationId = supplyStreamId.getAsLong();

                doTlsBegin(applicationTarget, newApplicationId, networkTraceId, authorization, applicationRef,
                        newCorrelationId, tlsHostname, tlsApplicationProtocol);
                router.setThrottle(applicationName, newApplicationId, this::handleThrottle);

                handshake.onFinished();

                if (handshake.networkSlotOffset != 0)
                {
                    this.networkSlot = handshake.networkSlot;
                    this.networkSlotOffset = handshake.networkSlotOffset;
                }

                this.applicationTarget = applicationTarget;
                this.applicationId = newApplicationId;
                this.applicationCorrelationId = newCorrelationId;

                this.streamState = this::afterHandshake;
                this.handshake = null;
            }
            else
            {
                doReset(networkThrottle, networkId);
                doAbort(networkReply, networkReplyId, 0L);
            }
        }

        private void handleFlushAppData()
        {
            if (applicationSlotOffset > 0)
            {
                final MutableDirectBuffer outAppBuffer = applicationPool.buffer(applicationSlot);

                final int applicationWindow = Math.min(
                        applicationBudget - applicationPadding, MAXIMUM_PAYLOAD_LENGTH);

                final int applicationBytesConsumed = Math.min(applicationSlotOffset, applicationWindow);

                if (applicationBytesConsumed > 0)
                {
                    final OctetsFW outAppOctets = outAppOctetsRO.wrap(outAppBuffer, 0, applicationBytesConsumed);

                    doData(applicationTarget, applicationId, networkTraceId, applicationPadding, authorization, outAppOctets);

                    applicationBudget -= applicationBytesConsumed + applicationPadding;

                    applicationSlotOffset -= applicationBytesConsumed;

                }

                if (applicationSlotOffset != 0)
                {
                    alignSlotBuffer(outAppBuffer, applicationBytesConsumed, applicationSlotOffset);
                }

            }

            if (applicationSlotOffset == 0 && tlsEngine.isInboundDone())
            {
                doEnd(applicationTarget, applicationId, networkTraceId, authorization);
            }
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                handleWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                handleReset(reset);
                break;
            default:
                // ignore
                break;
            }
        }

        private void handleWindow(
            WindowFW window)
        {
            applicationBudget += window.credit();
            applicationPadding = networkPadding = window.padding();

            if (applicationSlotOffset != 0)
            {
                try
                {
                    handleFlushAppData();
                }
                finally
                {
                    if (applicationSlotOffset == 0)
                    {
                        applicationPool.release(applicationSlot);
                        applicationSlot = NO_SLOT;
                    }
                }
            }

            if (networkSlotOffset != 0)
            {
                try
                {
                    unwrapNetworkBufferData();
                }
                catch (SSLException ex)
                {
                    doReset(networkThrottle, networkId);
                    doAbort(applicationTarget, applicationId, authorization);
                }
                finally
                {
                    if (networkSlotOffset == 0)
                    {
                        networkPool.release(networkSlot);
                        networkSlot = NO_SLOT;
                    }
                }
            }

            final int networkCredit = applicationBudget - networkBudget - networkSlotOffset;

            if (networkCredit > 0)
            {
                networkBudget += networkCredit;
                doWindow(networkThrottle, networkId, window.trace(), networkCredit, networkPadding);
            }
        }

        private void handleReset(
            ResetFW reset)
        {
            try
            {
                doCloseInbound(tlsEngine);
            }
            catch (SSLException ex)
            {
                // Ignore and clean up
            }
            finally
            {
                doReset(networkThrottle, networkId, reset.trace());
            }
        }

        private void handleNetworkDone()
        {
            doAbort(applicationTarget, applicationId, authorization);
        }

        private void handleNetworkReplyDone(long traceId)
        {
            correlations.remove(applicationCorrelationId);

            if (networkReplyDoneHandler != null)
            {
                networkReplyDoneHandler.accept(traceId);
            }
        }

        private void setNetworkReplyDoneHandler(
            LongConsumer networkReplyDoneHandler)
        {
            this.networkReplyDoneHandler = networkReplyDoneHandler;
        }

        private int getNetworkBudget()
        {
            return networkBudget;
        }

        private int getNetworkPadding()
        {
            return networkPadding;
        }

        private void setNetworkBudget(int networkBudget)
        {
            this.networkBudget = networkBudget;
        }

        private void setNetworkPadding(int networkPadding)
        {
            this.networkPadding = networkPadding;
        }
    }

    public final class ServerHandshake
    {
        private final SSLEngine tlsEngine;
        private final BiConsumer<HandshakeStatus, Consumer<SSLEngineResult>> statusHandler;

        private final MessageConsumer networkThrottle;
        private final long networkId;
        private final String networkReplyName;
        private final MessageConsumer networkReply;
        private final long networkReplyId;
        private final LongConsumer networkReplyDoneHandler;
        private final Consumer<LongConsumer> networkReplyDoneHandlerConsumer;

        private final LongSupplier writeFrameCounter;
        private final LongSupplier readFrameCounter;
        private final LongConsumer writeBytesAccumulator;
        private final LongConsumer readBytesAccumulator;

        private int networkSlot = NO_SLOT;
        private int networkSlotOffset;

        private int networkReplyBudget;
        private int networkReplyPadding;

        private IntSupplier networkBudgetSupplier;
        private IntSupplier networkPaddingSupplier;
        private IntConsumer networkBudgetConsumer;

        private Consumer<ResetFW> resetHandler;
        private long networkTraceId;

        private ServerHandshake(
            SSLEngine tlsEngine,
            MessageConsumer networkThrottle,
            long networkId,
            String networkReplyName,
            MessageConsumer networkReply,
            long networkReplyId,
            BiConsumer<HandshakeStatus, Consumer<SSLEngineResult>> statusHandler,
            LongConsumer networkReplyDoneHandler,
            Consumer<LongConsumer> networkReplyDoneHandlerConsumer,
            IntSupplier networkBudgetSupplier,
            IntSupplier networkPaddingSupplier,
            IntConsumer networkBudgetConsumer,
            LongSupplier writeFrameCounter,
            LongSupplier readFrameCounter,
            LongConsumer writeBytesAccumulator,
            LongConsumer readBytesAccumulator)
        {
            this.tlsEngine = tlsEngine;
            this.statusHandler = statusHandler;
            this.resetHandler = this::handleReset;
            this.networkReplyDoneHandler = networkReplyDoneHandler;

            this.networkThrottle = networkThrottle;
            this.networkId = networkId;
            this.networkReplyName = networkReplyName;
            this.networkReply = networkReply;
            this.networkReplyId = networkReplyId;
            this.networkReplyDoneHandlerConsumer = networkReplyDoneHandlerConsumer;
            this.networkBudgetSupplier = networkBudgetSupplier;
            this.networkPaddingSupplier = networkPaddingSupplier;
            this.networkBudgetConsumer = networkBudgetConsumer;
            this.writeFrameCounter = writeFrameCounter;
            this.readFrameCounter = readFrameCounter;
            this.writeBytesAccumulator = writeBytesAccumulator;
            this.readBytesAccumulator = readBytesAccumulator;
        }

        private void onFinished()
        {
            this.resetHandler = this::handleResetAfterHandshake;
        }

        private void afterBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                handleData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                doReset(networkThrottle, networkId);
                break;
            }
        }

        private void handleData(
            DataFW data)
        {
            readFrameCounter.getAsLong();
            readBytesAccumulator.accept(data.length());
            networkTraceId = data.trace();

            networkBudgetConsumer.accept(networkBudgetSupplier.getAsInt()
                    - data.length() - data.padding());

            if (networkSlot == NO_SLOT)
            {
                networkSlot = networkPool.acquire(networkId);
            }

            try
            {
                if (networkSlot == NO_SLOT || networkBudgetSupplier.getAsInt() < 0)
                {
                    doCloseOutbound(tlsEngine, networkReply, networkReplyId, networkTraceId, networkReplyPadding,
                            data.authorization(), NOP, writeFrameCounter, writeBytesAccumulator);
                    doReset(networkThrottle, networkId);
                    doAbort(networkReply, networkReplyId, 0L);
                }
                else
                {
                    final OctetsFW payload = data.payload();
                    final int payloadSize = payload.sizeof();

                    final MutableDirectBuffer inNetBuffer = networkPool.buffer(networkSlot);
                    inNetBuffer.putBytes(networkSlotOffset, payload.buffer(), payload.offset(), payloadSize);
                    final ByteBuffer inNetByteBuffer = networkPool.byteBuffer(networkSlot);
                    final int inNetByteBufferPosition = inNetByteBuffer.position();
                    inNetByteBuffer.limit(inNetByteBuffer.position() + networkSlotOffset + payloadSize);

                    loop:
                    while (inNetByteBuffer.hasRemaining() && !tlsEngine.isInboundDone())
                    {
                        outAppByteBuffer.rewind();
                        HandshakeStatus handshakeStatus = NOT_HANDSHAKING;
                        SSLEngineResult.Status status = BUFFER_UNDERFLOW;
                        if (tlsEngine.getHandshakeStatus() != NOT_HANDSHAKING && tlsEngine.getHandshakeStatus() != FINISHED)
                        {
                            SSLEngineResult result = tlsEngine.unwrap(inNetByteBuffer, outAppByteBuffer);
                            status = result.getStatus();
                            handshakeStatus = result.getHandshakeStatus();
                        }

                        if (outAppByteBuffer.position() != 0)
                        {
                            doReset(networkThrottle, networkId);
                            doAbort(networkReply, networkReplyId, 0L);
                            break loop;
                        }

                        switch (status)
                        {
                        case BUFFER_UNDERFLOW:
                            final int totalBytesConsumed = inNetByteBuffer.position() - inNetByteBufferPosition;
                            final int totalBytesRemaining = inNetByteBuffer.remaining();
                            alignSlotBuffer(inNetBuffer, totalBytesConsumed, totalBytesRemaining);
                            networkSlotOffset = totalBytesRemaining;
                            break loop;
                        default:
                            networkSlotOffset = inNetByteBuffer.remaining();
                            statusHandler.accept(handshakeStatus, this::updateNetworkReplyWindow);
                            break;
                        }
                    }

                    networkBudgetConsumer.accept(networkBudgetSupplier.getAsInt() + data.length());

                    doWindow(networkThrottle, networkId, data.length(), networkPaddingSupplier.getAsInt());
                }
            }
            catch (SSLException | UnsupportedOperationException ex)
            {
                networkSlotOffset = 0;
                doReset(networkThrottle, networkId);
                doAbort(networkReply, networkReplyId, 0L);
            }
            finally
            {
                if (networkSlotOffset == 0 && networkSlot != NO_SLOT)
                {
                    networkPool.release(networkSlot);
                    networkSlot = NO_SLOT;
                }
            }
        }

        private void handleEnd(
            EndFW end)
        {
            try
            {
                doCloseOutbound(
                    tlsEngine,
                    networkReply,
                    networkReplyId,
                    networkTraceId,
                    networkReplyPadding,
                    end.authorization(),
                    NOP,
                    writeFrameCounter,
                    writeBytesAccumulator);
            }
            catch (SSLException ex)
            {
                doAbort(networkReply, networkReplyId, 0L);
            }
        }

        private void handleAbort(
            AbortFW abort)
        {
            tlsEngine.closeOutbound();
        }

        private void updateNetworkReplyWindow(
            SSLEngineResult result)
        {
            final int bytesProduced = result.bytesProduced();
            if (bytesProduced != 0)
            {
                flushNetwork(
                    tlsEngine,
                    result.bytesProduced(),
                    networkReply,
                    networkReplyId,
                    networkTraceId,
                    0,
                    0L,
                    networkReplyDoneHandler,
                    writeFrameCounter,
                    writeBytesAccumulator);
                networkReplyBudget -= bytesProduced + networkReplyPadding;
            }
        }

        private void setNetworkThrottle(
            MessageConsumer newNetworkThrottle)
        {
            router.setThrottle(networkReplyName, networkReplyId, newNetworkThrottle);


            //doWindow(newNetworkThrottle, networkReplyId, outNetworkWindowBudget, outNetworkWindowPadding);
        }

        private void setNetworkReplyDoneHandler(
            LongConsumer networkReplyDoneHandler)
        {
            networkReplyDoneHandlerConsumer.accept(networkReplyDoneHandler);
        }

        @Override
        public String toString()
        {
            return String.format("%s [networkReplyBudget=%d networkReplyPadding=%d]", getClass().getSimpleName(),
                    networkReplyBudget, networkReplyPadding);
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                handleWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                resetHandler.accept(reset);
                break;
            default:
                // ignore
                break;
            }
        }

        private void handleWindow(
            WindowFW window)
        {
            networkReplyBudget += window.credit();
            networkReplyPadding = window.padding();

            statusHandler.accept(tlsEngine.getHandshakeStatus(), this::updateNetworkReplyWindow);
        }

        private void handleReset(
            ResetFW reset)
        {
            try
            {
                doCloseInbound(tlsEngine);
            }
            catch (SSLException ex)
            {
                // ignore and clean
            }
            finally
            {
                networkReplyDoneHandler.accept(0);
            }
        }

        private void handleResetAfterHandshake(
            ResetFW reset)
        {
            networkReplyDoneHandler.accept(0);
        }
    }

    private final class ServerConnectReplyStream
    {
        private final long applicationReplyId;

        private int applicationReplyBudget;
        private int applicationReplyPadding;

        private int networkReplyBudget;
        private int networkReplyPadding;

        private MessageConsumer applicationReplyThrottle;

        private MessageConsumer networkReply;
        private long networkReplyId;

        private MessageConsumer streamState;
        private SSLEngine tlsEngine;
        private BiConsumer<HandshakeStatus, Consumer<SSLEngineResult>> statusHandler;

        private LongSupplier writeFrameCounter;
        private LongSupplier readFrameCounter;
        private LongConsumer writeBytesAccumulator;
        private LongConsumer readBytesAccumulator;

        private long applicationReplyTraceId;

        @Override
        public String toString()
        {
            return String.format("%s [applicationBudget=%d applicationPadding=%d]",
                                 getClass().getSimpleName(), applicationReplyBudget, applicationReplyPadding);
        }

        private ServerConnectReplyStream(
            MessageConsumer applicationReplyThrottle,
            long applicationReplyId)
        {
            this.applicationReplyThrottle = applicationReplyThrottle;
            this.applicationReplyId = applicationReplyId;
            this.streamState = this::beforeBegin;
        }

        private void handleStream(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            streamState.accept(msgTypeId, buffer, index, length);
        }

        private void beforeBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            if (msgTypeId == BeginFW.TYPE_ID)
            {
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                handleBegin(begin);
            }
            else
            {
                doReset(applicationReplyThrottle, applicationReplyId);
            }
        }

        private void afterBegin(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                handleData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                handleEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                handleAbort(abort);
                break;
            default:
                doReset(applicationReplyThrottle, applicationReplyId);
                break;
            }
        }

        private void handleBegin(
            BeginFW begin)
        {
            final long sourceRef = begin.sourceRef();
            final long correlationId = begin.correlationId();

            final ServerHandshake handshake = sourceRef == 0L ? correlations.remove(correlationId) : null;
            if (handshake != null)
            {
                this.streamState = this::afterBegin;
                this.tlsEngine = handshake.tlsEngine;
                this.networkReply = handshake.networkReply;
                this.networkReplyId = handshake.networkReplyId;
                this.statusHandler = handshake.statusHandler;

                this.writeFrameCounter = handshake.writeFrameCounter;
                this.readFrameCounter = handshake.readFrameCounter;
                this.writeBytesAccumulator = handshake.writeBytesAccumulator;
                this.readBytesAccumulator = handshake.readBytesAccumulator;

                this.networkReplyBudget = handshake.networkReplyBudget;
                this.networkReplyPadding = handshake.networkReplyPadding;
                this.applicationReplyPadding = networkReplyPadding + MAXIMUM_HEADER_SIZE;
                handshake.setNetworkThrottle(this::handleThrottle);
                sendApplicationReplyWindow(0);
                handshake.setNetworkReplyDoneHandler(this::handleNetworkReplyDone);
            }
            else
            {
                doReset(applicationReplyThrottle, applicationReplyId);
            }
        }

        private void handleData(
            DataFW data)
        {
            applicationReplyTraceId = data.trace();
            applicationReplyBudget -= data.length() + data.padding();

            try
            {
                if (applicationReplyBudget < 0)
                {
                    doReset(applicationReplyThrottle, applicationReplyId);
                    doCloseOutbound(tlsEngine, networkReply, networkReplyId, applicationReplyTraceId, networkReplyPadding,
                            data.authorization(), this::handleNetworkReplyDone,
                            writeFrameCounter, writeBytesAccumulator);
                }
                else
                {
                    final OctetsFW payload = data.payload();

                    // Note: inAppBuffer is emptied by SslEngine.wrap(...)
                    //       so should be able to eliminate copy (stateless)
                    inAppByteBuffer.clear();
                    payload.buffer().getBytes(payload.offset(), inAppByteBuffer, payload.sizeof());
                    inAppByteBuffer.flip();

                    while (inAppByteBuffer.hasRemaining() && !tlsEngine.isOutboundDone())
                    {
                        outNetByteBuffer.rewind();
                        SSLEngineResult result = tlsEngine.wrap(inAppByteBuffer, outNetByteBuffer);
                        if (result.bytesProduced() > 0)
                        {
                            networkReplyBudget -= result.bytesProduced() + networkReplyPadding;
                        }
                        flushNetwork(tlsEngine, result.bytesProduced(), networkReply, networkReplyId,
                                applicationReplyTraceId, networkReplyPadding, data.authorization(),
                                this::handleNetworkReplyDone, writeFrameCounter, writeBytesAccumulator);

                        statusHandler.accept(result.getHandshakeStatus(), this::updateNetworkWindow);
                    }
                }
            }
            catch (SSLException ex)
            {
                doReset(applicationReplyThrottle, applicationReplyId);
                doAbort(networkReply, networkReplyId, 0L);
            }
        }

        private void handleEnd(
            EndFW end)
        {
            applicationReplyThrottle = null;

            try
            {
                doCloseOutbound(tlsEngine, networkReply, networkReplyId, end.trace(), networkReplyPadding,
                        end.authorization(), this::handleNetworkReplyDone,
                        writeFrameCounter, writeBytesAccumulator);
            }
            catch (SSLException ex)
            {
                // END is from application reply, so no need to clean that stream
                doAbort(networkReply, networkReplyId, 0L);
            }
        }

        private void handleAbort(
            AbortFW abort)
        {
            tlsEngine.closeOutbound();

            // ABORT is from application reply, so no need to clean that stream
            doAbort(networkReply, networkReplyId, abort.trace(), 0L);
        }

        private void updateNetworkWindow(
            SSLEngineResult result)
        {
            // TODO network-network protocol interaction (renegotiate key)
            //applicationWindowBudgetAdjustment += result.bytesProduced() - result.bytesConsumed();
        }

        private void handleThrottle(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                handleWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                handleReset(reset);
                break;
            default:
                // ignore
                break;
            }
        }

        private void sendApplicationReplyWindow(long traceId)
        {
            int applicationReplyCredit = networkReplyBudget - applicationReplyBudget;
            if (applicationReplyCredit > 0)
            {
                applicationReplyBudget += applicationReplyCredit;
                doWindow(applicationReplyThrottle, applicationReplyId, traceId, applicationReplyCredit,
                        applicationReplyPadding);
            }
        }

        private void handleWindow(
            final WindowFW window)
        {
            networkReplyBudget += window.credit();
            networkReplyPadding = window.padding();
            applicationReplyPadding = networkReplyPadding + MAXIMUM_HEADER_SIZE;
            sendApplicationReplyWindow(window.trace());
        }

        private void handleReset(
            ResetFW reset)
        {
            tlsEngine.closeOutbound();

            // RESET is from network reply, so no need to clean that stream
            handleNetworkReplyDone(reset.trace());
        }

        private void handleNetworkReplyDone(long traceId)
        {
            if (applicationReplyThrottle != null)
            {
                doReset(applicationReplyThrottle, applicationReplyId, traceId);
            }
        }

    }

    private void flushNetwork(
        SSLEngine tlsEngine,
        int bytesProduced,
        MessageConsumer networkReply,
        long networkReplyId,
        long traceId,
        int padding,
        long authorization,
        LongConsumer networkReplyDoneHandler,
        LongSupplier writeFrameCounter,
        LongConsumer writeBytesAccumulator)
    {
        if (bytesProduced > 0)
        {
            writeFrameCounter.getAsLong();
            writeBytesAccumulator.accept(bytesProduced);
            final OctetsFW outNetOctets = outNetOctetsRO.wrap(outNetBuffer, 0, bytesProduced);
            doData(networkReply, networkReplyId, traceId, padding, authorization, outNetOctets);
        }

        if (tlsEngine.isOutboundDone())
        {
            doEnd(networkReply, networkReplyId, traceId, authorization);
            networkReplyDoneHandler.accept(0);      // sends RESET to application reply stream (if not received END)
        }
    }

    private void alignSlotBuffer(
        final MutableDirectBuffer slotBuffer,
        final int bytesConsumed,
        final int bytesRemaining)
    {
        if (bytesConsumed > 0)
        {
            writeBuffer.putBytes(0, slotBuffer, bytesConsumed, bytesRemaining);
            slotBuffer.putBytes(0, writeBuffer, 0, bytesRemaining);
        }
    }

    private void doTlsBegin(
        MessageConsumer connect,
        long connectId,
        long traceId,
        long authorization,
        long connectRef,
        long correlationId,
        String hostname,
        String applicationProtocol)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(connectId)
                                     .trace(traceId)
                                     .authorization(authorization)
                                     .source("tls")
                                     .sourceRef(connectRef)
                                     .correlationId(correlationId)
                                     .extension(e -> e.set(visitTlsBeginEx(hostname, applicationProtocol)))
                                     .build();

        connect.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private Flyweight.Builder.Visitor visitTlsBeginEx(
        String hostname,
        String applicationProtocol)
    {
        return (buffer, offset, limit) ->
            tlsBeginExRW.wrap(buffer, offset, limit)
                        .hostname(hostname)
                        .applicationProtocol(applicationProtocol)
                        .build()
                        .sizeof();
    }

    private void doBegin(
        final MessageConsumer target,
        final long targetId,
        final long authorization,
        final long targetRef,
        final long correlationId)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .streamId(targetId)
                .authorization(authorization)
                .source("tls")
                .sourceRef(targetRef)
                .correlationId(correlationId)
                .build();

        target.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doData(
        final MessageConsumer target,
        final long targetId,
        final long traceId,
        final int padding,
        final long authorization,
        final OctetsFW payload)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .streamId(targetId)
                .trace(traceId)
                .authorization(authorization)
                .groupId(0)
                .padding(padding)
                .payload(p -> p.set(payload.buffer(), payload.offset(), payload.sizeof()))
                .build();

        target.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doEnd(
        final MessageConsumer target,
        final long targetId,
        final long traceId,
        final long authorization)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .streamId(targetId)
                .trace(traceId)
                .authorization(authorization)
                .build();

        target.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        final MessageConsumer target,
        final long targetId,
        final long traceId,
        final long authorization)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(targetId)
                                     .trace(traceId)
                                     .authorization(authorization)
                                     .build();

        target.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doAbort(
        final MessageConsumer target,
        final long targetId,
        final long authorization)
    {
        doAbort(target, targetId, 0, authorization);
    }

    private void doWindow(
        final MessageConsumer throttle,
        final long throttleId,
        final long traceId,
        final int credit,
        final int padding)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                        .streamId(throttleId)
                                        .trace(traceId)
                                        .credit(credit)
                                        .padding(padding)
                                        .groupId(0)
                                        .build();

        throttle.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doWindow(
        final MessageConsumer throttle,
        final long throttleId,
        final int credit,
        final int padding)
    {
        doWindow(throttle, throttleId, 0, credit, padding);
    }

    private void doReset(
        final MessageConsumer throttle,
        final long throttleId,
        final long traceId)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(throttleId)
                                     .trace(traceId)
                                     .build();

        throttle.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    private void doReset(
        final MessageConsumer throttle,
        final long throttleId)
    {
        doReset(throttle, throttleId, 0);
    }

    private void doCloseInbound(
        final SSLEngine tlsEngine) throws SSLException
    {
        tlsEngine.closeInbound();
    }

    private void doCloseOutbound(
        SSLEngine tlsEngine,
        MessageConsumer networkReply,
        long networkReplyId,
        long traceId,
        int padding,
        long authorization,
        LongConsumer networkReplyDoneHandler,
        LongSupplier writeFrameCounter,
        LongConsumer writeBytesAccumulator
        ) throws SSLException
    {
        tlsEngine.closeOutbound();
        outNetByteBuffer.rewind();
        SSLEngineResult result = tlsEngine.wrap(inAppByteBuffer, outNetByteBuffer);
        flushNetwork(tlsEngine, result.bytesProduced(), networkReply, networkReplyId, traceId, padding,
                authorization, networkReplyDoneHandler, writeFrameCounter, writeBytesAccumulator);
    }
}
