package org.apache.ode.dao.scheduler;

import org.apache.ode.dao.DAOConnectionFactory;

public interface SchedulerDAOConnectionFactory extends DAOConnectionFactory<SchedulerDAOConnection>{
	
	public SchedulerDAOConnection getConnection();

}
