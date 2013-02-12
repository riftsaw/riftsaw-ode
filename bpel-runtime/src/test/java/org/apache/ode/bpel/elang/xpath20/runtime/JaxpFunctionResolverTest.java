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
package org.apache.ode.bpel.elang.xpath20.runtime;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPathFunction;

import org.apache.ode.bpel.elang.xpath20.o.OXPath20ExpressionBPEL20;

import junit.framework.Assert;
import junit.framework.TestCase;

public class JaxpFunctionResolverTest extends TestCase {
    
    private static final String TEST_VALUE = "TestValue";

    public void testResolveFunction() {
        OXPath20ExpressionBPEL20 out=new OXPath20ExpressionBPEL20(null, null, null, null, null, false);

        JaxpFunctionResolver resolver=new JaxpFunctionResolver(null, out);

        XPathFunction function=resolver.resolveFunction(
                QName.valueOf("{java:"+JavaXPathFunction.class.getName()+"}method1"), 0);
        
        if (function == null) {
            Assert.fail("Failed to get Java function");
        }
    }
    
    public void testCallFunction() {
        OXPath20ExpressionBPEL20 out=new OXPath20ExpressionBPEL20(null, null, null, null, null, false);

        JaxpFunctionResolver resolver=new JaxpFunctionResolver(null, out);

        XPathFunction function=resolver.resolveFunction(
                QName.valueOf("{java:"+JavaXPathFunction.class.getName()+"}method1"), 0);
        
        if (function == null) {
            Assert.fail("Failed to get Java function");
        }
        
        java.util.List<Object> args=new java.util.Vector<Object>();
        args.add(TEST_VALUE);
        
        try {
            Object result=function.evaluate(args);
            
            if (result == null) {
                Assert.fail("No result");
            }
            
            // String values returned from the function are delimited by
            // double quotes
            if (!result.toString().equals("\""+TEST_VALUE+"\"")) {
                Assert.fail("Incorrect value: "+result.toString());
            }
        } catch (Exception e) {
            Assert.fail("Failed to invoke function: "+e);
        }
    }
}
