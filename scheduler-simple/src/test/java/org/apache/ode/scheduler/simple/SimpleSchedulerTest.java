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
import java.util.Properties;

import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.bpel.iapi.Scheduler.JobInfo;
import org.apache.ode.bpel.iapi.Scheduler.JobProcessor;
import org.apache.ode.bpel.iapi.Scheduler.JobProcessorException;

public class SimpleSchedulerTest extends SchedulerTestBase implements JobProcessor {

    SimpleScheduler _scheduler;
    ArrayList<JobInfo> _jobs;
    
    @Override
    public void setUp() throws Exception {
    	super.setUp();
    	
        _scheduler = newScheduler("n1");
        _jobs = new ArrayList<JobInfo>(100);
    }
    
    @Override
    public void tearDown() throws Exception {
        _scheduler.shutdown();
        super.tearDown();
    }

    public void testConcurrentExec() throws Exception  {
        _scheduler.start();
        _txm.begin();
        String jobId;
        try {
            jobId = _scheduler.schedulePersistedJob(newDetail("123"), new Date(System.currentTimeMillis() + 100));
            Thread.sleep(200);
            // Make sure we don't schedule until commit.
            assertEquals(0, _jobs.size());      
        } finally {
            _txm.commit();
        }
        // Wait for the job to be execed.
        Thread.sleep(100);
        // Should execute job,
        assertEquals(1, _jobs.size());
    }
    
    public void testImmediateScheduling() throws Exception {
        _scheduler.start();
        _txm.begin();
        try {
            _scheduler.schedulePersistedJob(newDetail("123"), new Date());
            Thread.sleep(100);
            // we're using transacted jobs which means it will commit at the end
            // if the job is scheduled, the following assert is not valid @seanahn
            // assertEquals(jobs, _jobs.size());        
        } finally {
            _txm.commit();
        }
        Thread.sleep(100);
        assertEquals(1, _jobs.size());
    }

    public void testStartStop() throws Exception {
        _scheduler.start();
        _txm.begin();
        try {
            for (int i = 0; i < 10; ++i)
                _scheduler.schedulePersistedJob(newDetail("123"), new Date(System.currentTimeMillis() + (i * 100)));
        } finally {
            _txm.commit();
        }
        Thread.sleep(100);
        _scheduler.stop();
        int jobs = _jobs.size();
        assertTrue(jobs > 0);
        assertTrue(jobs < 10);
        Thread.sleep(200);
        assertEquals(jobs, _jobs.size());
        _scheduler.start();
        Thread.sleep(1000);
        assertEquals(10, _jobs.size());
    }

    public void testNearFutureScheduling() throws Exception {
        // speed things up a bit to hit the right code paths
        _scheduler.setNearFutureInterval(10000);
        _scheduler.setImmediateInterval(5000);
        _scheduler.start();

        _txm.begin();
        try {
            _scheduler.schedulePersistedJob(newDetail("123"), new Date(System.currentTimeMillis() + 7500));
        } finally {
            _txm.commit();
        }

        Thread.sleep(8500);
        assertEquals(1, _jobs.size());
    }

    public void testFarFutureScheduling() throws Exception {
        // speed things up a bit to hit the right code paths
        _scheduler.setNearFutureInterval(7000);
        _scheduler.setImmediateInterval(3000);
        _scheduler.start();

        _txm.begin();
        try {
            _scheduler.schedulePersistedJob(newDetail("123"), new Date(System.currentTimeMillis() + 7500));
        } finally {
            _txm.commit();
        }

        Thread.sleep(8500);
        assertEquals(1, _jobs.size());
    }

    public void onScheduledJob(final JobInfo jobInfo) throws JobProcessorException {
        synchronized (_jobs) {
            _jobs.add(jobInfo);
        }
    }

    Scheduler.JobDetails newDetail(String x) {
        Scheduler.JobDetails jd = new Scheduler.JobDetails();
        jd.getDetailsExt().put("foo", x);
        return jd;
    }

    private SimpleScheduler newScheduler(String nodeId) {
        SimpleScheduler scheduler = new SimpleScheduler(nodeId, _factory, _txm, new Properties());
        scheduler.setJobProcessor(this);
        return scheduler;
      }
    
}
