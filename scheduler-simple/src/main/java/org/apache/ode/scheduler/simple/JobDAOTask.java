/**
 * 
 */
package org.apache.ode.scheduler.simple;

import org.apache.ode.dao.scheduler.JobDAO;
import org.apache.ode.dao.scheduler.Task;

/**
 * @author jeffyu
 *
 */
public class JobDAOTask extends Task {
	
	public JobDAO dao;
	
	public String jobId;

	public JobDAOTask(JobDAO job) {
	    super(job.getScheduledDate());
	    this.dao = job;
	    this.jobId=job.getJobId();
	}
	
    public JobDAOTask(String jobId) {
	    super(0L);
	    this.jobId=jobId;
	 }
    
    @Override
    public boolean equals(Object obj) {
      return obj instanceof JobDAOTask && jobId.equals(((JobDAOTask) obj).jobId);
    }

    @Override
    public int hashCode() {
      return jobId.hashCode();
    }
    
    public JobDAO getJobDAO() {
    	return this.dao;
    }

}
