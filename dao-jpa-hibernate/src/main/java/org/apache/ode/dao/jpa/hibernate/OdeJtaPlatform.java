/**
 * 
 */
package org.apache.ode.dao.jpa.hibernate;

import java.util.Map;
import java.util.Properties;

import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.hibernate.service.jta.platform.internal.AbstractJtaPlatform;

/**
 * 
 * uses {@link HibernateUtil} to obtain the JTA {@link TransactionManager} object.
 * @author jeffyu
 *
 */
public class OdeJtaPlatform extends AbstractJtaPlatform {
	
	
	private Properties properties = new Properties();
	
	public void configure(Map configValues) {
		super.configure(configValues);
		properties.putAll(configValues);
	}
	
	@Override
	protected TransactionManager locateTransactionManager() {
		return HibernateUtil.getTransactionManager(properties);
	}

	@Override
	protected UserTransaction locateUserTransaction() {
		throw new UnsupportedOperationException("locateUserTransaction is not supported. Use the locateTransactionManager instead.");
	}

}
