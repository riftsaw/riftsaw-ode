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
package org.apache.ode.bpel.runtime;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.common.FaultException;
import org.apache.ode.bpel.evt.PartnerLinkModificationEvent;
import org.apache.ode.bpel.evt.ScopeEvent;
import org.apache.ode.bpel.evt.VariableModificationEvent;
import org.apache.ode.bpel.explang.EvaluationContext;
import org.apache.ode.bpel.explang.EvaluationException;
import org.apache.ode.bpel.o.OAssign;
import org.apache.ode.bpel.o.OAssign.DirectRef;
import org.apache.ode.bpel.o.OAssign.LValueExpression;
import org.apache.ode.bpel.o.OAssign.PropertyRef;
import org.apache.ode.bpel.o.OAssign.VariableRef;
import org.apache.ode.bpel.o.OElementVarType;
import org.apache.ode.bpel.o.OExpression;
import org.apache.ode.bpel.o.OLink;
import org.apache.ode.bpel.o.OMessageVarType;
import org.apache.ode.bpel.o.OMessageVarType.Part;
import org.apache.ode.bpel.o.OProcess.OProperty;
import org.apache.ode.bpel.o.OScope;
import org.apache.ode.bpel.o.OScope.Variable;
import org.apache.ode.bpel.runtime.channels.FaultData;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.Namespaces;
import org.apache.ode.utils.msg.MessageBundle;
import org.apache.ode.bpel.evar.ExternalVariableModuleException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;

import java.io.IOException;
import java.net.URI;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Assign activity run-time template.
 */
class ASSIGN extends ACTIVITY {
    private static final long serialVersionUID = 1L;

    private static final Log __log = LogFactory.getLog(ASSIGN.class);

    private static final ASSIGNMessages __msgs = MessageBundle
            .getMessages(ASSIGNMessages.class);

    public ASSIGN(ActivityInfo self, ScopeFrame scopeFrame, LinkFrame linkFrame) {
        super(self, scopeFrame, linkFrame);
    }

    public void run() {
        OAssign oassign = getOAsssign();

        FaultData faultData = null;

		AssignHelper assignHelper = new AssignHelper(_self, _scopeFrame, _linkFrame);

		for (OAssign.Copy aCopy : oassign.copy) {
            try {
                assignHelper.copy(aCopy);
            } catch (FaultException fault) {
                if (aCopy.ignoreMissingFromData) {
                    if (fault.getQName().equals(getOAsssign().getOwner().constants.qnSelectionFailure) &&
                            (fault.getCause() != null && "ignoreMissingFromData".equals(fault.getCause().getMessage()))) {
                    continue;
                    }
                }
                if (aCopy.ignoreUninitializedFromVariable) {
                    if (fault.getQName().equals(getOAsssign().getOwner().constants.qnUninitializedVariable) &&
                            (fault.getCause() == null || !"throwUninitializedToVariable".equals(fault.getCause().getMessage()))) {
                    continue;
                    }
                }
                faultData = createFault(fault.getQName(), aCopy, fault
                        .getMessage());
                break;
            } catch (ExternalVariableModuleException e) {
                __log.error("Exception while initializing external variable", e);
                _self.parent.failure(e.toString(), null);
                return;
            }
        }

        if (faultData != null) {
            __log.info("Assignment Fault: " + faultData.getFaultName()
                    + ",lineNo=" + faultData.getFaultLineNo()
                    + ",faultExplanation=" + faultData.getExplanation());
            _self.parent.completed(faultData, CompensationHandler.emptySet());
        } else {
            _self.parent.completed(null, CompensationHandler.emptySet());
        }
    }

    protected Log log() {
        return __log;
    }

    private OAssign getOAsssign() {
        return (OAssign) _self.o;
    }

}
