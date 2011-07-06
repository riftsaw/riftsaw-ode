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
package org.apache.ode.il.cache;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.iapi.CacheProvider;
import org.apache.ode.il.config.OdeConfigProperties;

/**
 * This is Cache Provider Factory, users should add following lines into ode configuration files. see {@CacheProvider}
 * 
 * cache.provider = org.apache.ode.bpel.engine.DefaultCacheProvider (updating this value by using your own CacheProvider)
 * 
 * @author jeffyu
 *
 */
public class CacheProviderFactory {

	private static final Log __log = LogFactory.getLog(CacheProviderFactory.class);
	
	public static CacheProvider getCacheProvider(OdeConfigProperties props) {
		Class<?> clz;
		CacheProvider cp = null;
		try {
			clz = Thread.currentThread().getContextClassLoader().loadClass(props.getCacheProvider());
			cp = (CacheProvider)clz.newInstance();
		} catch (Exception e) {
			__log.error("Error in creating cache provider by using " + props.getCacheProvider());
			throw new RuntimeException("Error in creating cache provider.", e);
		}
		return cp;
	}
}
