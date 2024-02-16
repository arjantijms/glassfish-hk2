/*
 * Copyright (c) 2024 Contributors to Eclipse Foundation.
 * Copyright (c) 2013, 2024 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.hk2.runlevel.internal;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.Descriptor;
import org.glassfish.hk2.api.IndexedFilter;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.ChangeableRunLevelFuture;
import org.glassfish.hk2.runlevel.ErrorInformation;
import org.glassfish.hk2.runlevel.ProgressStartedListener;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.hk2.runlevel.RunLevelListener;
import org.glassfish.hk2.runlevel.Sorter;
import org.glassfish.hk2.runlevel.utilities.Utilities;

/**
 * This is the implementation of RunLevelFuture.  There should
 * only be one of these active in the system at any time.  Of
 * course users are given a handle to this object, so they can
 * hold onto references to it for as long as they'd like.
 * 
 * @author jwells
 *
 */
public class CurrentTaskFuture implements ChangeableRunLevelFuture {
    private final ReentrantLock lock = new ReentrantLock();
    private final AsyncRunLevelContext asyncContext;
    private final Executor executor;
    private final ServiceLocator locator;
    private int proposedLevel;
    private final boolean useThreads;
    private final List<ServiceHandle<RunLevelListener>> allListenerHandles;
    private final List<ServiceHandle<ProgressStartedListener>> allProgressStartedHandles;
    private final List<ServiceHandle<Sorter>> allSorterHandles;
    private final int maxThreads;
    private final Timer timer;
    private final long cancelTimeout;
    
    private UpAllTheWay upAllTheWay;
    private DownAllTheWay downAllTheWay;
    
    private boolean done = false;
    private boolean cancelled = false;
    private boolean inCallback = false;
    
    /* package */ CurrentTaskFuture(AsyncRunLevelContext asyncContext,
            Executor executor,
            ServiceLocator locator,
            int proposedLevel,
            int maxThreads,
            boolean useThreads,
            long cancelTimeout,
            Timer timer) {
        this.asyncContext = asyncContext;
        this.executor = executor;
        this.locator = locator;
        this.proposedLevel = proposedLevel;
        this.useThreads = useThreads;
        this.maxThreads = maxThreads;
        this.cancelTimeout = cancelTimeout;
        this.timer = timer;
        
        int currentLevel = asyncContext.getCurrentLevel();
        
        allListenerHandles = locator.getAllServiceHandles(RunLevelListener.class);
        allProgressStartedHandles = locator.getAllServiceHandles(ProgressStartedListener.class);
        allSorterHandles = locator.getAllServiceHandles(Sorter.class);
        
        if (currentLevel == proposedLevel) {
            done = true;
        }
        else if (currentLevel < proposedLevel) {
            upAllTheWay = new UpAllTheWay(proposedLevel,
                    this,
                    allListenerHandles,
                    allSorterHandles,
                    maxThreads,
                    useThreads,
                    cancelTimeout);
        }
        else {
            downAllTheWay = new DownAllTheWay(proposedLevel, this, allListenerHandles);
        }
    }
    
    /* package */ void go() {
        UpAllTheWay localUpAllTheWay;
        DownAllTheWay localDownAllTheWay;
        
        lock.lock();
        try {
            localUpAllTheWay = upAllTheWay;
            localDownAllTheWay = downAllTheWay;
        } finally {
            lock.unlock();
        }
        
        if (localUpAllTheWay != null || localDownAllTheWay != null) {
            int currentLevel = asyncContext.getCurrentLevel();
            
            invokeOnProgressStarted(this, currentLevel, allProgressStartedHandles);
        }
        
        go(localUpAllTheWay, localDownAllTheWay);
    }
    
    private void go(UpAllTheWay localUpAllTheWay, DownAllTheWay localDownAllTheWay) {
        if (localUpAllTheWay != null) {
            localUpAllTheWay.go();
        }
        else if (localDownAllTheWay != null) {
            if (useThreads) {
                executor.execute(localDownAllTheWay);
            }
            else {
                localDownAllTheWay.run();
            }
        }
        else {
            asyncContext.jobDone();
        }
    }
    
    @Override
    public boolean isUp() {
        lock.lock();
        try {
            if (upAllTheWay != null) return true;
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public boolean isDown() {
        lock.lock();
        try {
            if (downAllTheWay != null) return true;
            return false;
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see java.util.concurrent.Future#cancel(boolean)
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // Not locking in this order can cause deadlocks
        try {
            asyncContext.lock.lock();
            try {
                lock.lock();
                if (done) return false;
                if (cancelled) return false;
            
                cancelled = true;
            
                if (upAllTheWay != null) {
                    upAllTheWay.cancel();
                }
                else if (downAllTheWay != null) {
                    downAllTheWay.cancel();
                }
            
                return true;
            } finally {
                lock.unlock();
            }
        } finally {
            asyncContext.lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see java.util.concurrent.Future#isCancelled()
     */
    @Override
    public boolean isCancelled() {
        lock.lock();
        try {
            return cancelled;
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see java.util.concurrent.Future#isDone()
     */
    @Override
    public boolean isDone() {
        lock.lock();
        try {
            return done;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public int getProposedLevel() {
        lock.lock();
        try {
            return proposedLevel;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public int changeProposedLevel(int proposedLevel) {
        int oldProposedVal;
        boolean needGo = false;
        lock.lock();
        try {
            if (done) throw new IllegalStateException("Cannot change the proposed level of a future that is already complete");
            if (!inCallback) throw new IllegalStateException(
                    "changeProposedLevel must only be called from inside a RunLevelListener callback method");
            
            oldProposedVal = this.proposedLevel;
            int currentLevel = asyncContext.getCurrentLevel();
            this.proposedLevel = proposedLevel;
            
            if (upAllTheWay != null) {
                if (currentLevel <= proposedLevel) {
                    upAllTheWay.setGoingTo(proposedLevel, false);
                }
                else {
                    // Changing directions to down
                    upAllTheWay.setGoingTo(currentLevel, true); // This will make upAllTheWay stop
                    upAllTheWay = null;
                    
                    downAllTheWay = new DownAllTheWay(proposedLevel, this, allListenerHandles);
                    needGo = true;
                }
            }
            else if (downAllTheWay != null) {
                if (currentLevel >= proposedLevel) {
                    downAllTheWay.setGoingTo(proposedLevel, false);
                }
                else {
                    // Changing directions to up
                    downAllTheWay.setGoingTo(currentLevel, true);  // This will make downAllTheWay stop
                    downAllTheWay = null;
                    
                    upAllTheWay = new UpAllTheWay(proposedLevel,
                            this,
                            allListenerHandles,
                            allSorterHandles,
                            maxThreads,
                            useThreads,
                            cancelTimeout);
                    needGo = true;
                }
            }
            else {
                // Should be impossible
                throw new AssertionError("Can not determine previous job");
            }
        } finally {
            lock.unlock();
        }
        
        if (needGo) {
            go(upAllTheWay, downAllTheWay);
        }
        
        return oldProposedVal;
    }
    
    private void setInCallback(boolean inCallback) {
        lock.lock();
        try {
            this.inCallback = inCallback;
        } finally {
            lock.unlock();
        }
    }

    /* (non-Javadoc)
     * @see java.util.concurrent.Future#get()
     */
    @Override
    public Object get() throws InterruptedException, ExecutionException {
        try {
            return get(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException te) {
            throw new AssertionError(te);
        }
    }

    /* (non-Javadoc)
     * @see java.util.concurrent.Future#get(long, java.util.concurrent.TimeUnit)
     */
    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException,
            ExecutionException, TimeoutException {
        AllTheWay allTheWay = null;
        lock.lock();
        try {
            if (upAllTheWay != null) {
                allTheWay = upAllTheWay;
            }
            else if (downAllTheWay != null) {
                allTheWay = downAllTheWay;
            }
        } finally {
            lock.unlock();
        }
        
        if (allTheWay == null) return null;
        
        Boolean result = null;
        for (;;) {
            try {
                result = allTheWay.waitForResult(timeout, unit);
                if (result == null) {
                    lock.lock();
                    try {
                        if (upAllTheWay != null) {
                            allTheWay = upAllTheWay;
                        }
                        else if (downAllTheWay != null) {
                            allTheWay = downAllTheWay;
                        }
                    } finally {
                        lock.unlock();
                    }
                    
                    continue;
                }
                
                if (!result) {
                    throw new TimeoutException();
                }
                
                lock.lock();
                try {
                    done = true;
                } finally {
                    lock.unlock();
                }
                
                return null;
            }
            catch (MultiException me) {
                lock.lock();
                try {
                    done = true;
                } finally {
                    lock.unlock();
                }
                
                throw new ExecutionException(me);
            }
        }
    }
    
    private void invokeOnProgress(ChangeableRunLevelFuture job, int level,
            List<ServiceHandle<RunLevelListener>> listeners) {
        setInCallback(true);
        try {
            for (ServiceHandle<RunLevelListener> listener : listeners) {
                try {
                    RunLevelListener rll = listener.getService();
                    if (rll != null) {
                        rll.onProgress(job, level);
                    }
                }
                catch (Throwable th) {
                    // TODO:  Need a log message here
               }
            }
        }
        finally {
            setInCallback(false);
        }
    }
    
    private void invokeOnProgressStarted(ChangeableRunLevelFuture job, int level,
            List<ServiceHandle<ProgressStartedListener>> listeners) {
        setInCallback(true);
        try {
            for (ServiceHandle<ProgressStartedListener> listener : listeners) {
                try {
                    ProgressStartedListener psl = listener.getService();
                    if (psl != null) {
                        psl.onProgressStarting(job, level);
                    }
                }
                catch (Throwable th) {
                    // TODO:  Need a log message here
               }
            }
        }
        finally {
            setInCallback(false);
        }
    }
    
    private static void invokeOnCancelled(CurrentTaskFuture job, int levelAchieved,
            List<ServiceHandle<RunLevelListener>> listeners) {
        for (ServiceHandle<RunLevelListener> listener : listeners) {
            try {
                RunLevelListener rll = listener.getService();
                if (rll != null) {
                    rll.onCancelled(new CurrentTaskFutureWrapper(job), levelAchieved);
                }
            }
            catch (Throwable th) {
                // TODO:  Need a log message here
            }
        }
    }
    
    private static ErrorInformation invokeOnError(CurrentTaskFuture job, Throwable th,
            ErrorInformation.ErrorAction action,
            List<ServiceHandle<RunLevelListener>> listeners,
            Descriptor descriptor) {
        ErrorInformationImpl errorInfo = new ErrorInformationImpl(th, action, descriptor);
        
        for (ServiceHandle<RunLevelListener> listener : listeners) {
            try {
                RunLevelListener rll = listener.getService();
                if (rll != null) {
                    rll.onError(new CurrentTaskFutureWrapper(job),
                        errorInfo);
                }
            }
            catch (Throwable th2) {
                 // TODO:  Need a log message here
            }
        }
        
        return errorInfo;
    }
    
    private interface AllTheWay {
        /**
         * The method to call on the internal job
         * 
         * @param timeout The amount of time to wait for a result
         * @param unit The unit of the above time value
         * @return True if the job finished, False if the timeout is up prior to the job
         * finishing, and null if the job was repurposed and the caller may now need to
         * listen on a different job
         * @throws InterruptedException On a thread getting jacked
         * @throws MultiException Other exceptions
         */
        public Boolean waitForResult(long timeout, TimeUnit unit) throws InterruptedException, MultiException;
        
    }
    
    private class UpAllTheWay implements AllTheWay {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();
        
        private int goingTo;
        private final int maxThreads;
        private final boolean useThreads;
        private final CurrentTaskFuture future;
        private final List<ServiceHandle<RunLevelListener>> listeners;
        private final List<ServiceHandle<Sorter>> sorters;
        private final long cancelTimeout;
        
        private int workingOn;
        private UpOneLevel currentJob;
        private boolean cancelled = false;
        private boolean done = false;
        private boolean repurposed = false;
        private MultiException exception = null;
        
        private UpAllTheWay(int goingTo, CurrentTaskFuture future,
                List<ServiceHandle<RunLevelListener>> listeners,
                List<ServiceHandle<Sorter>> sorters,
                int maxThreads,
                boolean useThreads,
                long cancelTimeout) {
            this.goingTo = goingTo;
            this.future = future;
            this.listeners = listeners;
            this.maxThreads = maxThreads;
            this.useThreads = useThreads;
            this.sorters = sorters;
            this.cancelTimeout = cancelTimeout;
            
            workingOn = asyncContext.getCurrentLevel();
        }
        
        private void cancel() {
            lock.lock();
            try {
                cancelled = true;
                asyncContext.levelCancelled();
                currentJob.cancel();
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        public Boolean waitForResult(long timeout, TimeUnit unit) throws InterruptedException, MultiException {
            long totalWaitTimeMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
            
            lock.lock();
            try {
                while (totalWaitTimeMillis > 0L && !done && !repurposed) {
                    long startTime = System.currentTimeMillis();
                    
                    condition.await(totalWaitTimeMillis, TimeUnit.MILLISECONDS);
                    
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    totalWaitTimeMillis -= elapsedTime;
                }
                
                if (repurposed) return null;
                
                if (done && (exception != null)) {
                    throw exception;
                }
                
                return done;
            } finally {
                lock.unlock();
            }
        }
        
        private void setGoingTo(int goingTo, boolean repurposed) {
            lock.lock();
            try {
                this.goingTo = goingTo;
                if (repurposed) {
                    this.repurposed = true;
                }
            } finally {
                lock.unlock();
            }
        }
        
        private void go() {
            if (useThreads) {
                lock.lock();
                try {
                    workingOn++;
                    if (workingOn > goingTo) {
                        if (!repurposed) {
                            asyncContext.jobDone();
                    
                            done = true;
                        }
                        
                        condition.signalAll();
                        return;
                    }
            
                    currentJob = new UpOneLevel(workingOn,
                            this,
                            future,
                            listeners,
                            sorters,
                            maxThreads,
                            cancelTimeout);
            
                    executor.execute(currentJob);
                    return;
                } finally {
                    lock.unlock();
                }
            }
                
            workingOn++;
            while (workingOn <= goingTo) {
                lock.lock();
                try {
                    if (done) break;
                    
                    currentJob = new UpOneLevel(workingOn,
                            this,
                            future,
                            listeners,
                            sorters,
                            0,
                            cancelTimeout);
                } finally {
                    lock.unlock();
                }
                
                currentJob.run();
                
                workingOn++;
            }
             
            lock.lock();
            try {
                if (done) return;
                
                if (!repurposed) {
                    asyncContext.jobDone();
                
                    done = true;
                }
                
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }
        
        private void currentJobComplete(MultiException accumulatedExceptions) {
            asyncContext.clearErrors();
            
            if (accumulatedExceptions != null) {
                DownAllTheWay downer = new DownAllTheWay(workingOn - 1, null, null);
                
                downer.run();
                
                lock.lock();
                try {
                    done = true;
                    this.exception = accumulatedExceptions;
                    condition.signalAll();;
                    
                    asyncContext.jobDone();
                } finally {
                    lock.unlock();
                }
                
                return;
            }
            
            DownAllTheWay downer = null;
            lock.lock();
            try {
                if (cancelled) {
                    downer = new DownAllTheWay(workingOn - 1, null, null);
                }
            } finally {
                lock.unlock();
            }
            
            if (downer != null) {
                downer.run();
                
                invokeOnCancelled(future, workingOn - 1, listeners);
                
                lock.lock();
                try {
                    done = true;
                    condition.signalAll();
                        
                    asyncContext.jobDone();
                        
                    return;
                } finally {
                    lock.unlock();
                }
            }
            
            asyncContext.setCurrentLevel(workingOn);
            invokeOnProgress(future, workingOn, listeners);
                
            if (useThreads) {
                go();
            }
        }
    }
    
    private class UpOneLevel implements Runnable {
        private final ReentrantLock lock = new ReentrantLock();
        private final ReentrantLock queueLock = new ReentrantLock();
        private final int upToThisLevel;
        private final CurrentTaskFuture currentTaskFuture;
        private final List<ServiceHandle<RunLevelListener>> listeners;
        private final List<ServiceHandle<Sorter>> sorters;
        private final UpAllTheWay master;
        private final int maxThreads;
        private final long cancelTimeout;
        private int numJobs;
        private int completedJobs;
        private MultiException accumulatedExceptions;
        private boolean cancelled = false;
        private CancelTimer hardCanceller;
        private int numJobsRunning = 0;
        private boolean hardCancelled = false;
        private final HashSet<ServiceHandle<?>> outstandingHandles = new HashSet<ServiceHandle<?>>();
        
        private UpOneLevel(int paramUpToThisLevel,
                UpAllTheWay master,
                CurrentTaskFuture currentTaskFuture,
                List<ServiceHandle<RunLevelListener>> listeners,
                List<ServiceHandle<Sorter>> sorters,
                int maxThreads,
                long cancelTimeout) {
            this.upToThisLevel = paramUpToThisLevel;
            this.master = master;
            this.maxThreads = maxThreads;
            this.currentTaskFuture = currentTaskFuture;
            this.listeners = listeners;
            this.sorters = sorters;
            this.cancelTimeout = cancelTimeout;
        }
        
        private void cancel() {
            lock.lock();
            try {
                cancelled = true;
                hardCanceller = new CancelTimer(this);
                timer.schedule(hardCanceller, cancelTimeout);
            } finally {
                lock.unlock();
            }
        }
        
        private void hardCancel() {
            asyncContext.lock.lock();
            try {
                lock.lock();
                try {
                    hardCancelled = true;
                } finally {
                    lock.unlock();
                }
                
                HashSet<ServiceHandle<?>> poisonMe;
                queueLock.lock();
                try {
                    poisonMe = new HashSet<ServiceHandle<?>>(outstandingHandles);
                    outstandingHandles.clear();
                } finally {
                    queueLock.unlock();
                }
                
                for (ServiceHandle<?> handle : poisonMe) {
                    asyncContext.hardCancelOne(handle.getActiveDescriptor());
                }
            } finally {
                asyncContext.lock.unlock();
            }
            
            master.currentJobComplete(null);
        }
        
        private void jobRunning(ServiceHandle<?> handle) {
            numJobsRunning++;
            outstandingHandles.add(handle);
        }
        
        private void jobFinished(ServiceHandle<?> handle) {
            outstandingHandles.remove(handle);
            numJobsRunning--;
        }
        
        private int getJobsRunning() {
            return numJobsRunning;
        }
        
        private List<ServiceHandle<?>> applySorters(List<ServiceHandle<?>> jobs) {
            List<ServiceHandle<?>> retVal = jobs;
            
            for (ServiceHandle<Sorter> sorterHandle : sorters) {
                Sorter sorter = sorterHandle.getService();
                if (sorter == null) continue;
                
                List<ServiceHandle<?>> sortedList = sorter.sort(retVal);
                if (sortedList == null) continue;
                
                retVal = sortedList;
            }
            
            return retVal;
        }

        @Override
        public void run() {
            ReentrantLock jobsLock = new ReentrantLock();
            List<ServiceHandle<?>> jobs = locator.getAllServiceHandles(new IndexedFilter() {

                @Override
                public boolean matches(Descriptor d) {
                    return (upToThisLevel == Utilities.getRunLevelValue(locator, d));
                }

                @Override
                public String getAdvertisedContract() {
                    return RunLevel.class.getName();
                }

                @Override
                public String getName() {
                    return null;
                }
                
            });
            
            jobs = applySorters(jobs);
            
            numJobs = jobs.size();
            if (numJobs <= 0) {
                jobComplete();
                return;
            }
            
            int runnersToCreate = ((numJobs < maxThreads) ? numJobs : maxThreads) - 1;
            if (!useThreads) runnersToCreate = 0;
            
            for (int lcv = 0; lcv < runnersToCreate; lcv++) {
                QueueRunner runner = new QueueRunner(locator, asyncContext, jobsLock, jobs, this, lock, maxThreads);
                
                executor.execute(runner);
            }
            
            QueueRunner myRunner = new QueueRunner(locator, asyncContext, jobsLock, jobs, this, lock, maxThreads);
            myRunner.run();
        }
        
        private void fail(Throwable th, Descriptor descriptor) {
            lock.lock();
            try {
                if (hardCancelled) return;
                
                ErrorInformation info = invokeOnError(currentTaskFuture, th,
                        ErrorInformation.ErrorAction.GO_TO_NEXT_LOWER_LEVEL_AND_STOP,
                        listeners,
                        descriptor);
                
                if (ErrorInformation.ErrorAction.IGNORE.equals(info.getAction())) return;
                
                if (accumulatedExceptions == null) {
                    accumulatedExceptions = new MultiException();
                }
                
                accumulatedExceptions.addError(th);
            } finally {
                lock.unlock();
            }
        }
        
        private void jobComplete() {
            boolean complete = false;
            lock.lock();
            try {
                if (hardCancelled) return;
                
                completedJobs++;
                if (completedJobs >= numJobs) {
                    complete = true;
                    if (hardCanceller != null) {
                        hardCanceller.cancel();
                        hardCanceller = null;
                    }
                }
            } finally {
                lock.unlock();
            }
            
            if (complete) {
                master.currentJobComplete(accumulatedExceptions);
            }
        }
        
    }
    
    private static class CancelTimer extends TimerTask {
        private final UpOneLevel parent;
        
        private CancelTimer(UpOneLevel parent) {
            this.parent = parent;
        }

        @Override
        public void run() {
            parent.hardCancel();
        }
    }
    
    /**
     * Goes down all the way to the proposed level
     * 
     * @author jwells
     *
     */
    private class DownAllTheWay implements Runnable, AllTheWay {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition lockCondition = lock.newCondition();
        private int goingTo;
        private CurrentTaskFuture future;
        private final List<ServiceHandle<RunLevelListener>> listeners;
        
        private int workingOn;
        
        private boolean cancelled = false;
        private boolean done = false;
        private boolean repurposed = false;
        
        private Throwable lastError = null;
        private ActiveDescriptor<?> lastErrorDescriptor = null;
        
        private List<ActiveDescriptor<?>> queue = Collections.emptyList();
        private ReentrantLock queueLock = new ReentrantLock();
        private Condition queueCondition = queueLock.newCondition();
        private boolean downHardCancelled = false;
        
        private HardCancelDownTimer hardCancelDownTimer = null;
        
        public DownAllTheWay(int goingTo,
                CurrentTaskFuture future,
                List<ServiceHandle<RunLevelListener>> listeners) {
            this.goingTo = goingTo;
            this.future = future;
            this.listeners = listeners;
            
            if (future == null) {
                // This is an error or cancelled case, so we are pretending
                // we have gotten higher than we have
                workingOn = asyncContext.getCurrentLevel() + 1;
            }
            else {
                workingOn = asyncContext.getCurrentLevel();
            }
        }
        
        private void cancel() {
            List<ActiveDescriptor<?>> localQueue;
            ReentrantLock localQueueLock;
            Condition localQueueCondition;
            lock.lock();
            try {
                if (cancelled) return; // idempotent
                cancelled = true;
                
                if (done) return;
                
                localQueue = queue;
                localQueueLock = queueLock;
                localQueueCondition = queueCondition;
            } finally {
                lock.unlock();
            }
                
            queueLock.lock();
            try {
                if (localQueue.isEmpty()) return;
                
                hardCancelDownTimer = new HardCancelDownTimer(this, localQueue, localQueueLock, localQueueCondition);
                timer.schedule(hardCancelDownTimer, cancelTimeout, cancelTimeout);
            } finally {
                queueLock.unlock();
            }
        }
        
        private void setGoingTo(int goingTo, boolean repurposed) {
            lock.lock();
            try {
                this.goingTo = goingTo;
                if (repurposed) {
                    this.repurposed = true;
                }
            } finally {
                lock.unlock();
            }
        }
        
        private int getGoingTo() {
            lock.lock();
            try {
                return goingTo;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void run() {
            while (workingOn > getGoingTo()) {
                boolean runOnCancelled;
                boolean localCancelled;
                lock.lock();
                try {
                    localCancelled = cancelled;
                    runOnCancelled = cancelled && (future != null);
                } finally {
                    lock.unlock();
                }
                
                if (runOnCancelled) {
                    // Run outside of lock
                    invokeOnCancelled(future, workingOn, listeners);
                }
                
                lock.lock();
                try {
                    if (localCancelled) {
                        asyncContext.jobDone();
                        
                        done = true;
                        lockCondition.signalAll();
                        
                        return;
                    }
                } finally {
                    lock.unlock();
                }
                
                int proceedingTo = workingOn - 1;
                
                // This happens FIRST.  Here the definition of
                // the current level is that level which is guaranteed
                // to have ALL of its known services started.  Once
                // we destroy the first one of them (or are about to)
                // then we are officially at the next level
                asyncContext.setCurrentLevel(proceedingTo);
                
                // But we don't call the proceedTo until all those services are gone
                List<ActiveDescriptor<?>> localQueue = asyncContext.getOrderedListOfServicesAtLevel(workingOn);
                ReentrantLock localQueueLock = new ReentrantLock();
                Condition localQueueCondition = localQueueLock.newCondition();
                lock.lock();
                try {
                    queue = localQueue;
                    queueLock = localQueueLock;
                    queueCondition = localQueueCondition;
                } finally {
                    lock.unlock();
                }
                
                ErrorInformation errorInfo = null;
                queueLock.lock();
                try {
                    for (;;) {
                        DownQueueRunner currentRunner = new DownQueueRunner(queueLock, queueCondition, queue, this, locator);
                        executor.execute(currentRunner);
                    
                        lastError = null;
                        for (;;) {
                            while (!queue.isEmpty() && (lastError == null) && (downHardCancelled == false)) {
                                try {
                                    queueCondition.await();
                                }
                                catch (InterruptedException ie) {
                                    throw new RuntimeException(ie);
                                }
                            }
                            
                            if (downHardCancelled) {
                                currentRunner.caput = true;
                            }
                        
                            if ((lastError != null) && (future != null)) {
                                errorInfo = invokeOnError(future, lastError, ErrorInformation.ErrorAction.IGNORE, listeners, lastErrorDescriptor);
                            }
                            lastError = null;
                            lastErrorDescriptor = null;
                        
                            if (queue.isEmpty() || downHardCancelled) {
                                downHardCancelled = false;
                                break;
                            }
                        }
                        
                        if (queue.isEmpty()) {
                            if (hardCancelDownTimer != null) {
                                hardCancelDownTimer.cancel();
                            }
                            
                            break;
                        }
                    }
                } finally {
                    queueLock.unlock();
                }
                
                lock.lock();
                try {
                    queue = Collections.emptyList();
                    queueLock = new ReentrantLock();
                    queueCondition = queueLock.newCondition();
                } finally {
                    lock.unlock();
                }
                
                if (errorInfo != null && ErrorInformation.ErrorAction.GO_TO_NEXT_LOWER_LEVEL_AND_STOP.equals(errorInfo.getAction())) {
                    lock.lock();
                    try {
                        goingTo = workingOn;
                    } finally {
                        lock.unlock();
                    }
                }
                
                workingOn--;
                
                if (future != null) {
                    invokeOnProgress(future, proceedingTo, listeners);
                }
            }
            
            if (future == null) {
                // This is done as part of a cancel or error, do no
                // notifying this is special
                return;
            }
            
            lock.lock();
            try {
                if (!repurposed) {
                    asyncContext.jobDone();
                
                    done = true;
                }
                
                lockCondition.signalAll();
            } finally {
                lock.unlock();
            }
            
        }
        
        @Override
        public Boolean waitForResult(long timeout, TimeUnit unit) throws InterruptedException, MultiException {
            long totalWaitTimeMillis = TimeUnit.MILLISECONDS.convert(timeout, unit);
            
            lock.lock();
            try {
                while (totalWaitTimeMillis > 0L && !done && !repurposed) {
                    long startTime = System.currentTimeMillis();
                    
                    lockCondition.await(totalWaitTimeMillis, TimeUnit.MILLISECONDS);
                    
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    totalWaitTimeMillis -= elapsedTime;
                }
                
                if (repurposed) return null;
                
                return done;
            } finally {
                lock.unlock();
            }
        }
        
    }
    
    private static class HardCancelDownTimer extends TimerTask {
        private final DownAllTheWay parent;
        private final List<ActiveDescriptor<?>> queue;
        private final ReentrantLock localQueueLock;
        private final Condition localQueueCondition;
        
        private int lastQueueSize;
        
        private HardCancelDownTimer(DownAllTheWay parent, List<ActiveDescriptor<?>> queue, ReentrantLock localQueueLock, Condition localQueueCondition) {
            this.parent = parent;
            this.queue = queue;
            this.localQueueLock = localQueueLock;
            this.localQueueCondition = localQueueCondition;
            lastQueueSize = queue.size();
        }

        @Override
        public void run() {
            localQueueLock.lock();
            try {
                int currentSize = queue.size();
                if (currentSize == 0) return;
                
                if (currentSize == lastQueueSize) {
                    parent.downHardCancelled = true;
                    localQueueCondition.signal();
                }
                else {
                    lastQueueSize = currentSize;
                }
            } finally {
                localQueueLock.unlock();
            }
        }
    }
    
    private static class QueueRunner implements Runnable {
        private final ServiceLocator locator;
        private final AsyncRunLevelContext asyncContext;
        private final ReentrantLock queueLock;
        private final List<ServiceHandle<?>> queue;
        private final UpOneLevel parent;
        private final ReentrantLock parentLock;
        private final int maxThreads;
        private ServiceHandle<?> wouldHaveBlocked;
        private final HashSet<ActiveDescriptor<?>> alreadyTried = new HashSet<ActiveDescriptor<?>>();
        
        private QueueRunner(ServiceLocator locator,
                AsyncRunLevelContext asyncContext,
                ReentrantLock queueLock,
                List<ServiceHandle<?>> queue,
                UpOneLevel parent,
                ReentrantLock parentLock,
                int maxThreads) {
            this.locator = locator;
            this.asyncContext = asyncContext;
            this.queueLock = queueLock;
            this.queue = queue;
            this.parent = parent;
            this.parentLock = parentLock;
            this.maxThreads = maxThreads;
        }

        @Override
        public void run() {
            ServiceHandle<?> runningHandle = null;
            for (;;) {
                ServiceHandle<?> job;
                boolean block;
                queueLock.lock();
                try {
                    if (runningHandle != null) parent.jobFinished(runningHandle);
                    
                    if (wouldHaveBlocked != null) {
                        alreadyTried.add(wouldHaveBlocked.getActiveDescriptor());
                        
                        queue.add(queue.size(), wouldHaveBlocked);
                        wouldHaveBlocked = null;
                    }
                    
                    if (queue.isEmpty()) return;
                    
                    if (maxThreads <= 0) {
                        block = true;
                    }
                    else {
                        int currentlyEmptyThreads = maxThreads - parent.getJobsRunning();
                        block = (queue.size() <= currentlyEmptyThreads);
                    }
                    
                    if (block) {
                        job = queue.remove(0);
                    }
                    else {
                        job = null;
                        for (int lcv = 0; lcv < queue.size(); lcv++) {
                            ActiveDescriptor<?> candidate = queue.get(lcv).getActiveDescriptor();
                            if (!alreadyTried.contains(candidate)) {
                                job = queue.remove(lcv);
                                break;
                            }
                        }
                        if (job == null) {
                            // Every job in the queue is one I've tried already
                            job = queue.remove(0);
                            
                            block = true;
                        }
                    }
                    
                    parent.jobRunning(job);
                    runningHandle = job;
                } finally {
                    queueLock.unlock();
                }
                
                oneJob(job, block);
            }
            
        }
        
        /**
         * This method does a preliminary check of whether or not the descriptor (or any children) would cause
         * the thread to block.  If this method returns true then we do not try this service, which can save
         * on going down the getService stack and on the throwing and creation of WouldBlockException
         * 
         * @param cycleChecker To ensure we are not caught in a cycle
         * @param checkMe The descriptor to check
         * @return false if as far as we know this descriptor would NOT block, true if we think if we tried
         * this descriptor right now that it would block
         */
        private boolean isWouldBlockRightNow(HashSet<ActiveDescriptor<?>> cycleChecker, ActiveDescriptor<?> checkMe) {
            if (checkMe == null) return false;
            
            if (cycleChecker.contains(checkMe)) return false;
            cycleChecker.add(checkMe);
            
            if (asyncContext.wouldBlockRightNow(checkMe)) {
                return true;
            }
            
            if (!checkMe.isReified()) {
                checkMe = locator.reifyDescriptor(checkMe);
            }
            
            for (Injectee ip : checkMe.getInjectees()) {
                ActiveDescriptor<?> childService;
                try {
                    childService = locator.getInjecteeDescriptor(ip);
                }
                catch (MultiException me) {
                    continue;
                }
                
                if (childService == null) continue;
                
                if (!childService.getScope().equals(RunLevel.class.getName())) continue;
                
                if (isWouldBlockRightNow(cycleChecker, childService)) {
                    if (asyncContext.wouldBlockRightNow(checkMe)) {
                        return true;
                    }
                    return true;
                }
            }
            
            return false;
        }
        
        private void oneJob(ServiceHandle<?> fService, boolean block) {
            fService.setServiceData(!block);
            boolean completed = true;
            try {
                boolean ok;
                parentLock.lock();
                try {
                    ok = (!parent.cancelled && (parent.accumulatedExceptions == null));
                } finally {
                    parentLock.unlock();
                }
                
                if (!block && isWouldBlockRightNow(new HashSet<ActiveDescriptor<?>>(), fService.getActiveDescriptor())) {
                    wouldHaveBlocked = fService;
                    completed = false;
                    ok = false;
                }
                
                if (ok) {
                    fService.getService();
                }
            }
            catch (MultiException me) {
                if (!block && isWouldBlock(me)) {
                    // In this case completed is FALSE, as the job has NOT completed
                    wouldHaveBlocked = fService;
                    completed = false;
                }
                else if (!isWasCancelled(me)) {
                    parent.fail(me, fService.getActiveDescriptor());
                }
            }
            catch (Throwable th) {
                parent.fail(th, fService.getActiveDescriptor());
            }
            finally {
                fService.setServiceData(null);
                if (completed) {
                    parent.jobComplete();
                }
            }
        }
    }
    
    private static class DownQueueRunner implements Runnable {
        private final ReentrantLock queueLock;
        private final Condition queueCondition;
        private final List<ActiveDescriptor<?>> queue;
        private final DownAllTheWay parent;
        private final ServiceLocator locator;
        private boolean caput;
        
        private DownQueueRunner(ReentrantLock queueLock,
                Condition queueCondition,
                List<ActiveDescriptor<?>> queue,
                DownAllTheWay parent,
                ServiceLocator locator) {
            this.queueLock = queueLock;
            this.queueCondition = queueCondition;
            this.queue = queue;
            this.parent = parent;
            this.locator = locator;
        }

        @Override
        public void run() {
            for (;;) {
                ActiveDescriptor<?> job;
                queueLock.lock();
                try {
                    if (caput) return;
                    
                    if (queue.isEmpty()) {
                        queueCondition.signal();
                        return;
                    }
                    job = queue.get(0);
                } finally {
                    queueLock.unlock();
                }
                
                try {
                    locator.getServiceHandle(job).destroy();
                } catch (Throwable th) {
                    queueLock.lock();
                    try {
                        parent.lastError = th;
                        parent.lastErrorDescriptor = job;
                        queueCondition.signal();
                    } finally {
                        queueLock.unlock();
                    }
                } finally {
                    queueLock.lock();
                    try {
                        queue.remove(job);
                    } finally {
                        queueLock.unlock();
                    }
                }
            }
        }
    }
    
    /* package */ final static boolean isWouldBlock(Throwable th) {
        return isACertainException(th, WouldBlockException.class);
    }
    
    private final static boolean isWasCancelled(Throwable th) {
        return isACertainException(th, WasCancelledException.class);
    }
    
    private final static boolean isACertainException(Throwable th, Class<? extends Throwable> type) {
        Throwable cause = th;
        while (cause != null) {
            if (cause instanceof MultiException) {
                MultiException me = (MultiException) cause;
                for (Throwable innerMulti : me.getErrors()) {
                    if (isACertainException(innerMulti, type)) {
                        return true;
                    }
                }
            }
            else if (type.isAssignableFrom(cause.getClass())) {
                return true;
            }
            
            cause = cause.getCause();
        }
        
        return false;
    }
    
    @Override
    public String toString() {
        return "CurrentTaskFuture(proposedLevel=" + proposedLevel + "," +
          System.identityHashCode(this) + ")";
    }

    
}
