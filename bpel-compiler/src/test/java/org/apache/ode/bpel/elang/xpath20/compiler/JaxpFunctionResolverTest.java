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

package org.apache.ode.bpel.elang.xpath20.compiler;

import javax.xml.namespace.QName;
import javax.xml.xpath.XPathFunction;

import org.apache.ode.bpel.elang.xpath20.o.OXPath20ExpressionBPEL20;
import org.junit.*;

public class JaxpFunctionResolverTest {

    @Test
    public void testResolveJavaFunction() {
        MockCompilerContext ctx=new MockCompilerContext();
        OXPath20ExpressionBPEL20 out=new OXPath20ExpressionBPEL20(null, null, null, null, null, false);
        
        JaxpFunctionResolver resolver=new JaxpFunctionResolver(ctx, out, null, null);
        
        XPathFunction function=resolver.resolveFunction(QName.valueOf("{java:test}function"), 0);
        
        if (function == null) {
            Assert.fail("Java function did not resolve");
        }
    }

    @Test
    public void testAssociateVariableUsedInJavaFunction() {
        MockCompilerContext ctx=new MockCompilerContext();
        OXPath20ExpressionBPEL20 out=new OXPath20ExpressionBPEL20(null, null, null, null, null, false);
        
        JaxpFunctionResolver resolver=new JaxpFunctionResolver(ctx, out, null, null);
        
        XPathFunction function=resolver.resolveFunction(QName.valueOf("{java:test}function"), 0);
        
        if (function == null) {
            Assert.fail("Java function did not resolve");
        }
        
        String varName="MyVariable";
        QName varType=QName.valueOf("{ns}lp");
        
        ctx.registerElementVar(varName, varType);
        
        java.util.List<Object> list=new java.util.Vector<Object>();
        list.add(varName);
        
        try {
            function.evaluate(list);
        } catch (Exception e) {
            Assert.fail("Failed to evaluate Java function: "+e);
        }
        
        // Check that variable is now associated with the expresion
        if (!out.vars.containsKey(varName)) {
            Assert.fail("Variable '"+varName+"' has not been associated with the expression");
        }
    }
}
