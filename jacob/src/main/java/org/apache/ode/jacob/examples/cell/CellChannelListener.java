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
/*
 * SOURCE FILE GENERATATED BY JACOB CHANNEL CLASS GENERATOR
 * 
 *               !!! DO NOT EDIT !!!! 
 * 
 * Generated On  : Fri Apr 16 09:42:21 EDT 2010
 * For Interface : org.apache.ode.jacob.examples.cell.Cell
 */

package org.apache.ode.jacob.examples.cell;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;

/**
 * An auto-generated channel listener abstract class for the 
 * {@link org.apache.ode.jacob.examples.cell.Cell} channel type. 
 * @see org.apache.ode.jacob.examples.cell.Cell
 * @see org.apache.ode.jacob.examples.cell.CellChannel
 */
public abstract class CellChannelListener
    extends org.apache.ode.jacob.ChannelListener<org.apache.ode.jacob.examples.cell.CellChannel>
    implements org.apache.ode.jacob.examples.cell.Cell
{

    private static final Log __log = LogFactory.getLog(org.apache.ode.jacob.examples.cell.Cell.class);

    protected Log log() { return __log; } 

    protected CellChannelListener(org.apache.ode.jacob.examples.cell.CellChannel channel) {
       super(channel);
    }
}
