package org.apache.ode.bpel.wstx;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.engine.MessageImpl;
import org.apache.ode.bpel.iapi.Message;
import org.oasis_open.docs.ws_tx.wscoor._2006._06.CoordinationContextType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.arjuna.mw.wst.TxContext;
import com.arjuna.mw.wst11.TransactionManager;
import com.arjuna.mw.wst11.UserTransaction;
import com.arjuna.mw.wst11.common.CoordinationContextHelper;
import com.arjuna.mwlabs.wst11.at.context.TxContextImple;
import com.arjuna.webservices11.wscoor.CoordinationConstants;
import com.arjuna.wst.SystemException;
import com.arjuna.wst.TransactionRolledBackException;
import com.arjuna.wst.UnknownTransactionException;
import com.arjuna.wst.WrongStateException;

/**
 * 
 * @author ibek
 *
 */
public class AtomicTransaction implements WebServiceTransaction {

    private static final Log __log = LogFactory.getLog(AtomicTransaction.class);

    protected UserTransaction _tx;
    protected TxContext _txcontext;
    protected boolean _active;
    protected boolean _subordinate;

    public AtomicTransaction() {
        _active = false;
        _subordinate = false;
    }

    /**
     * Begin of transaction must be performed with mutual exclusion in one thread
     * because the registration service cannot begin more transactions concurrently.
     */
    private static synchronized void begin(UserTransaction tx) throws WrongStateException, SystemException{
        tx.begin();
    }
    
    public void begin(Message bpelRequest) throws WrongStateException, SystemException {
        MessageImpl req = (MessageImpl)bpelRequest;
        _subordinate = false;
        if(req._dao.getHeader() != null){
            try {
                NodeList cc = req._dao.getHeader().getElementsByTagNameNS(CoordinationConstants.WSCOOR_NAMESPACE, 
                        CoordinationConstants.WSCOOR_ELEMENT_COORDINATION_CONTEXT);
                if (cc.getLength() > 0){
                    CoordinationContextType cct = CoordinationContextHelper.deserialise((Element)cc.item(0).getFirstChild());
                    if (cct != null) {
                        TxContext ctx = new TxContextImple(cct);
                        TransactionManager.getTransactionManager().resume(ctx);
                        _subordinate = true;
                    }
                }
            } catch (Exception e) {
                __log.error("Wrong coordination context. The transaction won't be subordinated.");
            }
        }
        
        _tx = UserTransaction.getUserTransaction();
        if (_subordinate && _tx != null) {
            _tx = UserTransaction.getUserTransaction().getUserSubordinateTransaction();
        }
        
        if (_tx == null)
            throw new SystemException(
                    "Distributed transaction has not been created. Check that JBoss XTS is runnning.");
        try {
            begin(_tx);
        } catch (WrongStateException wse) {
            TransactionManager.getTransactionManager().suspend(); // previous transaction will be resumed by another instance
            _tx = UserTransaction.getUserTransaction();
            begin(_tx); // we try again to create new transaction
        }
        _txcontext = TransactionManager.getTransactionManager().currentTransaction();
        _active = true;
    }

    public void commit() throws SecurityException, UnknownTransactionException, SystemException,
            WrongStateException {
        _active = false;
        try {
            resume();
            _tx.commit();
        } catch (TransactionRolledBackException e) {
            __log.debug("Web service transaction was aborted.");
        } finally {
            _tx = null;
            _txcontext = null;
        }
    }
    
    public void complete() throws UnknownTransactionException, SystemException, WrongStateException {
        // this is atomic transaction, we cannot partially complete the transaction as BusinessActivity can
    }

    public boolean isActive() {
        return _tx != null && _active;
    }
    
    public boolean isSubordinate() {
        return _subordinate;
    }

    public String getTransactionIdentifier() {
        return _tx.transactionIdentifier();
    }
    
    public WebServiceTransactionType getType() {
        return WebServiceTransactionType.ATOMIC_TRANSACTION;
    }

    public void rollback() throws SecurityException, UnknownTransactionException, SystemException,
            WrongStateException {
        _active = false;
        try{
            resume();
            _tx.rollback();
        } finally {
            _tx = null;
            _txcontext = null;
        }
    }

    public void resume() throws UnknownTransactionException, SystemException {
        if (!_txcontext.equals(TransactionManager.getTransactionManager().currentTransaction())) {
            TransactionManager.getTransactionManager().resume(_txcontext);
            _tx = UserTransaction.getUserTransaction();
        }
    }

    public void suspend() throws SystemException {
        _txcontext = TransactionManager.getTransactionManager().suspend();
    }

    public Element putCoordinationContext(Element headerElement) throws UnknownTransactionException, SystemException {
        resume();
        final TxContextImple txContext = (TxContextImple) _txcontext;
        CoordinationContextType ctx = txContext.context().getCoordinationContext();
        try {
            Document doc = headerElement.getOwnerDocument();
            Element coord = doc.createElementNS(CoordinationConstants.WSCOOR_NAMESPACE,
                    CoordinationConstants.WSCOOR_ELEMENT_COORDINATION_CONTEXT);
            headerElement.appendChild(coord);
            CoordinationContextHelper.serialise(ctx, headerElement);
            
            Element parent = doc.createElementNS(CoordinationConstants.WSCOOR_NAMESPACE,
                    CoordinationConstants.WSCOOR_ELEMENT_COORDINATION_CONTEXT);
            Node tmp = headerElement.getElementsByTagNameNS(CoordinationConstants.WSCOOR_NAMESPACE, CoordinationConstants.WSCOOR_ELEMENT_COORDINATION_CONTEXT).item(0);
            parent.appendChild(tmp.cloneNode(true));
            
            headerElement.replaceChild(parent, tmp);
        } catch (Exception e) {
            e.printStackTrace();
            throw new SystemException("Coordination context has not been added to the header.");
        }
        return headerElement;
    }

}
