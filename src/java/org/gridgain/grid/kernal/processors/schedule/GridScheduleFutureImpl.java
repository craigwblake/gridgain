// Copyright (C) GridGain Systems, Inc. Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.schedule;

import it.sauronsoftware.cron4j.*;
import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.lang.utils.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.typedef.*;
import org.gridgain.grid.typedef.internal.*;
import org.gridgain.grid.util.tostring.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

import static java.util.concurrent.TimeUnit.*;

/**
 * Implementation of {@link GridScheduleFuture} interface.
 *
 * @author 2005-2011 Copyright (C) GridGain Systems, Inc.
 * @version 3.5.1c.17112011
 */
class GridScheduleFutureImpl<R> extends GridMetadataAwareAdapter implements GridScheduleFuture<R>, Externalizable {
    /** Empty time array. */
    private static final long[] EMPTY_TIMES = new long[] {};

    /** Identifier generated by cron scheduler. */
    private volatile String id;

    /** Scheduling pattern. */
    private String pat;

    /** Scheduling delay in seconds parsed from pattern. */
    private int delay;

    /** Number of maximum task calls parsed from pattern. */
    private int maxCalls;

    /** Mere cron pattern parsed from extended pattern. */
    private String cron;

    /** Cancelled flag. */
    private boolean cancelled;

    /** Done flag. */
    private boolean done;

    /** Task calls counter. */
    private int callCnt;

    /** De-schedule flag. */
    private final AtomicBoolean descheduled = new AtomicBoolean(false);

    /** Listeners. */
    private Collection<GridInClosure<? super GridFuture<R>>> lsnrs =
        new ArrayList<GridInClosure<? super GridFuture<R>>>(1);

    /** Statistics. */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private GridScheduleStatistics stats = new GridScheduleStatistics();

    /** Latch synchronizing fetch of the next execution result. */
    @GridToStringExclude
    private CountDownLatch resLatch = new CountDownLatch(1);

    /** Cron scheduler. */
    @GridToStringExclude
    private Scheduler sched;

    /** Processor registry. */
    @GridToStringExclude
    private GridKernalContext ctx;

    /** Execution task. */
    @GridToStringExclude
    private Callable<R> task;

    /** Result of the last execution of scheduled task. */
    @GridToStringExclude
    private R lastRes;

    /** Keeps last execution exception or {@code null} if the last execution was successful. */
    @GridToStringExclude
    private Throwable lastErr;

    /** Listener call count. */
    private int lastLsnrExecCnt;

    /** Synchronous notification flag. */
    private volatile boolean syncNotify = U.isFutureNotificationSynchronous("true");

    /** Concurrent notification flag. */
    private volatile boolean concurNotify = U.isFutureNotificationConcurrent("false");

    /** Mutex. */
    private final Object mux = new Object();

    /** Grid logger. */
    private GridLogger log;

    /** Runnable object to schedule with cron scheduler. */
    private final Runnable run = new Runnable() {
        @Nullable private CountDownLatch onStart() {
            synchronized (mux) {
                if (done || cancelled)
                    return null;

                if (stats.isRunning()) {
                    U.warn(log, "Task got scheduled while previous was not finished: " + this);

                    return null;
                }

                if (callCnt == maxCalls && maxCalls > 0)
                    return null;

                callCnt++;

                stats.onStart();

                assert resLatch != null;

                return resLatch;
            }
        }

        private boolean onEnd(CountDownLatch latch, R res, Throwable err) {
            assert latch != null;

            boolean notifyLsnr = false;

            CountDownLatch resLatchCopy = null;

            try {
                synchronized (mux) {
                    lastRes = res;
                    lastErr = err;

                    stats.onEnd();

                    int cnt = stats.getExecutionCount();

                    if (lastLsnrExecCnt != cnt) {
                        notifyLsnr = true;

                        lastLsnrExecCnt = cnt;
                    }

                    if ((callCnt == maxCalls && maxCalls > 0) || cancelled) {
                        done = true;

                        resLatchCopy = resLatch;

                        resLatch = null;

                        return false;
                    }

                    resLatch = new CountDownLatch(1);

                    return true;
                }
            }
            finally {
                // Unblock all get() invocations.
                latch.countDown();

                // Make sure that none will be blocked on new latch if this
                // future will not be executed any more.
                if (resLatchCopy != null)
                    resLatchCopy.countDown();

                if (notifyLsnr)
                    notifyListeners(res, err);
            }
        }

        @SuppressWarnings({"ErrorNotRethrown"})
        @Override public void run() {
            CountDownLatch latch = onStart();

            if (latch == null) {
                return;
            }

            R res = null;

            Throwable err = null;

            try {
                res = task.call();
            }
            catch (Exception e) {
                err = e;
            }
            catch (Error e) {
                err = e;

                U.error(log, "Error occurred while executing scheduled task: " + this, e);
            }
            finally {
                if (!onEnd(latch, res, err))
                    deschedule();
            }
        }
    };

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridScheduleFutureImpl() {
        // No-op.
    }

    /**
     * Creates descriptor for task scheduling. To start scheduling call {@link #schedule(Callable)}.
     *
     * @param sched Cron scheduler.
     * @param ctx Kernal context.
     * @param pat Cron pattern.
     * @throws GridException If pattern was invalid.
     */
    GridScheduleFutureImpl(Scheduler sched, GridKernalContext ctx, String pat)
        throws GridException {
        assert sched != null;
        assert ctx != null;
        assert pat != null;

        this.sched = sched;
        this.ctx = ctx;
        this.pat = pat.trim();

        parsePatternParameters();

        log = ctx.log(getClass());
    }

    /** {@inheritDoc} */
    @Override public boolean concurrentNotify() {
        return concurNotify;
    }

    /** {@inheritDoc} */
    @Override public void concurrentNotify(boolean concurNotify) {
        this.concurNotify = concurNotify;
    }

    /** {@inheritDoc} */
    @Override public boolean syncNotify() {
        return syncNotify;
    }

    /** {@inheritDoc} */
    @Override public void syncNotify(boolean syncNotify) {
        this.syncNotify = syncNotify;
    }

    /**
     * Sets execution task.
     *
     * @param task Execution task.
     */
    void schedule(Callable<R> task) {
        assert task != null;
        assert this.task == null;

        this.task = task;

        ctx.schedule().onScheduled(this);

        if (delay > 0) {
            // Schedule after delay.
            ctx.timeout().addTimeoutObject(new GridTimeoutObject() {
                private final GridUuid uid = GridUuid.randomUuid();

                private final long endTime = createTime() + delay * 1000;

                @Override public GridUuid timeoutId() {
                    return uid;
                }

                @Override public long endTime() {
                    return endTime;
                }

                @Override public void onTimeout() {
                    assert id == null;

                    try {
                        id = sched.schedule(cron, run);
                    }
                    catch (InvalidPatternException e) {
                        // This should never happen as we validated the pattern during parsing.
                        e.printStackTrace();

                        assert false : "Invalid scheduling pattern: " + cron;
                    }
                }
            });
        }
        else {
            assert id == null;

            try {
                id = sched.schedule(cron, run);
            }
            catch (InvalidPatternException e) {
                // This should never happen as we validated the pattern during parsing.
                e.printStackTrace();

                assert false : "Invalid scheduling pattern: " + cron;
            }
        }
    }

    /**
     * De-schedules scheduled task.
     */
    void deschedule() {
        if (descheduled.compareAndSet(false, true)) {
            sched.deschedule(id);

            ctx.schedule().onDescheduled(this);
        }
    }

    /**
     * Parse delay, number of task calls and mere cron expression from extended pattern
     *  that looks like  "{n1,n2} * * * * *".
     * @throws GridException Thrown if pattern is invalid.
     */
    private void parsePatternParameters() throws GridException {
        assert pat != null;

        String regEx = "(\\{(\\*|\\d+),\\s*(\\*|\\d+)\\})?(.*)";

        Matcher matcher = Pattern.compile(regEx).matcher(pat.trim());

        if (matcher.matches()) {
            String delayStr = matcher.group(2);

            if (delayStr != null)
                if ("*".equals(delayStr))
                    delay = 0;
                else
                    try {
                        delay = Integer.valueOf(delayStr);
                    }
                    catch (NumberFormatException e) {
                        throw new GridException("Invalid delay parameter in schedule pattern [delay=" +
                            delayStr + ", pattern=" + pat + ']', e);
                    }

            String numOfCallsStr = matcher.group(3);

            if (numOfCallsStr != null)
                if ("*".equals(numOfCallsStr))
                    maxCalls = 0;
                else {
                    try {
                        maxCalls = Integer.valueOf(numOfCallsStr);
                    }
                    catch (NumberFormatException e) {
                        throw new GridException("Invalid number of calls parameter in schedule pattern [numOfCalls=" +
                            numOfCallsStr + ", pattern=" + pat + ']', e);
                    }

                    if (maxCalls == 0)
                        throw new GridException("Number of calls must be greater than 0 or must equal to \"*\"" +
                            " in schedule pattern [numOfCalls=" + maxCalls + ", pattern=" + pat + ']');
                }

            cron = matcher.group(4);

            if (cron != null)
                cron = cron.trim();

            // Cron expression should never be empty and should be of correct format.
            if (cron.isEmpty() || !SchedulingPattern.validate(cron))
                throw new GridException("Invalid cron expression in schedule pattern: " + pat);
        }
        else
            throw new GridException("Invalid schedule pattern: " + pat);
    }

    /** {@inheritDoc} */
    @Override public long startTime() {
        return stats.getCreateTime();
    }

    /** {@inheritDoc} */
    @Override public long duration() {
        return stats.getTotalExecutionTime() + stats.getTotalIdleTime();
    }

    /** {@inheritDoc} */
    @Override public String pattern() {
        return pat;
    }

    /** {@inheritDoc} */
    @Override public String id() {
        return id;
    }

    /** {@inheritDoc} */
    @Override public long[] nextExecutionTimes(int cnt, long start) throws GridException {
        assert cnt > 0;
        assert start > 0;

        if (isDone() || isCancelled())
            return EMPTY_TIMES;

        if (maxCalls > 0)
            cnt = Math.min(cnt, maxCalls);

        long[] times = new long[cnt];

        if (start < createTime() + delay * 1000)
            start = createTime() + delay * 1000;

        SchedulingPattern pattern = new SchedulingPattern(cron);

        Predictor p = new Predictor(pattern, start);

        for (int i = 0; i < cnt; i++)
            times[i] = p.nextMatchingTime();

        return times;
    }

    /** {@inheritDoc} */
    @Override public long nextExecutionTime() throws GridException {
        return nextExecutionTimes(1, System.currentTimeMillis())[0];
    }

    /** {@inheritDoc} */
    @Override public boolean cancel() {
        synchronized (mux) {
            if (done)
                return false;

            if (cancelled)
                return true;

            if (!stats.isRunning())
                done = true;

            cancelled = true;
        }

        deschedule();

        return true;
    }

    /** {@inheritDoc} */
    @Override public long createTime() {
        synchronized (mux) {
            return stats.getCreateTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastStartTime() {
        synchronized (mux) {
            return stats.getLastStartTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastFinishTime() {
        synchronized (mux) {
            return stats.getLastEndTime();
        }
    }

    /** {@inheritDoc} */
    @Override public double averageExecutionTime() {
        synchronized (mux) {
            return stats.getLastExecutionTime();
        }
    }

    /** {@inheritDoc} */
    @Override public long lastIdleTime() {
        synchronized (mux) {
            return stats.getLastIdleTime();
        }
    }

    /** {@inheritDoc} */
    @Override public double averageIdleTime() {
        synchronized (mux) {
            return stats.getAverageIdleTime();
        }
    }

    /** {@inheritDoc} */
    @Override public int count() {
        synchronized (mux) {
            return stats.getExecutionCount();
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isRunning() {
        synchronized (mux) {
            return stats.isRunning();
        }
    }

    /** {@inheritDoc} */
    @Override public R last() throws GridException {
        synchronized (mux) {
            if (lastErr != null)
                throw U.cast(lastErr);

            return lastRes;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isCancelled() {
        synchronized (mux) {
            return cancelled;
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isDone() {
        synchronized (mux) {
            return done;
        }
    }

    /** {@inheritDoc} */
    @Override public GridAbsPredicate predicate() {
        return new PA() {
            @Override public boolean apply() {
                return isDone();
            }
        };
    }

    /** {@inheritDoc} */
    @Override public void listenAsync(@Nullable GridInClosure<? super GridFuture<R>> lsnr) {
        if (lsnr != null) {
            Throwable err;
            R res;

            boolean notifyLsnr = false;

            synchronized (mux) {
                lsnrs.add(lsnr);

                err = lastErr;
                res = lastRes;

                int cnt = stats.getExecutionCount();

                if (cnt > 0 && lastLsnrExecCnt != cnt) {
                    lastLsnrExecCnt = cnt;

                    notifyLsnr = true;
                }
            }

            // Avoid race condition in case if listener was added after
            // first execution completed.
            if (notifyLsnr)
                notifyListener(lsnr, res, err, syncNotify);
        }
    }

    /** {@inheritDoc} */
    @Override public void stopListenAsync(@Nullable GridInClosure<? super GridFuture<R>>... lsnr) {
        if (!F.isEmpty(lsnr))
            synchronized (mux) {
                lsnrs.removeAll(F.asList(lsnr));
            }
    }

    /**
     * @param lsnr Listener to notify.
     * @param res Last execution result.
     * @param err Last execution error.
     * @param syncNotify Synchronous notification flag.
     */
    private void notifyListener(final GridInClosure<? super GridFuture<R>> lsnr, R res, Throwable err,
        boolean syncNotify) {
        assert lsnr != null;
        assert !Thread.holdsLock(mux);
        assert ctx != null;

        final GridScheduleFuture<R> snapshot = snapshot(res, err);

        if (syncNotify)
            lsnr.apply(snapshot);
        else {
            try {
                ctx.closure().runLocalSafe(new Runnable() {
                    @Override public void run() {
                        lsnr.apply(snapshot);
                    }
                }, true);
            }
            catch (Throwable e) {
                U.error(log, "Failed to notify listener: " + this, e);
            }
        }
    }

    /**
     * @param res Last execution result.
     * @param err Last execution error.
     */
    private void notifyListeners(R res, Throwable err) {
        final Collection<GridInClosure<? super GridFuture<R>>> tmp;

        synchronized (mux) {
            tmp = new ArrayList<GridInClosure<? super GridFuture<R>>>(lsnrs);
        }

        final GridScheduleFuture<R> snapshot = snapshot(res, err);

        if (concurNotify) {
            for (final GridInClosure<? super GridFuture<R>> lsnr : tmp)
                ctx.closure().runLocalSafe(new GPR() {
                    @Override public void run() {
                        lsnr.apply(snapshot);
                    }
                }, true);
        }
        else {
            ctx.closure().runLocalSafe(new GPR() {
                @Override public void run() {
                    for (GridInClosure<? super GridFuture<R>> lsnr : tmp)
                        lsnr.apply(snapshot);
                }
            }, true);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public R call() throws Exception {
        return get();
    }

    /**
     * Checks that the future is in valid state for get operation.
     *
     * @return Latch or {@code null} if future has been finished.
     * @throws GridFutureCancelledException If was cancelled.
     */
    @Nullable private CountDownLatch ensureGet() throws GridFutureCancelledException {
        synchronized (mux) {
            if (cancelled)
                throw new GridFutureCancelledException("Scheduling has been cancelled: " + this);

            if (done)
                return null;

            return resLatch;
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get() throws GridException {
        CountDownLatch latch = ensureGet();

        if (latch != null) {
            try {
                latch.await();
            }
            catch (InterruptedException e) {
                if (isCancelled())
                    throw new GridFutureCancelledException(e);

                if (isDone())
                    return last();

                throw new GridInterruptedException(e);
            }
        }

        return last();
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get(long timeout) throws GridException {
        return get(timeout, MILLISECONDS);
    }

    /** {@inheritDoc} */
    @Nullable @Override public R get(long timeout, TimeUnit unit) throws GridException {
        CountDownLatch latch = ensureGet();

        if (latch != null) {
            try {
                if (latch.await(timeout, unit))
                    return last();
                else
                    throw new GridFutureTimeoutException("Timed out waiting for completion of next " +
                        "scheduled computation: " + this);
            }
            catch (InterruptedException e) {
                if (isCancelled())
                    throw new GridFutureCancelledException(e);

                if (isDone())
                    return last();

                throw new GridInterruptedException(e);
            }
        }

        return last();
    }

    /**
     * Creates a snapshot of this future with fixed last result.
     *
     * @param res Last result.
     * @param err Last error.
     * @return Future snapshot.
     */
    private GridScheduleFuture<R> snapshot(R res, Throwable err) {
        return new GridScheduleFutureSnapshot<R>(this, res, err);
    }

    /**
     * Future snapshot.
     *
     * @param <R>
     */
    private static class GridScheduleFutureSnapshot<R> extends GridMetadataAwareAdapter implements
        GridScheduleFuture<R> {
        /** */
        private GridScheduleFutureImpl<R> ref;

        /** */
        private R res;

        /** */
        private Throwable err;

        /**
         *
         * @param ref Referenced implementation.
         * @param res Last result.
         * @param err Throwable.
         */
        GridScheduleFutureSnapshot(GridScheduleFutureImpl<R> ref, R res, Throwable err) {
            assert ref != null;

            this.ref = ref;
            this.res = res;
            this.err = err;
        }

        /** {@inheritDoc} */
        @Override public R last() throws GridException {
            if (err != null)
                throw U.cast(err);

            return res;
        }

        /** {@inheritDoc} */
        @Override public GridAbsPredicate predicate() {
            return new PA() {
                @Override public boolean apply() {
                    return isDone();
                }
            };
        }

        /** {@inheritDoc} */
        @Override public long startTime() {
            return ref.startTime();
        }

        /** {@inheritDoc} */
        @Override public long duration() {
            return ref.duration();
        }

        /** {@inheritDoc} */
        @Override public boolean concurrentNotify() {
            return ref.concurrentNotify();
        }

        /** {@inheritDoc} */
        @Override public void concurrentNotify(boolean concurNotify) {
            ref.concurrentNotify(concurNotify);
        }

        /** {@inheritDoc} */
        @Override public void syncNotify(boolean syncNotify) {
            ref.syncNotify(syncNotify);
        }

        /** {@inheritDoc} */
        @Override public boolean syncNotify() {
            return ref.syncNotify();
        }

        /** {@inheritDoc} */
        @Override public String id() {
            return ref.id();
        }

        /** {@inheritDoc} */
        @Override public String pattern() {
            return ref.pattern();
        }

        /** {@inheritDoc} */
        @Override public long createTime() {
            return ref.createTime();
        }

        /** {@inheritDoc} */
        @Override public long lastStartTime() {
            return ref.lastStartTime();
        }

        /** {@inheritDoc} */
        @Override public long lastFinishTime() {
            return ref.lastFinishTime();
        }

        /** {@inheritDoc} */
        @Override public double averageExecutionTime() {
            return ref.averageExecutionTime();
        }

        /** {@inheritDoc} */
        @Override public long lastIdleTime() {
            return ref.lastIdleTime();
        }

        /** {@inheritDoc} */
        @Override public double averageIdleTime() {
            return ref.averageIdleTime();
        }

        /** {@inheritDoc} */
        @Override public long[] nextExecutionTimes(int cnt, long start) throws GridException {
            return ref.nextExecutionTimes(cnt, start);
        }

        /** {@inheritDoc} */
        @Override public int count() {
            return ref.count();
        }

        /** {@inheritDoc} */
        @Override public boolean isRunning() {
            return ref.isRunning();
        }

        /** {@inheritDoc} */
        @Override public long nextExecutionTime() throws GridException {
            return ref.nextExecutionTime();
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get() throws GridException {
            return ref.get();
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get(long timeout) throws GridException {
            return ref.get(timeout);
        }

        /** {@inheritDoc} */
        @Nullable @Override public R get(long timeout, TimeUnit unit) throws GridException {
            return ref.get(timeout, unit);
        }

        /** {@inheritDoc} */
        @Override public boolean cancel() {
            return ref.cancel();
        }

        /** {@inheritDoc} */
        @Override public boolean isDone() {
            return ref.isDone();
        }

        /** {@inheritDoc} */
        @Override public boolean isCancelled() {
            return ref.isCancelled();
        }

        /** {@inheritDoc} */
        @Override public void listenAsync(@Nullable GridInClosure<? super GridFuture<R>> lsnr) {
            ref.listenAsync(lsnr);
        }

        /** {@inheritDoc} */
        @Override public void stopListenAsync(@Nullable GridInClosure<? super GridFuture<R>>... lsnr) {
            ref.stopListenAsync(lsnr);
        }

        /** {@inheritDoc} */
        @Nullable @Override public R call() throws Exception {
            return ref.call();
        }
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        boolean cancelled;
        R lastRes;
        Throwable lastErr;
        GridScheduleStatistics stats;

        synchronized (mux) {
            cancelled = this.cancelled;
            lastRes = this.lastRes;
            lastErr = this.lastErr;
            stats = this.stats;
        }

        out.writeBoolean(cancelled);
        out.writeObject(lastRes);
        out.writeObject(lastErr);
        out.writeObject(stats);

        out.writeBoolean(syncNotify);
        out.writeBoolean(concurNotify);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        CountDownLatch latch = new CountDownLatch(0);

        boolean cancelled = in.readBoolean();
        R lastRes = (R)in.readObject();
        Throwable lastErr = (Throwable)in.readObject();
        GridScheduleStatistics stats = (GridScheduleStatistics)in.readObject();

        syncNotify = in.readBoolean();
        concurNotify = in.readBoolean();

        synchronized (mux) {
            done = true;

            resLatch = latch;

            this.cancelled = cancelled;
            this.lastRes = lastRes;
            this.lastErr = lastErr;
            this.stats = stats;
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridScheduleFutureImpl.class, this);
    }
}
