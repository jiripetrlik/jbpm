/*
 * Copyright 2013 JBoss by Red Hat.
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

package org.jbpm.executor.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.drools.core.time.TimeUtils;
import org.jbpm.executor.ExecutorNotStartedException;
import org.jbpm.executor.entities.RequestInfo;
import org.kie.internal.executor.api.CommandContext;
import org.kie.internal.executor.api.Executor;
import org.kie.internal.executor.api.ExecutorStoreService;
import org.kie.internal.executor.api.STATUS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the <code>Executor</code> that is baced by
 * <code>ScheduledExecutorService</code> for background task execution.
 * It can be configured for following:
 * <ul>
 *  <li>thread pool size - default 1 - use system property org.kie.executor.pool.size</li>
 *  <li>retry count - default 3 retries - use system property org.kie.executor.retry.count</li>
 *  <li>execution interval - default 3 seconds - use system property org.kie.executor.interval</li>
 * </ul>
 * Additionally executor can be disable to not start at all when system property org.kie.executor.disabled is 
 * set to true
 */
public class ExecutorImpl implements Executor {

    private static final Logger logger = LoggerFactory.getLogger(ExecutorImpl.class);
    
    private ExecutorStoreService executorStoreService;

	private List<ScheduledFuture<?>> handle = new ArrayList<ScheduledFuture<?>>();
    private int threadPoolSize = Integer.parseInt(System.getProperty("org.kie.executor.pool.size", "1"));
    private int retries = Integer.parseInt(System.getProperty("org.kie.executor.retry.count", "3"));
    private int interval = Integer.parseInt(System.getProperty("org.kie.executor.interval", "3"));
    private int initialDelay = Integer.parseInt(System.getProperty("org.kie.executor.initial.delay", "100"));
    private TimeUnit timeunit = TimeUnit.valueOf(System.getProperty("org.kie.executor.timeunit", "SECONDS"));

	private ScheduledExecutorService scheduler;

    public ExecutorImpl() {
    }
    
    public void setExecutorStoreService(ExecutorStoreService executorStoreService) {
		this.executorStoreService = executorStoreService;
	}

    /**
     * {@inheritDoc}
     */
    public int getInterval() {
        return interval;
    }

    /**
     * {@inheritDoc}
     */
    public void setInterval(int interval) {
        this.interval = interval;
    }

    /**
     * {@inheritDoc}
     */
    public int getRetries() {
        return retries;
    }

    /**
     * {@inheritDoc}
     */
    public void setRetries(int retries) {
        this.retries = retries;
    }

    /**
     * {@inheritDoc}
     */
    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    /**
     * {@inheritDoc}
     */
    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }
    
    /**
     * {@inheritDoc}
     */
    public TimeUnit getTimeunit() {
		return timeunit;
	}

    /**
     * {@inheritDoc}
     */
	public void setTimeunit(TimeUnit timeunit) {
		this.timeunit = timeunit;
	}

    /**
     * {@inheritDoc}
     */
    public void init() {
        if (!"true".equalsIgnoreCase(System.getProperty("org.kie.executor.disabled"))) {
            logger.info("Starting Executor Component ...\n" + " \t - Thread Pool Size: {}" + "\n"
                    + " \t - Interval: {} {} \n" + " \t - Retries per Request: {}\n",
                    threadPoolSize, interval, timeunit.toString(), retries);
            
            int delayIncremental = 0;
            
            scheduler = Executors.newScheduledThreadPool(threadPoolSize);
            for (int i = 0; i < threadPoolSize; i++) {
                long delay = 2000 + delayIncremental;
                long interval = TimeUnit.MILLISECONDS.convert(this.interval, timeunit);
                logger.debug("Starting executor thread with initial delay {} interval {} and time unit {}", delay, interval, TimeUnit.MILLISECONDS);
                handle.add(scheduler.scheduleAtFixedRate(executorStoreService.buildExecutorRunnable(), delay, interval, TimeUnit.MILLISECONDS));
                               
                delayIncremental += this.initialDelay;
                
            }
        } else {
        	throw new ExecutorNotStartedException();
        }
    }
    
    public void init(ThreadFactory threadFactory) {
        if (!"true".equalsIgnoreCase(System.getProperty("org.kie.executor.disabled"))) {
            logger.info("Starting Executor Component ...\n" + " \t - Thread Pool Size: {}" + "\n"
                    + " \t - Interval: {}" + " Seconds\n" + " \t - Retries per Request: {}\n",
                    threadPoolSize, interval, retries);
            
            int delayIncremental = 0;
            
            scheduler = Executors.newScheduledThreadPool(threadPoolSize, threadFactory);
            for (int i = 0; i < threadPoolSize; i++) {
                
                long delay = 2000 + delayIncremental;
                long interval = TimeUnit.MILLISECONDS.convert(this.interval, timeunit);
                logger.debug("Starting executor thread with initial delay {} interval {} and time unit {}", delay, interval, TimeUnit.MILLISECONDS);
                handle.add(scheduler.scheduleAtFixedRate(executorStoreService.buildExecutorRunnable(), delay, interval, TimeUnit.MILLISECONDS));
                
                delayIncremental += this.initialDelay;
            }
        } else {
        	throw new ExecutorNotStartedException();
        }
    }
    
    /**
     * {@inheritDoc}
     */
    public void destroy() {
        logger.info(" >>>>> Destroying Executor !!!");
        if (handle != null) {
        	for (ScheduledFuture<?> h : handle) {
        		h.cancel(true);
        	}
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long scheduleRequest(String commandId, CommandContext ctx) {
        return scheduleRequest(commandId, new Date(), ctx);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public Long scheduleRequest(String commandId, Date date, CommandContext ctx) {

        if (ctx == null) {
            throw new IllegalStateException("A Context Must Be Provided! ");
        }
        String businessKey = (String) ctx.getData("businessKey");
        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setCommandName(commandId);
        requestInfo.setKey(businessKey);
        requestInfo.setStatus(STATUS.QUEUED);
        requestInfo.setTime(date);
        requestInfo.setMessage("Ready to execute");
        requestInfo.setDeploymentId((String)ctx.getData("deploymentId"));
        requestInfo.setOwner((String)ctx.getData("owner"));
        if (ctx.getData("retries") != null) {
            requestInfo.setRetries(Integer.valueOf(String.valueOf(ctx.getData("retries"))));
        } else {
            requestInfo.setRetries(retries);
        }
        
        if (ctx.getData("retryDelay") != null) {
            List<Long> retryDelay = new ArrayList<Long>();
            String[] timeExpressions = ((String) ctx.getData("retryDelay")).split(",");
            
            for (String timeExpr : timeExpressions) {
                retryDelay.add(TimeUtils.parseTimeString(timeExpr));
            }
            ctx.setData("retryDelay", retryDelay);
        }
        
        if (ctx != null) {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                ObjectOutputStream oout = new ObjectOutputStream(bout);
                oout.writeObject(ctx);
                requestInfo.setRequestData(bout.toByteArray());
            } catch (IOException e) {
                logger.warn("Error serializing context data", e);
                requestInfo.setRequestData(null);
            }
        }
        
        executorStoreService.persistRequest(requestInfo);

        logger.debug("Scheduling request for Command: {} - requestId: {} with {} retries", commandId, requestInfo.getId(), requestInfo.getRetries());
        return requestInfo.getId();
    }

    /**
     * {@inheritDoc}
     */
    public void cancelRequest(Long requestId) {
        logger.debug("Before - Cancelling Request with Id: {}", requestId);

        executorStoreService.removeRequest(requestId);

        logger.debug("After - Cancelling Request with Id: {}", requestId);
    }

    


}
