package com.devebot.opflow;

import com.devebot.opflow.exception.OpflowRequestSuspendException;
import com.devebot.opflow.exception.OpflowRequestWaitingException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pnhung177
 */
public class OpflowRestrictor {

    private final static Logger LOG = LoggerFactory.getLogger(OpflowRestrictor.class);

    public interface Action<T> extends OpflowRestrictable.Action<T> {}

    public static abstract class Filter<T> extends OpflowRestrictable.Filter<T> {
        protected OpflowLogTracer logTracer = OpflowLogTracer.ROOT.copy();

        public Filter<T> setLogTracer(OpflowLogTracer logTracer) {
            this.logTracer = logTracer;
            return this;
        }
    }

    public static class OnOff<T> extends Filter<T> {
        private boolean active;
        
        public OnOff(Map<String, Object> options) {
            options = OpflowUtil.ensureNotNull(options);

            if (options.get("active") instanceof Boolean) {
                active = (Boolean) options.get("active");
            } if (options.get("enabled") instanceof Boolean) {
                active = (Boolean) options.get("enabled");
            } else {
                active = true;
            }
        }
        
        public boolean isActive() {
            return active;
        }

        public void setActive(boolean enabled) {
            this.active = enabled;
        }
        
        @Override
        public T filter(OpflowRestrictable.Action<T> action) throws Throwable {
            if (!isActive()) {
                return action.process();
            }
            return this.execute(action);
        }
    }

    public static class Valve<T> extends Filter<T> {
        private final ReentrantReadWriteLock valveLock;

        public Valve() {
            this(null);
        }
        
        public Valve(Map<String, Object> options) {
            this.valveLock = new ReentrantReadWriteLock();
        }

        public boolean isLocked() {
            return valveLock.isWriteLocked();
        }

        public void lock() {
            if(!valveLock.isWriteLockedByCurrentThread()) {
                valveLock.writeLock().lock();
            }
        }

        public void unlock() {
            if(valveLock.isWriteLockedByCurrentThread()) {
                valveLock.writeLock().unlock();
            }
        }

        @Override
        public T filter(OpflowRestrictable.Action<T> action) throws Throwable {
            Lock rl = this.valveLock.readLock();
            if (rl.tryLock()) {
                try {
                    return this.execute(action);
                }
                finally {
                    rl.unlock();
                }
            } else {
                if (logTracer.ready(LOG, "warn")) LOG.warn(logTracer
                        .text("Restrictor[${restrictorId}].filter() is not ready yet")
                        .stringify());
                throw new OpflowRequestSuspendException("The valve restrictor is not ready yet");
            }
        }
    }

    public static class Pause<T> extends Filter<T> implements AutoCloseable {

        private final long PAUSE_SLEEPING_INTERVAL = 500;
        private final long PAUSE_TIMEOUT_DEFAULT = 0l;

        private final ReentrantReadWriteLock pauseLock;
        private boolean pauseEnabled;
        private long pauseTimeout;
        private PauseThread pauseThread;
        private ExecutorService threadExecutor;

        public Pause() {
            this(null);
        }

        public Pause(Map<String, Object> options) {
            options = OpflowUtil.ensureNotNull(options);

            pauseLock = new ReentrantReadWriteLock();

            if (options.get("pauseEnabled") instanceof Boolean) {
                pauseEnabled = (Boolean) options.get("pauseEnabled");
            } else {
                pauseEnabled = true;
            }

            if (options.get("pauseTimeout") instanceof Long) {
                pauseTimeout = (Long) options.get("pauseTimeout");
            } else if (options.get("pauseTimeout") instanceof Integer) {
                pauseTimeout = (Integer) options.get("pauseTimeout");
            } else {
                pauseTimeout = PAUSE_TIMEOUT_DEFAULT;
            }
        }
        
        private class PauseThread extends Thread {
            private final ReentrantReadWriteLock rwlock;
            private final String instanceId;
            private final OpflowLogTracer tracer;
            private long duration = 0;
            private long elapsed = 0;
            private long count = 0;
            private boolean running = true;

            public String getInstanceId() {
                return instanceId;
            }

            public long getDuration() {
                return duration;
            }

            public long getElapsed() {
                return elapsed;
            }

            public boolean isLocked() {
                return rwlock.isWriteLocked();
            }

            public void init(long duration) {
                this.duration = duration;
                this.count = 0;
                this.running = true;
            }

            public void terminate() {
                running = false;
            }

            PauseThread(OpflowLogTracer logTracer, ReentrantReadWriteLock rwlock) {
                this.rwlock = rwlock;
                this.instanceId = OpflowUUID.getBase64ID();
                this.tracer = logTracer.copy();
                if (tracer.ready(LOG, "trace")) LOG.trace(tracer
                        .text("PauseThread[${restrictorId}] constructed")
                        .stringify());
            }

            @Override
            public void run() {
                if (duration <= 0) return;
                if (rwlock.isWriteLocked()) return;
                rwlock.writeLock().lock();
                try {
                    if(rwlock.isWriteLockedByCurrentThread()) {
                        if (tracer.ready(LOG, "trace")) LOG.trace(tracer
                                .put("duration", duration)
                                .text("PauseThread[${restrictorId}].run() sleeping in ${duration} ms")
                                .stringify());
                        count = duration;
                        while (running && count > 0) {
                            Thread.sleep((count < PAUSE_SLEEPING_INTERVAL) ? count : PAUSE_SLEEPING_INTERVAL);
                            count -= PAUSE_SLEEPING_INTERVAL;
                            elapsed = duration - count;
                        }
                    }
                }
                catch (InterruptedException e) {
                    if (tracer.ready(LOG, "trace")) LOG.trace(tracer
                            .text("PauseThread[${restrictorId}].run() is interrupted")
                            .stringify());
                }
                finally {
                    if (tracer.ready(LOG, "trace")) LOG.trace(tracer
                            .text("PauseThread[${restrictorId}].run() wake-up")
                            .stringify());
                    if(rwlock.isWriteLockedByCurrentThread()) {
                        if (tracer.ready(LOG, "trace")) LOG.trace(tracer
                                .text("PauseThread[${restrictorId}].run() done!")
                                .stringify());
                        rwlock.writeLock().unlock();
                    }
                }
            }
        }

        public boolean isPauseEnabled() {
            return pauseEnabled;
        }

        public long getPauseTimeout() {
            return pauseTimeout;
        }

        public long getPauseDuration() {
            if (pauseThread == null) {
                return 0;
            }
            return pauseThread.getDuration();
        }

        public long getPauseElapsed() {
            if (pauseThread == null) {
                return 0;
            }
            return pauseThread.getElapsed();
        }
        
        public boolean isPaused() {
            if (pauseThread == null) {
                return false;
            }
            return pauseThread.isLocked();
        }

        public synchronized Map<String, Object> pause(final long duration) {
            if (pauseThread == null) {
                pauseThread = new PauseThread(logTracer, pauseLock);
            }
            Map<String, Object> result = OpflowUtil.buildOrderedMap()
                    .put("threadId", pauseThread.getInstanceId())
                    .put("status", "skipped")
                    .toMap();
            if (!pauseThread.isLocked()) {
                pauseThread.init(duration);
                if (threadExecutor == null) {
                    threadExecutor = Executors.newSingleThreadExecutor();
                }
                threadExecutor.execute(pauseThread);
                result.put("duration", duration);
                result.put("status", pauseThread.isLocked() ? "locked" : "locking");
            }
            return result;
        }

        public synchronized Map<String, Object> unpause() {
            Map<String, Object> result = OpflowUtil.buildOrderedMap()
                    .put("threadId", pauseThread.getInstanceId())
                    .toMap();
            if (pauseThread == null) {
                result.put("status", "free");
            } else {
                pauseThread.terminate();
                result.put("status", pauseThread.isLocked() ? "unlocking" : "unlocked");
            }
            return result;
        }

        @Override
        public T filter(OpflowRestrictable.Action<T> action) throws Throwable {
            if (!pauseEnabled) {
                return this.execute(action);
            }
            Lock rl = pauseLock.readLock();
            if (pauseTimeout >= 0) {
                if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                        .put("pauseTimeout", pauseTimeout)
                        .text("Restrictor[${restrictorId}].filter() pauseTimeout: ${pauseTimeout} ms")
                        .stringify());
                try {
                    if (rl.tryLock() || (pauseTimeout > 0 && rl.tryLock(pauseTimeout, TimeUnit.MILLISECONDS))) {
                        try {
                            if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                    .text("Restrictor[${restrictorId}].filter() try")
                                    .stringify());
                            return this.execute(action);
                        }
                        finally {
                            if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                    .text("Restrictor[${restrictorId}].filter() finally")
                                    .stringify());
                            rl.unlock();
                        }
                    } else {
                        if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                .text("Restrictor[${restrictorId}].filter() tryLock() is timeout")
                                .stringify());
                        throw new OpflowRequestSuspendException("tryLock() return false - the lock is not available");
                    }
                }
                catch (InterruptedException exception) {
                    if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                            .text("Restrictor[${restrictorId}].filter() tryLock() is interrupted")
                            .stringify());
                    throw new OpflowRequestSuspendException("tryLock() is interrupted", exception);
                }
            } else {
                if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                        .put("pauseTimeout", pauseTimeout)
                        .text("Restrictor[${restrictorId}].filter() without pauseTimeout")
                        .stringify());
                rl.lock();
                try {
                    return this.execute(action);
                }
                finally {
                    rl.unlock();
                }
            }
        }
        
        @Override
        public synchronized void close() {
            if (threadExecutor != null) {
                if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                        .text("Restrictor[${restrictorId}].close() disable new tasks from being submitted")
                        .stringify());
                threadExecutor.shutdown();
                try {
                    if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                            .text("Restrictor[${restrictorId}].close() wait a while for existing tasks to terminate")
                            .stringify());
                    if (!threadExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                        if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                .text("Restrictor[${restrictorId}].close() cancel currently executing tasks")
                                .stringify());
                        threadExecutor.shutdownNow();
                        if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                .text("Restrictor[${restrictorId}].close() wait a while for tasks to respond to being cancelled")
                                .stringify());
                        if (!threadExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                            if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                                    .text("Restrictor[${restrictorId}].close() threadExecutor did not terminate")
                                    .stringify());
                        }
                    }
                } catch (InterruptedException ie) {
                    if (logTracer.ready(LOG, "trace")) LOG.trace(logTracer
                            .text("Restrictor[${restrictorId}].close() (re-)cancel if current thread also interrupted")
                            .stringify());
                    threadExecutor.shutdownNow();
                }
                finally {
                    threadExecutor = null;
                }
            }
        }
    }

    public static class Limit<T> extends Filter<T> {

        private final int SEMAPHORE_LIMIT_DEFAULT = 1000;
        private final long SEMAPHORE_TIMEOUT_DEFAULT = 0l;

        private boolean semaphoreEnabled;
        private long semaphoreTimeout;
        private final int semaphoreLimit;
        private final Semaphore semaphore;
        
        public Limit(Map<String, Object> options) {
            options = OpflowUtil.ensureNotNull(options);
            
            if (options.get("semaphoreEnabled") instanceof Boolean) {
                semaphoreEnabled = (Boolean) options.get("semaphoreEnabled");
            } else {
                semaphoreEnabled = false;
            }

            if (options.get("semaphoreTimeout") instanceof Long ) {
                semaphoreTimeout = (Long) options.get("semaphoreTimeout");
            } else if (options.get("semaphoreTimeout") instanceof Integer) {
                semaphoreTimeout = (Integer) options.get("semaphoreTimeout");
            } else {
                semaphoreTimeout = SEMAPHORE_TIMEOUT_DEFAULT;
            }

            if (options.get("semaphoreLimit") instanceof Integer) {
                int _limit = (Integer) options.get("semaphoreLimit");
                semaphoreLimit = (_limit > 0) ? _limit : SEMAPHORE_LIMIT_DEFAULT;
            } else {
                semaphoreLimit = SEMAPHORE_LIMIT_DEFAULT;
            }

            this.semaphore = new Semaphore(this.semaphoreLimit);
        }

        public int getSemaphoreLimit() {
            return semaphoreLimit;
        }

        public int getSemaphorePermits() {
            return semaphore.availablePermits();
        }

        public boolean isSemaphoreEnabled() {
            return semaphoreEnabled;
        }

        public long getSemaphoreTimeout() {
            return semaphoreTimeout;
        }

        @Override
        public T filter(OpflowRestrictable.Action<T> action) throws Throwable {
            if (!semaphoreEnabled) {
                return action.process();
            }
            try {
                if (semaphoreTimeout > 0) {
                    if (semaphore.tryAcquire(semaphoreTimeout, TimeUnit.MILLISECONDS)) {
                        try {
                            return action.process();
                        }
                        finally {
                            semaphore.release();
                        }
                    } else {
                        throw new OpflowRequestWaitingException("There are no permits available");
                    }
                } else {
                    semaphore.acquire();
                    try {
                        return action.process();
                    }
                    finally {
                        semaphore.release();
                    }
                }
            }
            catch (InterruptedException exception) {
                throw new OpflowRequestWaitingException("semaphore.acquire() is interrupted", exception);
            }
        }
    }
}
