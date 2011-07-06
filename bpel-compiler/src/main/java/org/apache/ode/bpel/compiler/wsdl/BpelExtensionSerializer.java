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
package org.apache.ode.bpel.compiler.wsdl;

import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.ode.bpel.compiler.bom.*;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.msg.MessageBundle;
import com.ibm.wsdl.util.xml.XPathUtils;

import javax.wsdl.Definition;
import javax.wsdl.WSDLException;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.ExtensionDeserializer;
import javax.wsdl.extensions.ExtensionRegistry;
import javax.wsdl.extensions.ExtensionSerializer;
import javax.xml.namespace.QName;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * BPEL extension (partnerLinkType/propertyAlias) serializer for WSDL4J.
 */
public class BpelExtensionSerializer implements ExtensionDeserializer, ExtensionSerializer {
  protected static final Messages __msgs = MessageBundle.getMessages(Messages.class);

  private BpelObjectFactory _factory;

  public BpelExtensionSerializer(BpelObjectFactory factory) {
      _factory = factory;
  }

  /**
   * @see javax.wsdl.extensions.ExtensionSerializer#marshall(java.lang.Class,
   *      javax.xml.namespace.QName,
   *      javax.wsdl.extensions.ExtensibilityElement, java.io.PrintWriter,
   *      javax.wsdl.Definition, javax.wsdl.extensions.ExtensionRegistry)
   */
  public void marshall(Class arg0, QName arg1, ExtensibilityElement arg2, PrintWriter arg3, Definition arg4,
          ExtensionRegistry arg5) throws WSDLException {
        if("partnerLinkType".equals(arg1.getLocalPart()))
    {
      String namespaceURI = arg1.getNamespaceURI();
      PartnerLinkType plink = (PartnerLinkType)arg2;

      // xmlns:h="http://www.w3.org/TR/html4/"
      StringBuffer sb = new StringBuffer();
      sb.append("<ns0:partnerLinkType");
      sb.append(" xmlns:ns0='").append(namespaceURI).append("'");
      sb.append(" xmlns:ns1='").append(plink.getName().getNamespaceURI()).append("'");
      sb.append(" name='ns1:").append(plink.getName().getLocalPart()).append("'>");

      for(PartnerLinkType.Role role : plink.getRoles())
      {
        sb.append("<ns0:role");
        sb.append(" name='").append(role.getName()).append("'/>");
      }

      sb.append("</ns0:partnerLinkType>");

      arg3.write(sb.toString());
    }
    else if("property".equals(arg1.getLocalPart()))
    {
      String namespaceURI = arg1.getNamespaceURI();
      Property property = (Property)arg2;

      StringBuffer sb = new StringBuffer();
      sb.append("<ns0:property");
      sb.append(" xmlns:ns0='").append(namespaceURI).append("'");
      sb.append(" xmlns:n1='").append(property.getType().getNamespaceURI()).append("'");
      sb.append(" type='").append("n1:").append(property.getType().getLocalPart()).append("'");
      sb.append("'/>");

      arg3.write(sb.toString());
    }
    else if("propertyAlias".equals(arg1.getLocalPart()))
    {
      String namespaceURI = arg1.getNamespaceURI();
      PropertyAlias propertyAlias = (PropertyAlias)arg2;

      StringBuffer sb = new StringBuffer();
      sb.append("<ns0:propertyAlias");
      sb.append(" xmlns:ns0='").append(namespaceURI).append("'");

      // propertyName attribute
      sb.append(" xmlns:ns1='").append(propertyAlias.getPropertyName().getNamespaceURI()).append("'");
      sb.append(" propertyName='").append("ns1:").append(propertyAlias.getPropertyName().getLocalPart()).append("'");

      // messageType attribute
      sb.append(" xmlns:ns2='").append(propertyAlias.getMessageType().getNamespaceURI()).append("'");
      sb.append(" messageType='").append("ns2:").append(propertyAlias.getMessageType().getLocalPart()).append("'");

      // part attribute
      sb.append(" part='").append(propertyAlias.getPart()).append("'");

      // query child elements
        sb.append("<ns0:query>");
        sb.append(propertyAlias.getQuery());
        sb.append("</ns0:query>");

      sb.append("</ns0:propertyAlias>");

      arg3.write(sb.toString());
    }
    else
    {
      throw new UnsupportedOperationException("Unknown element "+arg1);
    }
  }

  /**
   * @see javax.wsdl.extensions.ExtensionDeserializer#unmarshall(java.lang.Class,
   *      javax.xml.namespace.QName, org.w3c.dom.Element,
   *      javax.wsdl.Definition, javax.wsdl.extensions.ExtensionRegistry)
   */
  public ExtensibilityElement unmarshall(Class clsType, QName eltype, Element el, Definition def, ExtensionRegistry extReg)
          throws WSDLException {

      validateExtensibilityElementContext(el);

      BpelObject4WSDL obj;
      try {
          obj = (BpelObject4WSDL) _factory.createBpelObject(el,new URI(def.getDocumentBaseURI()));
      } catch (URISyntaxException e) {
          throw new RuntimeException(e);
      }
      
      obj.setElementType(eltype);
      obj.setRequired(false);  // ? what does this do 
      obj.setTargetNamespace(def.getTargetNamespace());
      return obj;
  }

  /**
   * Dereference a qualified name given in the "ns:name" form using the namespace
   * context of a given element.
   *
   * @param prefixedQNameStr qualified name, represented as a prefixed string
   * @param context context element
   * @return a resolved {@link QName}
   * @throws javax.wsdl.WSDLException in case of resolution error (e.g. undefined prefix)
   */
  protected static QName derefQName(String prefixedQNameStr, Element context)
                          throws WSDLException {
    int idx = prefixedQNameStr.indexOf(":");
    String uri;

    if (idx == -1) {
      uri = DOMUtils.getNamespaceURIFromPrefix(context, null);
    } else {
      if (idx >= prefixedQNameStr.length() || idx == 0) {
        String msg = __msgs.msgMalformedQName(prefixedQNameStr);
        throw new WSDLException(WSDLException.INVALID_WSDL, msg);
      }

        // Look up the prefix from the namespaces defined *at the element*.
      String prefix = prefixedQNameStr.substring(0, idx);
      uri = DOMUtils.getMyNSContext(context).getNamespaceURI(prefix);

      if (uri == null) {
        String msg = __msgs.msgInvalidNamespacePrefix(prefix);
        throw new WSDLException(WSDLException.INVALID_WSDL, msg);
      }
    }

    return new QName(uri, prefixedQNameStr.substring(idx + 1, prefixedQNameStr.length()));
  }


  /**
   * Ensure that a top-level extensibility element does not occur before other WSDL
   * declarations (messages, port types, etc.).
   *
   * @param el the extensibility element
   * @throws javax.wsdl.WSDLException if the requirements of the WSDL schema are violated.
   */
  static void validateExtensibilityElementContext(Element el) throws WSDLException {
    Node n = el.getParentNode();
    if (n == null || n.getNodeType() != Node.ELEMENT_NODE) {
      WSDLException we = new WSDLException(WSDLException.OTHER_ERROR,
          __msgs.msgCannotBeDocumentRootElement(DOMUtils.getNodeQName(el).toString()));
      we.setLocation(XPathUtils.getXPathExprFromNode(el));
      throw we;
    }
    Element def = (Element) n;
    if (def.getNamespaceURI() == null || !def.getNamespaceURI().equals(DOMUtils.WSDL_NS)
      || !def.getLocalName().equals(DOMUtils.WSDL_ROOT_ELEMENT))
    {
      WSDLException we =  new WSDLException(WSDLException.OTHER_ERROR,
          __msgs.msgMustBeChildOfDef(DOMUtils.getNodeQName(el).toString()));
      we.setLocation(XPathUtils.getXPathExprFromNode(el));
      throw we;
    }
    /*
     * NOTE:
     * This check was originally put in to match the W3C version of the WSDL
     * schema, but the WS-I version is more permissive.  Leaving this check out
     * complies with the WS-I version, which is preferable.
     */
//    n = el.getNextSibling();
//    while (n != null) {
//      if (n.getNamespaceURI() != null && n.getNamespaceURI().equals(DOMUtils.WSDL_NS)) {
//        WSDLException we = new WSDLException(WSDLException.INVALID_WSDL,
//            MSGS.msgExtensibilityElementsMustBeLast(DOMUtils.getElementQName(el).toString()));
//        we.setLocation(XPathUtils.getXPathExprFromNode(el));
//        throw we;
//      }
//      n = n.getNextSibling();
//    }
  }

  protected String getAttribute(Element element, String attributeName) {
    Attr attribute = element.getAttributeNode(attributeName);
    if (attribute == null)
      return null;
    return attribute.getValue();

  }
}
