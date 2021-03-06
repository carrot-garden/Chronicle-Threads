/*
 * Copyright 2016 higherfrequencytrading.com
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
package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Created by peter.lawrey on 22/01/15.
 */
public class EventGroup implements EventLoop {

    static final long REPLICATION_MONITOR_INTERVAL_MS = Long.getLong
            ("REPLICATION_MONITOR_INTERVAL_MS", SECONDS.toMillis(15));

    static final long MONITOR_INTERVAL_MS = Long.getLong("MONITOR_INTERVAL_MS", 200);

    static final int CONC_THREADS = Integer.getInteger("CONC_THREADS", (Runtime.getRuntime().availableProcessors() + 2) / 2);

    private static final Integer REPLICATION_EVENT_PAUSE_TIME = Integer.getInteger
            ("replicationEventPauseTime", 20);
    final EventLoop monitor;
    @NotNull
    final VanillaEventLoop core;
    final BlockingEventLoop blocking;
    @NotNull
    private final Pauser pauser;
    private final boolean binding;
    private final String name;
    private VanillaEventLoop _replication;
    private VanillaEventLoop[] concThreads = new VanillaEventLoop[CONC_THREADS];
    private Supplier<Pauser> concThreadPauserSupplier = () -> new LongPauser(500, 100, 250, Jvm.isDebug() ? 200_000 : REPLICATION_EVENT_PAUSE_TIME * 1000, TimeUnit.MICROSECONDS);
    private boolean daemon;

    public EventGroup(boolean daemon, Pauser pauser, boolean binding, String name) {
        this.daemon = daemon;
        this.pauser = pauser;
        this.binding = binding;
        this.name = name;

        core = new VanillaEventLoop(this, name + "core-event-loop", pauser, 1, daemon, binding);
        monitor = new MonitorEventLoop(this, new LongPauser(0, 0, 100, 100, TimeUnit.MILLISECONDS));
        monitor.addHandler(new PauserMonitor(pauser, name + "core pauser", 30));
        blocking = new BlockingEventLoop(this, name + "blocking-event-loop");
    }

    public EventGroup(boolean daemon) {
        this(daemon, false);
    }

    public EventGroup(boolean daemon, boolean binding) {
        this(daemon, new LongPauser(1000, 200, 250, Jvm.isDebug() ? 200_000 : 20_000, TimeUnit.MICROSECONDS), binding);
    }

    public EventGroup(boolean daemon, Pauser pauser, boolean binding) {
        this(daemon, pauser, binding, "");
    }

    static int hash(int n, int mod) {
        n = (n >>> 23) ^ (n >>> 9) ^ n;
        n = (n & 0x7FFF_FFFF) % mod;
        return n;
    }

    public void setConcThreadPauserSupplier(Supplier<Pauser> supplier) {
        concThreadPauserSupplier = supplier;
    }

    synchronized VanillaEventLoop getReplication() {
        if (_replication == null) {
            LongPauser pauser = new LongPauser(500, 100, 250, Jvm.isDebug() ? 200_000 : REPLICATION_EVENT_PAUSE_TIME * 1000, TimeUnit.MICROSECONDS);
            _replication = new VanillaEventLoop(this, name + "replication-event-loop", pauser, REPLICATION_EVENT_PAUSE_TIME, true, binding);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, _replication));
            _replication.start();
            monitor.addHandler(new PauserMonitor(pauser, name + "replication pauser", 60));
        }
        return _replication;
    }

    synchronized VanillaEventLoop getConcThread(int n) {
        if (concThreads[n] == null) {
            Pauser pauser = concThreadPauserSupplier.get();
            concThreads[n] = new VanillaEventLoop(this, name + "conc-event-loop-" + n, pauser, REPLICATION_EVENT_PAUSE_TIME, daemon, binding);
            monitor.addHandler(new LoopBlockMonitor(REPLICATION_MONITOR_INTERVAL_MS, concThreads[n]));
            concThreads[n].start();
            monitor.addHandler(new PauserMonitor(pauser, name + "conc-event-loop-" + n + " pauser", 60));
        }
        return concThreads[n];
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    @Override
    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        addHandler(handler);
    }

    public void addHandler(@NotNull EventHandler handler) {
        HandlerPriority t1 = handler.priority();
        switch (t1) {
            case HIGH:
            case MEDIUM:
            case TIMER:
            case DAEMON:
                core.addHandler(handler);
                break;

            case MONITOR:
                monitor.addHandler(handler);
                break;

            case BLOCKING:
                blocking.addHandler(handler);
                break;

            // used only for replication, this is so replication can run in its own thread
            case REPLICATION:
                getReplication().addHandler(handler);
                break;

            case CONCURRENT: {
                int n = hash(handler.hashCode(), CONC_THREADS);
                getConcThread(n).addHandler(handler);
                break;
            }

            default:
                throw new IllegalArgumentException("Unknown priority " + handler.priority());
        }
    }

    @Override
    public void start() {
        if (!core.isAlive()) {
            core.start();

            monitor.start();
            // this checks that the core threads have stalled
            monitor.addHandler(new LoopBlockMonitor(MONITOR_INTERVAL_MS, EventGroup.this.core));
        }
    }

    @Override
    public void stop() {
        monitor.stop();
        if (_replication != null)
            _replication.stop();
        for (VanillaEventLoop concThread : concThreads) {
            if (concThread != null)
                concThread.stop();
        }
        core.stop();
        blocking.stop();
    }

    @Override
    public boolean isClosed() {
        return core.isClosed();
    }

    @Override
    public boolean isAlive() {
        return core.isAlive();
    }

    @Override
    public void close() {
        stop();
        monitor.close();
        blocking.close();
        core.close();
        if (_replication != null) _replication.close();
        Closeable.closeQuietly(concThreads);
    }

    class LoopBlockMonitor implements EventHandler {
        private final long monitoryIntervalMs;
        private final VanillaEventLoop eventLoop;
        long lastInterval = 1;

        public LoopBlockMonitor(long monitoryIntervalMs, final VanillaEventLoop eventLoop) {
            this.monitoryIntervalMs = monitoryIntervalMs;
            assert eventLoop != null;
            this.eventLoop = eventLoop;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {

            long loopStartMS = eventLoop.loopStartMS();
            if (loopStartMS <= 0 || loopStartMS == Long.MAX_VALUE)
                return false;
            if (loopStartMS == Long.MAX_VALUE - 1) {
                Jvm.warn().on(getClass(), "Monitoring a task which has finished " + eventLoop);
                throw new InvalidEventHandlerException();
            }
            long now = Time.currentTimeMillis();
            long blockingTimeMS = now - loopStartMS;
            long blockingInterval = blockingTimeMS / ((monitoryIntervalMs + 1) / 2);

            if (blockingInterval > lastInterval && !Jvm.isDebug() && eventLoop.isAlive()) {
                eventLoop.dumpRunningState(eventLoop.name() + " thread has blocked for "
                                + blockingTimeMS + " ms.",
                        // check we are still in the loop.
                        () -> eventLoop.loopStartMS() == loopStartMS);

            } else {
                lastInterval = Math.max(1, blockingInterval);
            }
            return false;
        }
    }
}
