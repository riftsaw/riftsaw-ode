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

import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.bpel.iapi.Scheduler.JobDetails;
import org.apache.ode.dao.scheduler.DatabaseException;
import org.apache.ode.dao.scheduler.JobDAO;
import org.apache.ode.dao.scheduler.SchedulerDAOConnection;
import org.apache.ode.utils.DbIsolation;
import org.apache.ode.utils.GUID;
import org.apache.ode.utils.StreamUtils;

/**
 * JDBC-based implementation of the {@link SchedulerDAOConnection} interface. Should work with most 
 * reasonably behaved databases. 
 * 
 * @author Maciej Szefler ( m s z e f l e r @ g m a i l . c o m )
 */
public class SchedulerDAOConnectionImpl implements SchedulerDAOConnection {

    private static final Log __log = LogFactory.getLog(SchedulerDAOConnectionImpl.class);

    private static final String DELETE_JOB = "delete from ODE_JOB where jobid = ? and nodeid = ?";

    private static final String UPDATE_REASSIGN = "update ODE_JOB set nodeid = ?, scheduled = false where nodeid = ?";

    private static final String UPDATE_JOB = "update ODE_JOB set ts = ?, retryCount = ?, scheduled = ? where jobid = ?";

    private static final String UPGRADE_JOB_DEFAULT = "update ODE_JOB set nodeid = ? where nodeid is null and scheduled = false "
            + "and mod(ts,?) = ? and ts < ?";

    private static final String UPGRADE_JOB_DB2 = "update ODE_JOB set nodeid = ? where nodeid is null and scheduled = false "
            + "and mod(ts,CAST(? AS BIGINT)) = ? and ts < ?";
    
    private static final String UPGRADE_JOB_SQLSERVER = "update ODE_JOB set nodeid = ? where nodeid is null and scheduled = false "
            + "and (ts % ?) = ? and ts < ?";

    private static final String UPGRADE_JOB_SYBASE = "update ODE_JOB set nodeid = ? where nodeid is null and scheduled = false "
            + "and convert(int, ts) % ? = ? and ts < ?";

    private static final String UPGRADE_JOB_SYBASE12 = "update ODE_JOB set nodeid = ? where nodeid is null and scheduled = false "
            + "and -1 <> ? and -1 <> ? and ts < ?";
    
    private static final String SAVE_JOB = "insert into ODE_JOB "
            + " (jobid, nodeid, ts, scheduled, transacted, "
            + "instanceId,"
            + "mexId,"
            + "processId,"
            + "type,"
            + "channel,"
            + "correlatorId,"
            + "correlationKeySet,"
            + "retryCount,"
            + "inMem,"
            + "detailsExt"
            + ") values(?, ?, ?, ?, ?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?,"
            + "?"
            + ")";

    private static final String GET_NODEIDS = "select distinct nodeid from ODE_JOB";

    private static final String SCHEDULE_IMMEDIATE = "select jobid, ts, transacted, scheduled, "
        + "instanceId,"
        + "mexId,"
        + "processId,"
        + "type,"
        + "channel,"
        + "correlatorId,"
        + "correlationKeySet,"
        + "retryCount,"
        + "inMem,"
        + "detailsExt"
        + " from ODE_JOB "
            + "where nodeid = ? and scheduled = false and ts < ? order by ts";
    
    private static final String UPDATE_SCHEDULED = "update ODE_JOB set scheduled = true where jobid in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final int UPDATE_SCHEDULED_SLOTS = 10;


    private DataSource _ds;

    private Dialect _dialect;
    
    private AtomicBoolean _active;
    
    private TransactionManager _txm;
    
    public SchedulerDAOConnectionImpl(AtomicBoolean active, DataSource ds, TransactionManager txm) {
        _active = active;
    	_ds = ds;
    	_txm = txm;
        _dialect = guessDialect();
    }

    public boolean deleteJob(String jobid, String nodeId) throws DatabaseException {
        if (__log.isDebugEnabled())
            __log.debug("deleteJob " + jobid + " on node " + nodeId);

        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            ps = con.prepareStatement(DELETE_JOB);
            ps.setString(1, jobid);
            ps.setString(2, nodeId);
            return ps.executeUpdate() == 1;
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    public List<String> getNodeIds() throws DatabaseException {
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            ps = con.prepareStatement(GET_NODEIDS, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ResultSet rs = ps.executeQuery();
            ArrayList<String> nodes = new ArrayList<String>();
            while (rs.next()) {
                String nodeId = rs.getString(1);
                if (nodeId != null)
                    nodes.add(rs.getString(1));
            }
            if (__log.isDebugEnabled())
                __log.debug("getNodeIds: " + nodes);
            return nodes;
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    public boolean insertJob(JobDAO job, String nodeId, boolean loaded) throws DatabaseException {
        if (__log.isDebugEnabled())
            __log.debug("insertJob " + job.getJobId() + " on node " + nodeId + " loaded=" + loaded);

        Connection con = null;
        PreparedStatement ps = null;
        try {
            int i = 1;
            con = getConnection();
            ps = con.prepareStatement(SAVE_JOB);
            ps.setString(i++, job.getJobId());
            ps.setString(i++, nodeId);
            ps.setLong(i++, job.getScheduledDate());
            ps.setBoolean(i++, loaded);
            ps.setBoolean(i++, job.isTransacted());
            
            JobDetails details = job.getDetails();
            
            ps.setObject(i++, details.instanceId, Types.BIGINT);
            ps.setObject(i++, details.mexId, Types.VARCHAR);
            ps.setObject(i++, details.processId, Types.VARCHAR);
            ps.setObject(i++, details.type, Types.VARCHAR);
            ps.setObject(i++, details.channel, Types.VARCHAR);
            ps.setObject(i++, details.correlatorId, Types.VARCHAR);
            ps.setObject(i++, details.correlationKeySet, Types.VARCHAR);
            ps.setObject(i++, details.retryCount, Types.INTEGER);
            ps.setObject(i++, details.inMem, Types.BOOLEAN);
            
            if (details.detailsExt == null || details.detailsExt.size() == 0) {
                ps.setObject(i++, null, Types.BLOB);
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try {
                    StreamUtils.write(bos, (Serializable) details.detailsExt);
                } catch (Exception ex) {
                    __log.error("Error serializing job detail: " + job.getDetails());
                    throw new DatabaseException(ex);
                }
                ps.setObject(i++, bos.toByteArray(), Types.BLOB);
            }
            
            return ps.executeUpdate() == 1;
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    public boolean updateJob(JobDAO job) throws DatabaseException {
        if (__log.isDebugEnabled())
            __log.debug("updateJob " + job.getJobId() + " retryCount=" + job.getDetails().getRetryCount());

        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            ps = con.prepareStatement(UPDATE_JOB);
            ps.setLong(1, job.getScheduledDate());
            ps.setInt(2, job.getDetails().getRetryCount());
            ps.setBoolean(3, job.isScheduled());
            ps.setString(4, job.getJobId());
            return ps.executeUpdate() == 1;
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    
    private Long asLong(Object o) {
        if (o == null) return null;
        else if (o instanceof BigDecimal) return ((BigDecimal) o).longValue();
        else if (o instanceof Long) return (Long) o;
        else if (o instanceof Integer) return ((Integer) o).longValue();
        else throw new IllegalStateException("Can't convert to long " + o.getClass());
    }

    private Integer asInteger(Object o) {
        if (o == null) return null;
        else if (o instanceof BigDecimal) return ((BigDecimal) o).intValue();
        else if (o instanceof Integer) return (Integer) o;
        else throw new IllegalStateException("Can't convert to integer " + o.getClass());
    }

    @SuppressWarnings("unchecked")
    public List<JobDAO> dequeueImmediate(String nodeId, long maxtime, int maxjobs) throws DatabaseException {
        ArrayList<JobDAO> ret = new ArrayList<JobDAO>(maxjobs);
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            ps = con.prepareStatement(SCHEDULE_IMMEDIATE);
            ps.setString(1, nodeId);
            ps.setLong(2, maxtime);
            ps.setMaxRows(maxjobs);
            
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Scheduler.JobDetails details = new Scheduler.JobDetails();
                details.instanceId = asLong(rs.getObject("instanceId"));
                details.mexId = (String) rs.getObject("mexId");
                details.processId = (String) rs.getObject("processId");
                details.type = (String) rs.getObject("type");
                details.channel = (String) rs.getObject("channel");
                details.correlatorId = (String) rs.getObject("correlatorId");
                details.correlationKeySet = (String) rs.getObject("correlationKeySet");
                details.retryCount = asInteger(rs.getObject("retryCount"));
                details.inMem = rs.getBoolean("inMem");
                if (rs.getObject("detailsExt") != null) {
                    try {
                        ObjectInputStream is = new ObjectInputStream(rs.getBinaryStream("detailsExt"));
                        details.detailsExt = (Map<String, Object>) is.readObject();
                        is.close();
                    } catch (Exception e) {
                        throw new DatabaseException("Error deserializing job detailsExt", e);
                    }
                }
                
                {
                    //For compatibility reasons, we check whether there are entries inside
                    //jobDetailsExt blob, which correspond to extracted entries. If so, we
                    //use them.

                    Map<String, Object> detailsExt = details.getDetailsExt();
                    if (detailsExt.get("type") != null) {
                        details.type = (String) detailsExt.get("type");
                    }
                    if (detailsExt.get("iid") != null) {
                        details.instanceId = (Long) detailsExt.get("iid");
                    }
                    if (detailsExt.get("pid") != null) {
                        details.processId = (String) detailsExt.get("pid");
                    }
                    if (detailsExt.get("inmem") != null) {
                        details.inMem = (Boolean) detailsExt.get("inmem");
                    }
                    if (detailsExt.get("ckey") != null) {
                        details.correlationKeySet = (String) detailsExt.get("ckey");
                    }
                    if (detailsExt.get("channel") != null) {
                        details.channel = (String) detailsExt.get("channel");
                    }
                    if (detailsExt.get("mexid") != null) {
                        details.mexId = (String) detailsExt.get("mexid");
                    }
                    if (detailsExt.get("correlatorId") != null) {
                        details.correlatorId = (String) detailsExt.get("correlatorId");
                    }
                    if (detailsExt.get("retryCount") != null) {
                        details.retryCount = Integer.parseInt((String) detailsExt.get("retryCount"));
                    }
                }
                
                JobDAO job = new JobDAOImpl(rs.getLong("ts"), rs.getString("jobid"), rs.getBoolean("transacted"), details);
                ret.add(job);
            }
            rs.close();
            ps.close();
            
            // mark jobs as scheduled, UPDATE_SCHEDULED_SLOTS at a time
            int j = 0;
            int updateCount = 0;
            ps = con.prepareStatement(UPDATE_SCHEDULED);
            for (int updates = 1; updates <= (ret.size() / UPDATE_SCHEDULED_SLOTS) + 1; updates++) {
                for (int i = 1; i <= UPDATE_SCHEDULED_SLOTS; i++) {
                    ps.setString(i, j < ret.size() ? ret.get(j).getJobId() : "");
                    j++;
                }
                ps.execute();
                updateCount += ps.getUpdateCount();
            }
            if (updateCount != ret.size()) {
              __log.error("Updating scheduled jobs failed to update all jobs; expected=" + ret.size()
                                + " actual=" + updateCount);
             return null;
              
            }
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
        return ret;
    }

    public int updateReassign(String oldnode, String newnode) throws DatabaseException {
        if (__log.isDebugEnabled())
            __log.debug("updateReassign from " + oldnode + " ---> " + newnode);
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            ps = con.prepareStatement(UPDATE_REASSIGN);
            ps.setString(1, newnode);
            ps.setString(2, oldnode);
            return ps.executeUpdate();
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    public int updateAssignToNode(String node, int i, int numNodes, long maxtime) throws DatabaseException {
        if (__log.isDebugEnabled())
            __log.debug("updateAsssignToNode node=" + node + " " + i + "/" + numNodes + " maxtime=" + maxtime);
        Connection con = null;
        PreparedStatement ps = null;
        try {
            con = getConnection();
            if (_dialect == Dialect.SQLSERVER) {
                ps = con.prepareStatement(UPGRADE_JOB_SQLSERVER);
            } else if (_dialect == Dialect.DB2) {
                ps = con.prepareStatement(UPGRADE_JOB_DB2);
            } else if (_dialect == Dialect.SYBASE) {
                ps = con.prepareStatement(UPGRADE_JOB_SYBASE);
            } else if (_dialect == Dialect.SYBASE12) {
                ps = con.prepareStatement(UPGRADE_JOB_SYBASE12);
            } else {
                ps = con.prepareStatement(UPGRADE_JOB_DEFAULT);
            }
            ps.setString(1, node);
            ps.setInt(2, numNodes);
            ps.setInt(3, i);
            ps.setLong(4, maxtime);
            return ps.executeUpdate();
        } catch (SQLException se) {
            throw new DatabaseException(se);
        } finally {
            close(ps);
            close(con);
        }
    }

    private Connection getConnection() throws SQLException {
        Connection c = _ds.getConnection();
        DbIsolation.setIsolationLevel(c);
        return c;
    }

    private void close(PreparedStatement ps) {
        if (ps != null) {
            try {
                ps.close();
            } catch (Exception e) {
                __log.warn("Exception while closing prepared statement", e);
            }
        }
    }

    private void close(Connection con) {
        if (con != null) {
            try {
                con.close();
            } catch (Exception e) {
                __log.warn("Exception while closing connection", e);
            }
        }
    }

    private Dialect guessDialect() {
        Dialect d = Dialect.UNKNOWN;
        Connection con = null;
        try {
            con = getConnection();
            DatabaseMetaData metaData = con.getMetaData();
            if (metaData != null) {
                String dbProductName = metaData.getDatabaseProductName();
                int dbMajorVer = metaData.getDatabaseMajorVersion();
                __log.debug("Using database " + dbProductName + " major version " + dbMajorVer);
                if (dbProductName.indexOf("DB2") >= 0) {
                    d = Dialect.DB2;
                } else if (dbProductName.indexOf("Derby") >= 0) {
                    d = Dialect.DERBY;
                } else if (dbProductName.indexOf("Firebird") >= 0) {
                    d = Dialect.FIREBIRD;
                } else if (dbProductName.indexOf("HSQL") >= 0) {
                    d = Dialect.HSQL;
                } else if (dbProductName.indexOf("Microsoft SQL") >= 0) {
                    d = Dialect.SQLSERVER;
                } else if (dbProductName.indexOf("MySQL") >= 0) {
                    d = Dialect.MYSQL;
                } else if (dbProductName.indexOf("Sybase") >= 0 || dbProductName.indexOf("ASE") >= 0 || dbProductName.indexOf("Adaptive") >= 0) {
                    d = Dialect.SYBASE;
                    if( dbMajorVer >= 12 ) {
                        d = Dialect.SYBASE12;
                    }
                }
            }
        } catch (SQLException e) {
            __log.warn("Unable to determine database dialect", e);
        } finally {
            close(con);
        }
        __log.debug("Using database dialect: " + d);
        return d;
    }

    enum Dialect {
        DB2, DERBY, FIREBIRD, HSQL, MYSQL, ORACLE, SQLSERVER, SYBASE, SYBASE12, UNKNOWN 
    }

	public void close() {

	}

	public boolean isClosed() {
		return _active.get();
	}

	public JobDAO createJob(String jobid, boolean transacted, JobDetails jobDetails,
			boolean persisted, long scheduledDate) {
		return new JobDAOImpl(scheduledDate, jobid, transacted, jobDetails);
	}
	
	public JobDAO createJob(boolean transacted, JobDetails jobDetails,
			boolean persisted, long scheduledDate){
		return createJob(new GUID().toString(), transacted, jobDetails, persisted, scheduledDate);
	}
    
}
