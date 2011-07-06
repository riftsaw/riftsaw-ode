/**
 * 
 */
package org.apache.ode.scheduler.simple.jdbc;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;
import javax.transaction.TransactionManager;

import org.apache.ode.dao.scheduler.SchedulerDAOConnection;
import org.apache.ode.dao.scheduler.SchedulerDAOConnectionFactory;

/**
 * @author jeffyu
 *
 */
public class SchedulerDAOConnectionFactoryImpl implements SchedulerDAOConnectionFactory {
	
	static ThreadLocal<SchedulerDAOConnection> _connections = new ThreadLocal<SchedulerDAOConnection>();
	
	DataSource _ds;
	TransactionManager _txm;
	AtomicBoolean _active = new AtomicBoolean(true);
	
	public SchedulerDAOConnection getConnection() {
		if (_connections.get()==null || _connections.get().isClosed() ){
		      _connections.set(new SchedulerDAOConnectionImpl(_active,_ds,_txm));
		    }
		return _connections.get();
	}

	
	public void init(Properties p, TransactionManager txm, Object env) {
		_ds = (DataSource) env;
	    _txm = txm;
	}


	public void shutdown() {
		_active.set(false);
	}

}
