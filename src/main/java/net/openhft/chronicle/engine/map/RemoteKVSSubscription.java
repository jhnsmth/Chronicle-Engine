package net.openhft.chronicle.engine.map;

import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.engine.api.map.KeyValueStore;
import net.openhft.chronicle.engine.api.map.MapEvent;
import net.openhft.chronicle.engine.api.pubsub.InvalidSubscriberException;
import net.openhft.chronicle.engine.api.pubsub.Subscriber;
import net.openhft.chronicle.engine.api.pubsub.TopicSubscriber;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.server.WireType;
import net.openhft.chronicle.engine.server.internal.MapWireHandler;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.AsyncTcpConsumer;
import net.openhft.chronicle.network.connection.TcpConnectionHub;
import net.openhft.chronicle.wire.ReadMarshallable;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static net.openhft.chronicle.core.pool.ClassAliasPool.CLASS_ALIASES;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.subscribe;
import static net.openhft.chronicle.engine.server.internal.MapWireHandler.EventId.unSubscribe;
import static net.openhft.chronicle.wire.CoreFields.reply;

/**
 * Created by daniel on 10/06/15.
 */
public class RemoteKVSSubscription<K, MV, V> extends AbstractStatelessClient implements ObjectKVSSubscription<K, MV, V>, Closeable {

    private long tid = -1;
    private static final Logger LOG = LoggerFactory.getLogger(MapWireHandler.class);

    private final AsyncTcpConsumer tcpConsumer;

    public RemoteKVSSubscription(RequestContext context, Asset asset) {
        super(TcpConnectionHub.hub(context, asset), (long) 0, toUri(context));

        // todo move this into the asset tree
        tcpConsumer = new AsyncTcpConsumer(WireType.wire, hub, hub.inBytesLock());
    }

    @Override
    public boolean needsPrevious() {
        return true;
    }

    @Override
    public void setKvStore(KeyValueStore<K, MV, V> store) {
    }

    @Override
    public void notifyEvent(MapEvent<K, V> mpe) {
        //todo This should be implemented
    }

    @Override
    public int keySubscriberCount() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public int entrySubscriberCount() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public int topicSubscriberCount() {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerSubscriber(RequestContext rc, Subscriber<MapEvent<K, V>> subscriber) {
        final long startTime = System.currentTimeMillis();

        if (hub.outBytesLock().isHeldByCurrentThread())
            throw new IllegalStateException("Cannot view map while debugging");

        hub.outBytesLock().lock();
        try {
            tid = writeMetaDataStartTime(startTime);
            hub.outWire().writeDocument(false, wireOut ->
                    wireOut.writeEventName(subscribe).
                            typeLiteral(CLASS_ALIASES.nameFor(rc.elementType())));

            hub.writeSocket(hub.outWire());
        } finally {
            hub.outBytesLock().unlock();
        }

        assert !hub.outBytesLock().isHeldByCurrentThread();
        tcpConsumer.apply(tid, w -> {
            System.out.println("received for subscription !!! :\n" +
                    Wires.fromSizePrefixedBlobs(w.bytes()));

            w.readDocument(null, d -> {
                ValueIn read = d.read(reply);
                final ReadMarshallable marshallable = read.typedMarshallable();
                this.onEvent(marshallable, subscriber);
            });

        });
    }

    @Override
    public void registerKeySubscriber(RequestContext rc, Subscriber<K> kSubscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterKeySubscriber(Subscriber<K> kSubscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerTopicSubscriber(RequestContext rc, TopicSubscriber<K, V> subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterTopicSubscriber(TopicSubscriber subscriber) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void registerDownstream(EventConsumer<K, V> subscription) {
        throw new UnsupportedOperationException("todo");
    }

    @Override
    public void unregisterSubscriber(Subscriber<MapEvent<K, V>> subscriber) {
        if (tid == -1) {
            LOG.warn("There is subscription to unsubscribe");
        }

        hub.outBytesLock().lock();
        try {
            writeMetaDataForKnownTID(tid);
            hub.outWire().writeDocument(false, wireOut -> {
                wireOut.writeEventName(unSubscribe).text("");
            });

            hub.writeSocket(hub.outWire());
        } finally {
            hub.outBytesLock().unlock();
        }
    }

    @NotNull
    private static String toUri(@NotNull final RequestContext context) {
        return "/" + context.name()
                + "?view=" + "map&keyType=" + context.keyType().getName() + "&valueType=" + context.valueType()
                .getName();
    }

    private void onEvent(Object me, Subscriber subscriber) {
        try {
            if (me == null) {
                // todo remove subscriber.
            } else {
                subscriber.onMessage(me);
            }
        } catch (InvalidSubscriberException noLongerValid) {
            unregisterSubscriber(subscriber);
        }
    }

    @Override
    public void close() {
        try {
            tcpConsumer.close();
        } catch (IOException e) {
            // do nothing
        }
    }
}

