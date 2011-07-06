/**
 * 
 */
package org.apache.ode.dao.jpa.scheduler;

import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.iapi.Scheduler.JobDetails;
import org.apache.ode.dao.jpa.JpaConnection;
import org.apache.ode.dao.jpa.JpaOperator;
import org.apache.ode.dao.scheduler.DatabaseException;
import org.apache.ode.dao.scheduler.JobDAO;
import org.apache.ode.dao.scheduler.SchedulerDAOConnection;
import org.apache.ode.utils.GUID;

/**
 * @author jeffyu
 *
 */
public class SchedulerDAOConnectionImpl extends JpaConnection implements SchedulerDAOConnection {
	
	private static final Log __log = LogFactory.getLog(SchedulerDAOConnectionImpl.class);
	
	static final ThreadLocal<SchedulerDAOConnectionImpl> _connections = new ThreadLocal<SchedulerDAOConnectionImpl>();
	
	private static final int UPDATE_SCHEDULED_SLOTS = 10;

	public SchedulerDAOConnectionImpl(EntityManager mgr, TransactionManager txMgr, JpaOperator operator) {
	    super(mgr, txMgr, operator);
	}

	public static ThreadLocal<SchedulerDAOConnectionImpl> getThreadLocal() {
	    return _connections;
	}

	
	public boolean deleteJob(String jobid, String nodeId) throws DatabaseException {
		_txCtx.begin();		
		Query q = _em.createNamedQuery("deleteJobs");
		q.setParameter("job", jobid);
		q.setParameter("node", nodeId);
		boolean ret = q.executeUpdate() == 1 ? true : false;
		_txCtx.commit();
		return ret;
	}

	
	public List<JobDAO> dequeueImmediate(String nodeId, long maxtime, int maxjobs) throws DatabaseException {
        _txCtx.begin();
        Query q = _em.createNamedQuery("dequeueImmediate");
        q.setParameter("node", nodeId);
        q.setParameter("time", maxtime);
        q.setMaxResults(maxjobs);
        List<JobDAO> ret = (List<JobDAO>) q.getResultList();

        //For compatibility reasons, we check whether there are entries inside
        //jobDetailsExt blob, which correspond to extracted entries. If so, we
        //use them.
        for (JobDAO dao : ret) {

            JobDAOImpl daoImpl = (JobDAOImpl) dao;
            Map<String, Object> detailsExt = daoImpl.getDetails().getDetailsExt();
            if (detailsExt.get("type") != null) {
                daoImpl.setType((String) detailsExt.get("type"));
            }
            if (detailsExt.get("iid") != null) {
                daoImpl.setInstanceId((Long) detailsExt.get("iid"));
            }
            if (detailsExt.get("pid") != null) {
                daoImpl.setProcessId((String) detailsExt.get("pid"));
            }
            if (detailsExt.get("inmem") != null) {
                daoImpl.setInMem((Boolean) detailsExt.get("inmem"));
            }
            if (detailsExt.get("ckey") != null) {
                daoImpl.setCorrelationKeySet((String) detailsExt.get("ckey"));
            }
            if (detailsExt.get("channel") != null) {
                daoImpl.setChannel((String) detailsExt.get("channel"));
            }
            if (detailsExt.get("mexid") != null) {
                daoImpl.setMexId((String) detailsExt.get("mexid"));
            }
            if (detailsExt.get("correlatorId") != null) {
                daoImpl.setCorrelatorId((String) detailsExt.get("correlatorId"));
            }
            if (detailsExt.get("retryCount") != null) {
                daoImpl.setRetryCount(Integer.parseInt((String) detailsExt.get("retryCount")));
            }
        }

        // mark jobs as scheduled, UPDATE_SCHEDULED_SLOTS at a time
        int j = 0;
        int updateCount = 0;
        q = _em.createNamedQuery("updateScheduled");

        for (int updates = 1; updates <= (ret.size() / UPDATE_SCHEDULED_SLOTS) + 1; updates++) {
            for (int i = 1; i <= UPDATE_SCHEDULED_SLOTS; i++) {
                q.setParameter(i, j < ret.size() ? ret.get(j).getJobId() : "");
                j++;
            }
            updateCount += q.executeUpdate();
        }
        if (updateCount != ret.size()) {
            __log.error("Updating scheduled jobs failed to update all jobs; expected=" + ret.size()
                    + " actual=" + updateCount);
            return null;

        }
        _txCtx.commit();
        return ret;

	}

	
	public List<String> getNodeIds() throws DatabaseException {
		_txCtx.begin();
		Query q = _em.createNamedQuery("nodeIds");
		List<String> ret = (List<String>) q.getResultList();
		_txCtx.commit();
		return ret;
	}

	
	public boolean insertJob(JobDAO job, String nodeId, boolean loaded) throws DatabaseException {
		_txCtx.begin();
		JobDAOImpl theJob = (JobDAOImpl)job;
		theJob.setNodeId(nodeId);
		theJob.setScheduled(loaded);
		_em.persist(theJob);
		_txCtx.commit();
		return true;
	}

	
	public int updateAssignToNode(String nodeId, int i, int numNodes, long maxtime) throws DatabaseException {
		_txCtx.begin();
		Query q = _em.createNamedQuery("updateAssignToNode");
		q.setParameter("node", nodeId);
		q.setParameter("numNode", numNodes);
		q.setParameter("i", i);
		q.setParameter("maxTime", maxtime);
		
		int ret = q.executeUpdate();
		
		_txCtx.commit();
		return ret;
	}

	
	public boolean updateJob(JobDAO job) throws DatabaseException {
		_txCtx.begin();
        JobDAOImpl theJob = _em.find(JobDAOImpl.class, job.getJobId());
        if (theJob == null) {
            throw new DatabaseException("the updated job is not existed! job detail is: " + job);
        }
        theJob.setScheduledDate(job.getScheduledDate());
        theJob.getDetails().setRetryCount(job.getDetails().getRetryCount());
        theJob.setScheduled(job.isScheduled());
		_txCtx.commit();
		return true;
	}

	
	public int updateReassign(String oldnode, String newnode) throws DatabaseException {
		_txCtx.begin();
		Query q = _em.createNamedQuery("updateReassign");
		q.setParameter("newNode", newnode);
		q.setParameter("oldNode", oldnode);
		int ret = q.executeUpdate();
		_txCtx.commit();
		return ret;
	}

	public JobDAO createJob(String jobId, boolean transacted, JobDetails jobDetails,
			boolean persisted, long scheduledDate) {
		JobDAOImpl theJob = new JobDAOImpl();
		theJob.setJobId(jobId);
		theJob.setTransacted(transacted);
		theJob.setDetails(jobDetails);
		theJob.setPersisted(persisted);
		theJob.setTimestamp(scheduledDate);
		return theJob;
	}
	
	public JobDAO createJob(boolean transacted, JobDetails jobDetails,
			boolean persisted, long scheduledDate) {
        String jobId = new GUID().toString();
		return createJob(jobId, transacted, jobDetails, persisted, scheduledDate);
	}

}
