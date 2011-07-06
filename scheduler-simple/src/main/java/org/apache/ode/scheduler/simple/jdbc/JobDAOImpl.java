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

package org.apache.ode.scheduler.simple.jdbc;

import java.text.SimpleDateFormat;

import org.apache.ode.bpel.iapi.Scheduler.JobDetails;
import org.apache.ode.dao.scheduler.JobDAO;
import org.apache.ode.utils.GUID;

/**
 * Like a task, but a little bit better.
 * 
 * @author Maciej Szefler ( m s z e f l e r @ g m a i l . c o m )
 */
public class JobDAOImpl implements JobDAO {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

    private String jobId;
    private boolean transacted;
    private JobDetails detail;
    private boolean persisted = true;
    private long scheduledDate;
    private boolean scheduled = false;

    public JobDAOImpl(long when, boolean transacted, JobDetails jobDetail) {
        this(when, new GUID().toString(),transacted,jobDetail);
    }

    public JobDAOImpl(long when, String jobId, boolean transacted, JobDetails jobDetail) {
        this.scheduledDate = when;
        this.jobId = jobId;
        this.detail = jobDetail;
        this.transacted = transacted;
    }

	public JobDetails getDetails() {
		return detail;
	}

	public String getJobId() {
		return jobId;
	}

	public long getScheduledDate() {
		return this.scheduledDate;
	}
	
	public void setScheduledDate(long scheduledDate) {
		this.scheduledDate = scheduledDate;
	}

	public boolean isPersisted() {
		return this.persisted;
	}

	public boolean isTransacted() {
		return this.transacted;
	}

	public void setPersisted(boolean persisted) {
		this.persisted = persisted;
	}

	public boolean isScheduled() {
		return scheduled;
	}

	public void setScheduled(boolean scheduled) {
		this.scheduled = scheduled;
	}


}
