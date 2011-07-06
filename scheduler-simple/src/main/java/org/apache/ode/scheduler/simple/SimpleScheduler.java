/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ode.scheduler.simple;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.helpers.AbsoluteTimeDateFormat;
import org.apache.ode.bpel.iapi.ClusterAware;
import org.apache.ode.bpel.iapi.ContextException;
import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.dao.scheduler.DatabaseException;
import org.apache.ode.dao.scheduler.JobDAO;
import org.apache.ode.dao.scheduler.SchedulerDAOConnection;
import org.apache.ode.dao.scheduler.SchedulerDAOConnectionFactory;
import org.apache.ode.dao.scheduler.Task;

/**
 * A reliable and relatively simple scheduler that uses a database to persist information about
 * scheduled tasks.
 *
 * The challenge is to achieve high performance in a small memory footprint without loss of reliability
 * while supporting distributed/clustered configurations.
 *
 * The design is based around three time horizons: "immediate", "near future", and "everything else".
 * Immediate jobs (i.e. jobs that are about to be up) are written to the database and kept in
 * an in-memory priority queue. When they execute, they are removed from the database. Near future
 * jobs are placed in the database and assigned to the current node, however they are not stored in
 * memory. Periodically jobs are "upgraded" from near-future to immediate status, at which point they
 * get loaded into memory. Jobs that are further out in time, are placed in the database without a
 * node identifer; when they are ready to be "upgraded" to near-future jobs they are assigned to one
 * of the known live nodes. Recovery is rather straighforward, with stale node identifiers being
 * reassigned to known good nodes.
 *
 * @author Maciej Szefler ( m s z e f l e r @ g m a i l . c o m )
 *
 */
public class SimpleScheduler implements Scheduler, TaskRunner, ClusterAware {
    private static final Log __log = LogFactory.getLog(SimpleScheduler.class);

    /**
     * Jobs scheduled with a time that is between [now, now+immediateInterval] will be assigned to the current node, and placed
     * directly on the todo queue.
     */
    long _immediateInterval = 30000;

    /**
     * Jobs scheduled with a time that is between (now+immediateInterval,now+nearFutureInterval) will be assigned to the current
     * node, but will not be placed on the todo queue (the promoter will pick them up).
     */
    long _nearFutureInterval = 10 * 60 * 1000;

    /** 10s of no communication and you are deemed dead. */
    long _staleInterval = 10000;

    /** Duration used to log a warning if a job scheduled at a date D is queued at D'>D+_warningDelay */
    long _warningDelay = 5*60*1000;

    /**
     * Estimated sustained transaction per second capacity of the system.
     * e.g. 100 means the system can process 100 jobs per seconds, on average
     * This number is used to determine how many jobs to load from the database at once.
     */
    int _tps = 100;

    TransactionManager _txm;

    ExecutorService _exec;

    String _nodeId;

    /** Maximum number of jobs in the "near future" / todo queue. */
    int _todoLimit = 10000;

    /** The object that actually handles the jobs. */
    volatile JobProcessor _jobProcessor;

    volatile JobProcessor _polledRunnableProcessor;

    private SchedulerThread _todo;

    private SchedulerDAOConnectionFactory _dbcf;

    /** Set of outstanding jobs, i.e., jobs that have been enqueued but not dequeued or dispatched yet.
        Used to avoid cases where a job would be dispatched twice if the server is under high load and
        does not fully process a job before it is reloaded from the database. */
    private ConcurrentHashMap<String, Long> _outstandingJobs = new ConcurrentHashMap<String, Long>();
    /** Set of Jobs processed since the last LoadImmediate task.
        This prevents a race condition where a job is processed twice. This could happen if a LoadImediate tasks loads a job
        from the db before the job is processed but puts it in the _outstandingJobs map after the job was processed .
        In such a case the job is no longer in the _outstandingJobs map, and so it's queued again. */
    private ConcurrentHashMap<String, Long> _processedSinceLastLoadTask = new ConcurrentHashMap<String, Long>();

    /**
     * Set of jobs that needed to be retried.
     */
    private ConcurrentHashMap<String, Boolean> _retryJobList = new ConcurrentHashMap<String, Boolean>();

    private boolean _running;

    /** Time for next upgrade. */
    private AtomicLong _nextUpgrade = new AtomicLong();

    private Random _random = new Random();

    private long _pollIntervalForPolledRunnable = Long.getLong("org.apache.ode.polledRunnable.pollInterval", 10 * 60 * 1000);

    /** Number of immediate retries when the transaction fails **/
    private int _immediateTransactionRetryLimit = 3;

    /** Interval between immediate retries when the transaction fails **/
    private long _immediateTransactionRetryInterval = 1000;

    private List<String> _defaultNodeList = new ArrayList<String>();
    
    private List<String> _nodeList = new ArrayList<String>();

    public SimpleScheduler(String nodeId, SchedulerDAOConnectionFactory dbcf, TransactionManager txm, Properties conf) {
        _nodeId = nodeId;
        _dbcf = dbcf;
        _txm = txm;
        
        _todoLimit = getIntProperty(conf, "ode.scheduler.queueLength", _todoLimit);
        _immediateInterval = getLongProperty(conf, "ode.scheduler.immediateInterval", _immediateInterval);
        _nearFutureInterval = getLongProperty(conf, "ode.scheduler.nearFutureInterval", _nearFutureInterval);
        _staleInterval = getLongProperty(conf, "ode.scheduler.staleInterval", _staleInterval);
        _tps = getIntProperty(conf, "ode.scheduler.transactionsPerSecond", _tps);
        _warningDelay =  getLongProperty(conf, "ode.scheduler.warningDelay", _warningDelay);

        _immediateTransactionRetryLimit = getIntProperty(conf, "ode.scheduler.immediateTransactionRetryLimit", _immediateTransactionRetryLimit);
        _immediateTransactionRetryInterval = getLongProperty(conf, "ode.scheduler.immediateTransactionRetryInterval", _immediateTransactionRetryInterval);

        _todo = new  SchedulerThread(this);
        
        _defaultNodeList.add(nodeId);
    }

    public void setPollIntervalForPolledRunnable(long pollIntervalForPolledRunnable) {
        _pollIntervalForPolledRunnable = pollIntervalForPolledRunnable;
    }

    private int getIntProperty(Properties props, String propName, int defaultValue) {
        String s = props.getProperty(propName);
        if (s != null) return Integer.parseInt(s);
        else return defaultValue;
    }

    private long getLongProperty(Properties props, String propName, long defaultValue) {
        String s = props.getProperty(propName);
        if (s != null) return Long.parseLong(s);
        else return defaultValue;
    }

    public void setNodeId(String nodeId) {
        _nodeId = nodeId;
    }

    public void setStaleInterval(long staleInterval) {
        _staleInterval = staleInterval;
    }

    public void setImmediateInterval(long immediateInterval) {
        _immediateInterval = immediateInterval;
    }

    public void setNearFutureInterval(long nearFutureInterval) {
        _nearFutureInterval = nearFutureInterval;
    }

    public void setTransactionsPerSecond(int tps) {
        _tps = tps;
    }

    public void setTransactionManager(TransactionManager txm) {
        _txm = txm;
    }

    public void setSchedulerDAOConnectionFactory(SchedulerDAOConnectionFactory dbcf) {
		_dbcf = dbcf;
	}

	public void setExecutorService(ExecutorService executorService) {
        _exec = executorService;
    }

    public void setPolledRunnableProcesser(JobProcessor polledRunnableProcessor) {
        _polledRunnableProcessor = polledRunnableProcessor;
    }

    public void cancelJob(String jobId) throws ContextException {
        _todo.dequeue(new JobDAOTask(jobId));
        _outstandingJobs.remove(jobId);
        SchedulerDAOConnection conn = _dbcf.getConnection();
        try {
            conn.deleteJob(jobId, _nodeId);
        } catch (DatabaseException e) {
            __log.debug("Job removal failed.", e);
            throw new ContextException("Job removal failed.", e);
        }
    }

    public <T> Future<T> execIsolatedTransaction(final Callable<T> transaction) throws Exception, ContextException {
        return _exec.submit(new Callable<T>() {
            public T call() throws Exception {
                try {
                    return execTransaction(transaction);
                } catch (Exception e) {
                    __log.error("An exception occured while executing an isolated transaction, " +
                            "the transaction is going to be abandoned.", e);
                    return null;
                }
            }
        });
    }

    public <T> T execTransaction(Callable<T> transaction) throws Exception, ContextException {
        return execTransaction(transaction, 0);
    }

    public <T> T execTransaction(Callable<T> transaction, int timeout) throws Exception, ContextException {
        if( _txm == null ) {
            throw new ContextException("Cannot locate the transaction manager; the server might be shutting down.");
        }

        // The value of the timeout is in seconds. If the value is zero, the transaction service restores the default value.
        if (timeout < 0) {
           throw new IllegalArgumentException("Timeout must be positive, received: "+timeout);
        }
        
        boolean existingTransaction = false;
        try {
            existingTransaction = ( _txm.getTransaction() != null );
        } catch (Exception ex) {
            String errmsg = "Internal Error, could not get current transaction.";
            throw new ContextException(errmsg, ex);
        }

        // already in transaction, execute and return directly
        if (existingTransaction) {
            return transaction.call();
        }

        // run in new transaction
        Exception ex = null;
        int immediateRetryCount = _immediateTransactionRetryLimit;
        _txm.setTransactionTimeout(timeout);
        if(__log.isDebugEnabled() && timeout!=0) __log.debug("Custom transaction timeout: "+timeout);
        try {
            do {
                try {
                    if (__log.isDebugEnabled()) __log.debug("Beginning a new transaction");
                    _txm.begin();
                } catch (Exception e) {
                    String errmsg = "Internal Error, could not begin transaction.";
                    throw new ContextException(errmsg, e);
                }
    
                try {
                    ex = null;
                    return transaction.call();
                } catch (Exception e) {
                    ex = e;
                } finally {
                    if (ex == null) {
                        if (__log.isDebugEnabled()) {
                        	__log.debug("Commiting on " + _txm + "...");
                        }
                        try {
                            _txm.commit();
                            if (__log.isDebugEnabled()) {
                            	__log.debug("committed on " + _txm + " successfully.");
                            }
                        } catch( Exception e2 ) {
                            ex = e2;
                            __log.error("error in commiting transaction", e2);
                        }
                    } else {
                        if (__log.isDebugEnabled()) {
                        	__log.debug("Rollbacking on " + _txm + "...");
                        }
                        _txm.rollback();
                    }
                    
                    if( ex != null && immediateRetryCount > 0 ) {
                        if (__log.isDebugEnabled())  __log.debug("Will retry the transaction in " + _immediateTransactionRetryInterval + " msecs on " + _txm + " for error: ", ex);
                        Thread.sleep(_immediateTransactionRetryInterval);
                    }
                }
            } while( immediateRetryCount-- > 0 );
        } finally {
            // 0 restores the default value
        	if (_txm != null) {
        		_txm.setTransactionTimeout(0);
        	}
        }
        
        throw ex;
    }

    public void setRollbackOnly() throws Exception {
        TransactionManager txm = _txm;
        if( txm == null ) {
            throw new ContextException("Cannot locate the transaction manager; the server might be shutting down.");
        }
        
        txm.setRollbackOnly();
    }

    public void registerSynchronizer(final Synchronizer synch) throws ContextException {
        TransactionManager txm = _txm;
        if( txm == null ) {
            throw new ContextException("Cannot locate the transaction manager; the server might be shutting down.");
        }
        
        try {
            txm.getTransaction().registerSynchronization(new Synchronization() {

                public void beforeCompletion() {
                    synch.beforeCompletion();
                }

                public void afterCompletion(int status) {
                    synch.afterCompletion(status == Status.STATUS_COMMITTED);
                }

            });
        } catch (Exception e) {
            throw new ContextException("Unable to register synchronizer.", e);
        }
    }

    public String schedulePersistedJob(final JobDetails jobDetail, Date when) throws ContextException {
        long ctime = System.currentTimeMillis();
        if (when == null)
            when = new Date(ctime);

        if (__log.isDebugEnabled())
            __log.debug("scheduling " + jobDetail + " for " + when);

        return schedulePersistedJob(jobDetail, true, when, ctime);
    }

    public String scheduleMapSerializableRunnable(MapSerializableRunnable runnable, Date when) throws ContextException {
        long ctime = System.currentTimeMillis();
        if (when == null)
            when = new Date(ctime);

        JobDetails jobDetails = new JobDetails();
        jobDetails.getDetailsExt().put("runnable", runnable);
        runnable.storeToDetails(jobDetails);
        
        if (__log.isDebugEnabled())
            __log.debug("scheduling " + jobDetails + " for " + when);
        
        return schedulePersistedJob(jobDetails, true, when, ctime);
    }

    private String schedulePersistedJob(JobDetails jobDetails, boolean transacted, Date when, long ctime) throws ContextException {
        boolean immediate = when.getTime() <= ctime + _immediateInterval;
        boolean nearfuture = !immediate && ( when.getTime() <= ctime + _nearFutureInterval );
        JobDAO job;
        try {
            if (immediate) {
            	
                // If we have too many jobs in the queue, we don't allow any new ones
                if (_outstandingJobs.size() > _todoLimit) {
                  __log.error("The execution queue is backed up, the engine can't keep up with the load. Either "
                          + "increase the queue size or regulate the flow.");
                  return null;
                }
                
                job = insertJob(transacted, jobDetails, when.getTime(), _nodeId, true, true);
                __log.debug("scheduled immediate job: " + job.getJobId());
            } else if (nearfuture) {
                // Near future, assign the job to ourselves (why? -- this makes it very unlikely that we
                // would get two nodes trying to process the same instance, which causes unsightly rollbacks).
                job = insertJob(transacted, jobDetails, when.getTime(), _nodeId, false, false);
                __log.debug("scheduled near-future job: " + job.getJobId());
            } else /* far future */ {
                // Not the near future, we don't assign a node-id, we'll assign it later.
                job = insertJob(transacted, jobDetails, when.getTime(), null, false, false);
                __log.debug("scheduled far-future job: " + job.getJobId());
            }
        } catch (DatabaseException dbe) {
            __log.error("Database error.", dbe);
            throw new ContextException("Database error.", dbe);
        }
        return job.getJobId();
    }
    
    private JobDAO insertJob(final boolean transacted, final JobDetails jobDetails, final long scheduledDate, final String nodeID,
    							final boolean loaded, final boolean enqueue) throws ContextException, DatabaseException {
        SchedulerDAOConnection conn = _dbcf.getConnection();
        final JobDAO job = conn.createJob(transacted, jobDetails, true, scheduledDate);
        if (!conn.insertJob(job, nodeID, loaded)) {
            String msg = String.format("Database insert failed. jobId %s nodeId %s", job.getJobId(), nodeID);
            __log.error(msg);
            throw new ContextException(msg);
        }
        if (enqueue) {
            addTodoOnCommit(job);
        }
        return job;
    }

    public String scheduleVolatileJob(boolean transacted, JobDetails jobDetail) throws ContextException {
        return scheduleVolatileJob(transacted, jobDetail, null);
    }

    public String scheduleVolatileJob(boolean transacted, JobDetails jobDetail, Date when) throws ContextException {
        long ctime = System.currentTimeMillis();
        if (when == null) {
            when = new Date(ctime);
        }
        SchedulerDAOConnection conn = _dbcf.getConnection();
        JobDAO job = conn.createJob(transacted, jobDetail, false, when.getTime());
        addTodoOnCommit(job);
        return job.toString();
    }

    public void setJobProcessor(JobProcessor processor) throws ContextException {
        _jobProcessor = processor;
    }

    public List<String> getNodeList() {
    	if (this._nodeList == null || this._nodeList.size() == 0) {
    		return _defaultNodeList;
    	}
		return _nodeList;
	}

	public void setNodeList(List<String> nodeList) {
		this._nodeList = nodeList;
	}

	public void shutdown() {
        stop();
        _jobProcessor = null;
        _txm = null;
        _todo = null;
    }

    public synchronized void start() {
        if (_running)
            return;

        if (_exec == null) {
            _exec = Executors.newCachedThreadPool();
        }
        
        _todo.clearTasks(UpgradeJobsTask.class);
        _todo.clearTasks(LoadImmediateTask.class);
        
        _processedSinceLastLoadTask.clear();
        _outstandingJobs.clear();
        _retryJobList.clear();

        long now = System.currentTimeMillis();

        // schedule immediate job loading for now!
        _todo.enqueue(new LoadImmediateTask(now));

        // do the upgrade sometime (random) in the immediate interval.
        _todo.enqueue(new UpgradeJobsTask(now + randomMean(_immediateInterval)));

        _todo.start();
        _running = true;
    }

    private long randomMean(long mean) {
        return (long) _random.nextDouble() * mean + (mean/2);
    }

    public synchronized void stop() {
        if (!_running)
            return;

        _todo.stop();
        _todo.clearTasks(UpgradeJobsTask.class);
        _todo.clearTasks(LoadImmediateTask.class);

        _processedSinceLastLoadTask.clear();
        _outstandingJobs.clear();
        _retryJobList.clear();

        // disable because this is not the right way to do it
        // will be fixed by ODE-595
        // graceful shutdown; any new submits will throw RejectedExecutionExceptions
//        _exec.shutdown();
        _running = false;
    }
    
    
    /**
     * This is the class for delegating job to jobProcessor, also introduced retry mechanism here.
     * @author jeffyu
     *
     */
    private class RunJobCallable implements Callable<Void> {
        final JobProcessor processor;
        final JobDAO job;

        RunJobCallable(JobDAO jobDao, JobProcessor processor) {
            this.job = jobDao;
            this.processor = processor;
        }

        public Void call() throws Exception {
            try {
                final Scheduler.JobInfo jobInfo = new Scheduler.JobInfo(job.getJobId(), job.getDetails(), job.getDetails().getRetryCount());
                if (job.isTransacted()) {
                    processInTransactionContext(jobInfo);
                } else {
                    processor.onScheduledJob(jobInfo);
                }
                return null;
            } finally {
                // the order of these 2 actions is crucial to avoid a race condition.
                // if the transaction failed, and has retry mechanism, we will not put it to avoid being ignore.
                if (_retryJobList.get(job.getJobId()) == null ) {
                    _processedSinceLastLoadTask.put(job.getJobId(), job.getScheduledDate());
                } else {
                    _retryJobList.remove(job.getJobId());
                }
                _outstandingJobs.remove(job.getJobId());
            }
        }

		private void processInTransactionContext(final Scheduler.JobInfo jobInfo) throws Exception {
			final boolean[] needRetry = new boolean[]{true};
			try {
			    execTransaction(new Callable<Void>() {
			        public Void call() throws ContextException, Exception  {
			        	SchedulerDAOConnection conn = _dbcf.getConnection();
			            if (job.isPersisted()) {
			                if (!conn.deleteJob(job.getJobId(), _nodeId)) {
			                    throw new JobNoLongerInDbException(job.getJobId(), _nodeId);
			                }
			            }

			            try {
			                processor.onScheduledJob(jobInfo);
			                // If the job is a "runnable" job, schedule the next job occurence
			                if (job.getDetails().getDetailsExt().get("runnable") != null &&
			                		!"COMPLETED".equals(String.valueOf(jobInfo.jobDetail.getDetailsExt().get("runnable_status")))) {
			                    // the runnable is still in progress, schedule checker to 10 mins later
			                    if (_pollIntervalForPolledRunnable < 0) {
			                        if (__log.isWarnEnabled())
			                            __log.warn("The poll interval for polled runnables is negative; setting it to 1000ms");
			                        _pollIntervalForPolledRunnable = 1000;
			                    }
			                    long schedDate = System.currentTimeMillis() + _pollIntervalForPolledRunnable;
			                    job.setScheduledDate(schedDate);
			                    conn.insertJob(job, _nodeId, false);
			                }
			            } catch (JobProcessorException jpe) {
			                if (!jpe.retry) {
			                    needRetry[0] = false;
			                }
			                // Let execTransaction know that shit happened.
			                throw jpe;
			            }
			            return null;
			        }
			    });
			} catch (JobNoLongerInDbException jde) {
			    // This may happen if two node try to do the same job... we try to avoid
			    // it the synchronization is a best-effort but not perfect.
			    __log.debug("job no longer in db forced rollback: "+job);
			} catch (final Exception ex) {
			    __log.error("Error while processing a "+(job.isPersisted()?"":"non-")+"persisted job"+(needRetry[0] && job.isPersisted()?": ":", no retry: ")+job, ex);

			    // We only get here if the above execTransaction fails, so that transaction got
			    // rollbacked already
			    if (job.isPersisted()) {
			    	try {
				        execTransaction(new Callable<Void>() {
				            public Void call() throws Exception {
				                retryJob(needRetry);
				                return null;
				            }
				        });
			    	} catch (Exception e) {
			    		e.printStackTrace();
			    	}
			    }
			}
		}
		
		private void retryJob(final boolean[] needRetry) throws DatabaseException {
			SchedulerDAOConnection conn = _dbcf.getConnection();
			int retry = job.getDetails().getRetryCount() + 1;
			if (!needRetry[0] || retry > 10) {
				conn.deleteJob(job.getJobId(), _nodeId);
				if (retry > 10) {
					__log.error("Error while processing job after 10 retries, no more retries:" + job);
				}
			} else {
                job.getDetails().setRetryCount(retry);
                long delay = (long)(Math.pow(5, retry));
                long scheddate = System.currentTimeMillis() + delay*1000;
                job.setScheduled(false);
                job.setScheduledDate(scheddate);
                conn.updateJob(job);

                _retryJobList.put(job.getJobId(), new Boolean(true));

                __log.error("Error while processing job, retrying in " + delay + "s, the job is " + job);
			}
		}
		

		
    }
    
    /**
     * Run a job in the current thread.
     **/
    protected void runJob(final JobDAO jobDao) {
        _exec.submit(new RunJobCallable(jobDao, _jobProcessor));
    }

     /**
     * Run a job from a polled runnable thread. The runnable is not persistent,
     * however, the poller is persistent and wakes up every given interval to
     * check the status of the runnable.
     * <ul>
     * <li>1. The runnable is being scheduled; the poller persistent job dispatches
     * the runnable to a runnable delegate thread and schedules itself to a later time.</li>
     * <li>2. The runnable is running; the poller job re-schedules itself every time it
     * sees the runnable is not completed.</li>
     * <li>3. The runnable failed; the poller job passes the exception thrown on the runnable
     * down, and the standard scheduler retries happen.</li>
     * <li>4. The runnable completes; the poller persistent does not re-schedule itself.</li>
     * <li>5. System powered off and restarts; the poller job does not know what the status
     * of the runnable. This is handled just like the case #1.</li>
     * </ul>
     * <p/>
     * There is at least one re-scheduling of the poller job. Since, the runnable's state is
     * not persisted, and the same runnable may be tried again after system failure,
     * the runnable that's used with this polling should be repeatable.
     *
     */
    protected void runPolledRunnable(final JobDAO jobDao) {
         _exec.submit(new RunJobCallable(jobDao, _polledRunnableProcessor));
    }

    private void addTodoOnCommit(final JobDAO job) {
        registerSynchronizer(new Synchronizer() {
            public void afterCompletion(boolean success) {
                if (success) {
                    enqueue(job);
                }
            }

            public void beforeCompletion() {
            }
        });
    }

    public boolean isTransacted() {
        TransactionManager txm = _txm;
        if( txm == null ) {
            throw new ContextException("Cannot locate the transaction manager; the server might be shutting down.");
        }
        
        try {
            Transaction tx = txm.getTransaction();
            return (tx != null && tx.getStatus() != Status.STATUS_NO_TRANSACTION);
        } catch (SystemException e) {
            throw new ContextException("Internal Error: Could not obtain transaction status.");
        }
    }

    public void runTask(final Task task) {
        if (task instanceof JobDAOTask) {
            JobDAOTask job = (JobDAOTask)task;
            if( job.getJobDAO().getDetails().getDetailsExt().get("runnable") != null ) {
                runPolledRunnable(job.getJobDAO());
            } else {
                runJob(job.getJobDAO());
            }
        } else if (task instanceof SchedulerTask) {
            _exec.submit(new Callable<Void>() {
                public Void call() throws Exception {
                    try {
                        ((SchedulerTask) task).run();
                    } catch (Exception ex) {
                        __log.error("Error during SchedulerTask execution", ex);
                    }
                    return null;
                }
            });
        }
    }

    boolean doLoadImmediate() {
        __log.debug("LOAD IMMEDIATE started");

        // don't load anything if we're already half-full;  we've got plenty to do already
        if (_outstandingJobs.size() > _todoLimit/2) {
        	return true;
        }
        
        List<JobDAO> jobs;
        try {
            // don't load more than we can chew
            final int batch = Math.min((int) (_immediateInterval * _tps / 1000), _todoLimit-_outstandingJobs.size());

            // jobs might have been enqueued by #addTodoOnCommit meanwhile
            if (batch<=0) {
                if (__log.isDebugEnabled()) __log.debug("Max capacity reached: "+_outstandingJobs.size()+" jobs dispacthed i.e. queued or being executed");
                return true;
            }

            if (__log.isDebugEnabled()) __log.debug("loading "+batch+" jobs from db");
            
            jobs = execTransaction(new Callable<List<JobDAO>>() {
                public List<JobDAO> call() throws ContextException, DatabaseException {
                	SchedulerDAOConnection conn = _dbcf.getConnection();
                    return conn.dequeueImmediate(_nodeId, System.currentTimeMillis() + _immediateInterval, batch);
                }
            });
            
            if (__log.isDebugEnabled()) __log.debug("loaded "+jobs.size()+" jobs from db");

            long delayedTime = System.currentTimeMillis() - _warningDelay;
            int delayedCount = 0;
            boolean runningLate;
            AbsoluteTimeDateFormat f = new AbsoluteTimeDateFormat();
            for (JobDAO j : jobs) {
                // jobs might have been enqueued by #addTodoOnCommit meanwhile
                if (_outstandingJobs.size() >= _todoLimit){
                    if (__log.isDebugEnabled()) __log.debug("Max capacity reached: "+_outstandingJobs.size()+" jobs dispacthed i.e. queued or being executed");
                    break;
                }
                runningLate = (j.getScheduledDate() <= delayedTime);
                if (runningLate) {
                    delayedCount++;
                }
                if (__log.isDebugEnabled())
                    __log.debug("todo.enqueue job from db: " + j.getJobId().trim() + " for " + j.getScheduledDate() + "(" + f.format(j.getScheduledDate())+") "+(runningLate?" delayed=true":""));
                enqueue(j);
            }
            if (delayedCount > 0) {
                __log.warn("Dispatching jobs with more than "+(_warningDelay/60000)+" minutes delay. Either the server was down for some time or the job load is greater than available capacity");
            }

            // clear only if the batch succeeded
            _processedSinceLastLoadTask.clear();
            _retryJobList.clear();
            return true;
        } catch (Exception ex) {
            __log.error("Error loading immediate jobs from database.", ex);
            return false;
        } finally {
            __log.debug("LOAD IMMEDIATE complete");
        }
    }

    /**
     * Put job into _outstandingJobs for immediate execution.
     * 
     * @param job
     */
    private void enqueue(JobDAO job) {
        if (_processedSinceLastLoadTask.get(job.getJobId()) == null) {
            if (_outstandingJobs.putIfAbsent(job.getJobId(), job.getScheduledDate()) == null) {
                if (job.getScheduledDate() <= System.currentTimeMillis()) {
                    runJob(job);
                } else {
                    _todo.enqueue(new JobDAOTask(job));
                }
            } else {
              if (__log.isDebugEnabled()) __log.debug("Job "+job.getJobId()+" is being processed (outstanding job)");
            }
        } else {
            if (__log.isDebugEnabled()) __log.debug("Job "+job.getJobId()+" is being processed (processed since last load)");
        }
    }

    boolean doUpgrade() {
        __log.debug("UPGRADE started");

        // We're going to try to upgrade near future jobs using the db only.
        // We assume that the distribution of the trailing digits in the
        // scheduled time are uniformly distributed, and use modular division
        // of the time by the number of nodes to create the node assignment.
        // This can be done in a single update statement.
        final long maxtime = System.currentTimeMillis() + _nearFutureInterval;
        try {
            return execTransaction(new Callable<Boolean>() {

                public Boolean call() throws ContextException, DatabaseException {
                	SchedulerDAOConnection conn = _dbcf.getConnection();
                    int numNodes = getNodeList().size();
                    for (int i = 0; i < numNodes; ++i) {
                        String node = getNodeList().get(i);
                        conn.updateAssignToNode(node, i, numNodes, maxtime);
                    }
                    return true;
                }

            });

        } catch (Exception ex) {
            __log.error("Database error upgrading jobs.", ex);
            return false;
        } finally {
            __log.debug("UPGRADE complete");
        }

    }


    private abstract class SchedulerTask extends Task implements Runnable {
        SchedulerTask(long schedDate) {
            super(schedDate);
        }
    }

    private class LoadImmediateTask extends SchedulerTask {
        LoadImmediateTask(long schedDate) {
            super(schedDate);
        }

        public void run() {
            boolean success = false;
            try {
                success = doLoadImmediate();
            } finally {
                if (success) {
                    _todo.enqueue(new LoadImmediateTask(System.currentTimeMillis() + (long) (_immediateInterval * .90)));
                } else {
                    _todo.enqueue(new LoadImmediateTask(System.currentTimeMillis() + 1000));
                }
            }

        }
    }

    /**
     * Upgrade jobs from far future to immediate future (basically, assign them to a node).
     * @author mszefler
     *
     */
    private class UpgradeJobsTask extends SchedulerTask {
        UpgradeJobsTask(long schedDate) {
            super(schedDate);
        }

        public void run() {
            long ctime = System.currentTimeMillis();
            long ntime = _nextUpgrade.get();
            __log.debug("UPGRADE task for " + getScheduledDate() + " fired at " + ctime);

            // We could be too early, this can happen if upgrade gets delayed due to another
            // node
            if (_nextUpgrade.get() > System.currentTimeMillis()) {
                __log.debug("UPGRADE skipped -- wait another " + (ntime - ctime) + "ms");
                _todo.enqueue(new UpgradeJobsTask(ntime));
                return;
            }

            boolean success = false;
            try {
                success = doUpgrade();
            } finally {
                long future = System.currentTimeMillis() + (success ? (long) (_nearFutureInterval * .50) : 1000);
                _nextUpgrade.set(future);
                _todo.enqueue(new UpgradeJobsTask(future));
                __log.debug("UPGRADE completed, success = " + success + "; next time in " + (future - ctime) + "ms");
            }
        }
    }
    
    /**
     * Right now, just assume all of nodes are coordinator for now.
     * 
     */
	public boolean amICoordinator() {
		return true;
	}
    
}

    