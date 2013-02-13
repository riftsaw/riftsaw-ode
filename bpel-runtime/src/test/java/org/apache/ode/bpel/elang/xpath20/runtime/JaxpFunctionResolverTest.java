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

import java.net.URI;
import java.util.Date;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPathFunction;

import org.apache.ode.bpel.common.FaultException;
import org.apache.ode.bpel.elang.xpath20.o.OXPath20ExpressionBPEL20;
import org.apache.ode.bpel.explang.EvaluationContext;
import org.apache.ode.bpel.explang.EvaluationException;
import org.apache.ode.bpel.o.OExpression;
import org.apache.ode.bpel.o.OLink;
import org.apache.ode.bpel.o.OMessageVarType.Part;
import org.apache.ode.bpel.o.OProcess.OProperty;
import org.apache.ode.bpel.o.OScope.Variable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import junit.framework.Assert;
import junit.framework.TestCase;

public class JaxpFunctionResolverTest extends TestCase {
    
    private static final String TEST_PROCESS = "{test}Process";
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
    
    public void testCallMethodNoContext() {
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
    
    public void testCallMethodWithContext() {
        OXPath20ExpressionBPEL20 out=new OXPath20ExpressionBPEL20(null, null, null, null, null, false);

        TestContext tc=new TestContext();
        tc._processName = QName.valueOf(TEST_PROCESS);
        
        JaxpFunctionResolver resolver=new JaxpFunctionResolver(tc, out);

        XPathFunction function=resolver.resolveFunction(
                QName.valueOf("{java:"+JavaXPathFunction.class.getName()+"}method2"), 0);
        
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
            if (!result.toString().equals("\""+TEST_PROCESS+":"+TEST_VALUE+"\"")) {
                Assert.fail("Incorrect value: "+result.toString());
            }
        } catch (Exception e) {
            Assert.fail("Failed to invoke function: "+e);
        }
    }
    
    public class TestContext implements EvaluationContext {
        
        public QName _processName=null;

        public Node readVariable(Variable variable, Part part)
                throws FaultException {
            // TODO Auto-generated method stub
            return null;
        }

        public Node getPartData(Element message, Part part)
                throws FaultException {
            // TODO Auto-generated method stub
            return null;
        }

        public String readMessageProperty(Variable variable, OProperty property)
                throws FaultException {
            // TODO Auto-generated method stub
            return null;
        }

        public boolean isLinkActive(OLink olink) throws FaultException {
            // TODO Auto-generated method stub
            return false;
        }

        public Node getRootNode() {
            // TODO Auto-generated method stub
            return null;
        }

        public Node evaluateQuery(Node root, OExpression expr)
                throws FaultException, EvaluationException {
            // TODO Auto-generated method stub
            return null;
        }

        public Long getProcessId() {
            // TODO Auto-generated method stub
            return null;
        }

        public QName getProcessQName() {
            return _processName;
        }

        public boolean narrowTypes() {
            // TODO Auto-generated method stub
            return false;
        }

        public URI getBaseResourceURI() {
            // TODO Auto-generated method stub
            return null;
        }

        public Node getPropertyValue(QName propertyName) {
            // TODO Auto-generated method stub
            return null;
        }

        public Date getCurrentEventDateTime() {
            // TODO Auto-generated method stub
            return null;
        }
        
    }
}
