// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2007 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cmscore.ldap;


import java.io.*;
import java.util.*;
import java.net.*;
import java.util.*;
import java.text.*;
import java.math.*;
import java.security.*;
import java.security.cert.X509Certificate;
import javax.servlet.*;
import javax.servlet.http.*;
import java.security.cert.*;
import netscape.ldap.*;
import netscape.security.util.*;
import netscape.security.x509.*;
import com.netscape.certsrv.common.*;
import com.netscape.certsrv.base.*;
import com.netscape.certsrv.logging.*;
import com.netscape.certsrv.authority.*;
import com.netscape.certsrv.ca.*;
import com.netscape.certsrv.dbs.*;
import com.netscape.certsrv.dbs.certdb.*;
import com.netscape.certsrv.request.IRequest;
import com.netscape.certsrv.apps.*;
import com.netscape.certsrv.ldap.*;
import com.netscape.certsrv.publish.*;

import com.netscape.cmscore.util.*;
import com.netscape.cmscore.cert.*;
import com.netscape.cmscore.dbs.*;
import com.netscape.cmscore.util.Debug;


public class PublisherProcessor implements
		IPublisherProcessor, IXcertPublisherProcessor {

    public Hashtable mPublisherPlugins = new Hashtable();
    public Hashtable mPublisherInsts = new Hashtable();
    public Hashtable mMapperPlugins = new Hashtable();
    public Hashtable mMapperInsts = new Hashtable();
    public Hashtable mRulePlugins = new Hashtable();
    public Hashtable mRuleInsts = new Hashtable();

    /**
     protected PublishRuleSet mRuleSet = null;
     **/
    protected LdapConnModule mLdapConnModule = null;

    private IConfigStore mConfig = null;
    private IConfigStore mLdapConfig = null;
    private String mId = null;
    private ILogger mLogger = CMS.getLogger();

    protected ICertAuthority mAuthority = null;
    protected LdapRequestListener mLdapRequestListener = null;
    private boolean mCreateOwnDNEntry = false;
    private boolean mInited = false;

    public PublisherProcessor(String id) {
        mId = id;
    }

    public String getId() {
        return mId;
    }
		
    public void setId(String id) {
        mId = id;
    }

    public IConfigStore getConfigStore() {
        return mConfig;
    }

    public void init(ISubsystem authority, IConfigStore config)
        throws EBaseException {
        mConfig = config;
        mAuthority = (ICertAuthority) authority;

        // load publisher implementation
        IConfigStore publisherConfig = config.getSubStore("publisher");
        IConfigStore c = publisherConfig.getSubStore(PROP_IMPL);
        mCreateOwnDNEntry = mConfig.getBoolean("createOwnDNEntry", false);
        Enumeration mImpls = c.getSubStoreNames();

        while (mImpls.hasMoreElements()) {
            String id = (String) mImpls.nextElement();
            String pluginPath = c.getString(id + "." + PROP_CLASS);
            PublisherPlugin plugin = new PublisherPlugin(id, pluginPath);

            mPublisherPlugins.put(id, plugin);
        }
        if (Debug.ON)
            Debug.trace("loaded publisher plugins");

            // load publisher instances
        c = publisherConfig.getSubStore(PROP_INSTANCE);
        Enumeration instances = c.getSubStoreNames();

        while (instances.hasMoreElements()) {
            String insName = (String) instances.nextElement();
            String implName = c.getString(insName + "." + 
                    PROP_PLUGIN);
            PublisherPlugin plugin =
                (PublisherPlugin) mPublisherPlugins.get(implName);

            if (plugin == null) { 
                log(ILogger.LL_FAILURE, 
			CMS.getLogMessage("CMSCORE_LDAP_PLUGIN_NOT_FIND", implName));
                throw new ELdapException(implName);
            }
            String className = plugin.getClassPath();

            // Instantiate and init the publisher.
            boolean isEnable = false;
            ILdapPublisher publisherInst = null;

            try {
                publisherInst = (ILdapPublisher)
                        Class.forName(className).newInstance();
                IConfigStore pConfig = 
                    c.getSubStore(insName);

                publisherInst.init(pConfig);
                isEnable = true;

            } catch (ClassNotFoundException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (IllegalAccessException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (InstantiationException e) {
                String errMsg = "PublisherProcessor: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (Throwable e) {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_SKIP_PUBLISHER", insName, e.toString()));
                // Let the server continue if it is a
                // mis-configuration. But the instance
                // will be skipped. This give another
                // chance to the user to re-configure
                // the server via console.
            }

            if (publisherInst == null) {
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            }

            if (insName == null) {
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", insName));
            }

            // add publisher instance to list.
            mPublisherInsts.put(insName, new 
                PublisherProxy(isEnable, publisherInst));
            log(ILogger.LL_INFO, "publisher instance " + insName + " added");
            if (Debug.ON)
                Debug.trace("loaded publisher instance " + insName + " impl " + implName);
        }

        // load mapper implementation
        IConfigStore mapperConfig = config.getSubStore("mapper");

        c = mapperConfig.getSubStore(PROP_IMPL);
        mImpls = c.getSubStoreNames();
        while (mImpls.hasMoreElements()) {
            String id = (String) mImpls.nextElement();
            String pluginPath = c.getString(id + "." + PROP_CLASS);
            MapperPlugin plugin = new MapperPlugin(id, pluginPath);

            mMapperPlugins.put(id, plugin);
        }
        if (Debug.ON)
            Debug.trace("loaded mapper plugins");

            // load mapper instances
        c = mapperConfig.getSubStore(PROP_INSTANCE);
        instances = c.getSubStoreNames();
        while (instances.hasMoreElements()) {
            String insName = (String) instances.nextElement();
            String implName = c.getString(insName + "." + 
                    PROP_PLUGIN);
            MapperPlugin plugin =
                (MapperPlugin) mMapperPlugins.get(implName);

            if (plugin == null) { 
                log(ILogger.LL_FAILURE, 
			CMS.getLogMessage("CMSCORE_LDAP_MAPPER_NOT_FIND", implName));
                throw new ELdapException(implName);
            }
            String className = plugin.getClassPath();

            if (Debug.ON)
                Debug.trace("loaded mapper className=" + className);

                // Instantiate and init the mapper
            boolean isEnable = false;
            ILdapMapper mapperInst = null;

            try {
                mapperInst = (ILdapMapper)
                        Class.forName(className).newInstance();
                IConfigStore mConfig = 
                    c.getSubStore(insName);

                mapperInst.init(mConfig);
                isEnable = true;
            } catch (ClassNotFoundException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (IllegalAccessException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (InstantiationException e) {
                String errMsg = "PublisherProcessor: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (Throwable e) {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_SKIP_MAPPER", insName, e.toString()));
                // Let the server continue if it is a
                // mis-configuration. But the instance
                // will be skipped. This give another
                // chance to the user to re-configure
                // the server via console.
            }

            if (mapperInst == null) {
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            }

            // add manager instance to list.
            mMapperInsts.put(insName, new MapperProxy(
                    isEnable, mapperInst));

            log(ILogger.LL_INFO, "mapper instance " + insName + " added");
            if (Debug.ON)
                Debug.trace("loaded mapper instance " + insName + " impl " + implName);
        }

        // load rule implementation
        IConfigStore ruleConfig = config.getSubStore("rule");

        c = ruleConfig.getSubStore(PROP_IMPL);
        mImpls = c.getSubStoreNames();
        while (mImpls.hasMoreElements()) {
            String id = (String) mImpls.nextElement();
            String pluginPath = c.getString(id + "." + PROP_CLASS);
            RulePlugin plugin = new RulePlugin(id, pluginPath);

            mRulePlugins.put(id, plugin);
        }
        if (Debug.ON)
            Debug.trace("loaded rule plugins");

            // load rule instances
        c = ruleConfig.getSubStore(PROP_INSTANCE);
        instances = c.getSubStoreNames();
        while (instances.hasMoreElements()) {
            String insName = (String) instances.nextElement();
            String implName = c.getString(insName + "." + 
                    PROP_PLUGIN);
            RulePlugin plugin =
                (RulePlugin) mRulePlugins.get(implName);

            if (plugin == null) { 
                log(ILogger.LL_FAILURE, 
			CMS.getLogMessage("CMSCORE_LDAP_RULE_NOT_FIND", implName));
                throw new ELdapException(implName);
            }
            String className = plugin.getClassPath();

            if (Debug.ON)
                Debug.trace("loaded rule className=" + className);

                // Instantiate and init the rule
            IConfigStore mConfig = null;

            try {
                ILdapRule ruleInst = null;

                ruleInst = (ILdapRule)
                        Class.forName(className).newInstance();
                mConfig = c.getSubStore(insName);
                ruleInst.init(this, mConfig);
                ruleInst.setInstanceName(insName);

                // add manager instance to list.
                if (Debug.ON)
                    Debug.trace("ADDING RULE " + insName + "  " + ruleInst);
                mRuleInsts.put(insName, ruleInst);
                log(ILogger.LL_INFO, "rule instance " + 
                    insName + " added");
            } catch (ClassNotFoundException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (IllegalAccessException e) {
                String errMsg = "PublisherProcessor:: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (InstantiationException e) {
                String errMsg = "PublisherProcessor: init()-" + e.toString();

                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_PUBLISHER_INIT_FAILED", e.toString()));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
            } catch (Throwable e) {
                if (mConfig == null) {
                    throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
                }
                mConfig.putString(ILdapRule.PROP_ENABLE, 
                    "false");
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_SKIP_RULE", insName, e.toString()));
                // Let the server continue if it is a
                // mis-configuration. But the instance
                // will be skipped. This give another
                // chance to the user to re-configure
                // the server via console.
            }
            if (Debug.ON)
                Debug.trace("loaded rule instance " + insName + " impl " + implName);
        }

        startup();
        mInited = true;
        log(ILogger.LL_INFO, "publishing initialization done");
    }

    /**
     * Retrieves LDAP connection module.
     * <P>
     *
     * @return LDAP connection instance
     */
    public ILdapConnModule getLdapConnModule() {
        return mLdapConnModule;
    }
	
    public void setLdapConnModule(ILdapConnModule m) {
        mLdapConnModule = (LdapConnModule)m;
    }
		
    /**
     * init ldap connection
     */
    private void initLdapConn(IConfigStore ldapConfig)
        throws EBaseException {
        IConfigStore c = ldapConfig;

        try {
            //c = authConfig.getSubStore(PROP_LDAP_PUBLISH_SUBSTORE);
            if (c != null && c.size() > 0) {
                mLdapConnModule = new LdapConnModule();
                mLdapConnModule.init(this, c);
                CMS.debug("LdapPublishing connection inited");
            } else {
                log(ILogger.LL_FAILURE, 
                    "No Ldap Module configuration found");
                throw new ELdapException(
                  CMS.getUserMessage("CMS_LDAP_NO_LDAP_PUBLISH_CONFIG_FOUND"));
            }

        } catch (ELdapException e) {
            log(ILogger.LL_FAILURE, 
                "Ldap Publishing Module failed with " + e);
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_INIT_LDAP_PUBLISH_MODULE_FAILED", e.toString()));
        }
    }

    public void startup() throws EBaseException {
        CMS.debug("PublisherProcessor: startup()");
        mLdapConfig = mConfig.getSubStore(PROP_LDAP_PUBLISH_SUBSTORE);
        if (mLdapConfig.getBoolean(PROP_ENABLE, false)) {
            CMS.debug("PublisherProcessor: about to initLdapConn");
            initLdapConn(mLdapConfig);
        } else {
            CMS.debug("No LdapPublishing enabled");
        }

        if (mConfig.getBoolean(PROP_ENABLE, false)) {
            mLdapRequestListener = new LdapRequestListener();
            mLdapRequestListener.init(this, mLdapConfig);
            mAuthority.registerRequestListener(mLdapRequestListener);
        }
    }

    public void shutdown() {
        CMS.debug("Shuting down publishing.");
        try {
            if (mLdapConnModule != null) {
                mLdapConnModule.getLdapConnFactory().reset();
            }
            if (mLdapRequestListener != null) {
                //mLdapRequestListener.shutdown();
                mAuthority.removeRequestListener(mLdapRequestListener);
            }
        } catch (Exception e) { 
            // ignore 
        }
    }

    public Hashtable getRulePlugins() {
        return mRulePlugins;
    }

    public Hashtable getRuleInsts() {
        return mRuleInsts;
    }

    public Hashtable getMapperPlugins() {
        return mMapperPlugins;
    }

    public Hashtable getPublisherPlugins() {
        return mPublisherPlugins;
    }

    public Hashtable getMapperInsts() {
        return mMapperInsts;
    }

    public Hashtable getPublisherInsts() {
        return mPublisherInsts;
    }

    //certType can be client,server,ca,crl,smime
    //XXXshould make it static to make it faster
    public Enumeration getRules(String publishingType) {
        Vector rules = new Vector();
        Enumeration e = mRuleInsts.keys();
			
        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();

            if (name == null) {
                if (Debug.ON)
                    Debug.trace("rule name is " + "null");
                return null;
            } else {
                if (Debug.ON)
                    Debug.trace("rule name is " + name);
            }

            //this is the only rule we support now
            LdapRule rule = (LdapRule) (mRuleInsts.get(name));

            if (rule.enabled() && rule.getType().equals(publishingType)) {
                // check if the predicate match
                ILdapExpression exp = rule.getPredicate();

                try {
                    SessionContext sc = SessionContext.getContext();

                    if (exp != null && !exp.evaluate(sc))
                        continue;
                } catch (Exception ex) {
                    // do nothing
                }
                rules.addElement(rule);
                if (Debug.ON)
                    Debug.trace("added rule " + name + " for " + publishingType);
            }
        }
        return rules.elements();
    }

    public Enumeration getRules(String publishingType, IRequest req) {
        if (req == null) {
            return getRules(publishingType);
        }

        Vector rules = new Vector();
        Enumeration e = mRuleInsts.keys();
			
        while (e.hasMoreElements()) {
            String name = (String) e.nextElement();

            if (name == null) {
                if (Debug.ON)
                    Debug.trace("rule name is " + "null");
                return null;
            } else {
                if (Debug.ON)
                    Debug.trace("rule name is " + name);
            }

            //this is the only rule we support now
            LdapRule rule = (LdapRule) (mRuleInsts.get(name));

            if (rule.enabled() && rule.getType().equals(publishingType)) {
                // check if the predicate match
                ILdapExpression exp = rule.getPredicate();

                try {
                    if (exp != null && !exp.evaluate(req))
                        continue;
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                rules.addElement(rule);
                if (Debug.ON)
                    Debug.trace("added rule " + name + " for " + publishingType +
                        " request: " + req.getRequestId());
            }
        }
        return rules.elements();
    }

    /**
     public PublishRuleSet getPublishRuleSet()
     {
     return mRuleSet;
     }
     **/

    public Vector getMapperDefaultParams(String implName) throws
            ELdapException {
        // is this a registered implname?
        MapperPlugin plugin = (MapperPlugin)
            mMapperPlugins.get(implName);

        if (plugin == null) {
            log(ILogger.LL_FAILURE,
		CMS.getLogMessage("CMSCORE_LDAP_MAPPER_NOT_FIND", implName));
            throw new ELdapException(implName);
        }
			
        // XXX can find an instance of this plugin in existing
        // mapper instances to avoid instantiation just for this.
			
        // a temporary instance
        ILdapMapper mapperInst = null;
        String className = plugin.getClassPath();

        try {
            mapperInst = (ILdapMapper)
                    Class.forName(className).newInstance();
            Vector v = mapperInst.getDefaultParams();

            return v;
        } catch (InstantiationException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_MAPPER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (ClassNotFoundException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_MAPPER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (IllegalAccessException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_MAPPER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        }
    }

    public Vector getMapperInstanceParams(String insName) throws
            ELdapException {
        ILdapMapper mapperInst = null;
        MapperProxy proxy = (MapperProxy) mMapperInsts.get(insName);

        if (proxy == null) {
            return null;
        }
        mapperInst = proxy.getMapper();
        if (mapperInst == null) {
            return null;
        }
        Vector v = mapperInst.getInstanceParams();

        return v;
    }

    public Vector getPublisherDefaultParams(String implName) throws
            ELdapException {
        // is this a registered implname?
        PublisherPlugin plugin = (PublisherPlugin)
            mPublisherPlugins.get(implName);

        if (plugin == null) {
            log(ILogger.LL_FAILURE,
		CMS.getLogMessage("CMSCORE_LDAP_PLUGIN_NOT_FIND", implName));
            throw new ELdapException(implName);
        }
			
        // XXX can find an instance of this plugin in existing
        // publisher instantces to avoid instantiation just for this.
			
        // a temporary instance
        ILdapPublisher publisherInst = null;
        String className = plugin.getClassPath();

        try {
            publisherInst = (ILdapPublisher)
                    Class.forName(className).newInstance();
            Vector v = publisherInst.getDefaultParams();

            return v;
        } catch (InstantiationException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_PUBLISHER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (ClassNotFoundException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_PUBLISHER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (IllegalAccessException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_PUBLISHER", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        }
    }

    public boolean isMapperInstanceEnable(String insName) {
        MapperProxy proxy = (MapperProxy)
            mMapperInsts.get(insName);

        if (proxy == null) {
            return false;
        }
        return proxy.isEnable();
    }

    public ILdapMapper getActiveMapperInstance(String insName) {
        MapperProxy proxy = (MapperProxy) mMapperInsts.get(insName);

        if (proxy == null)
            return null;
        if (proxy.isEnable())
            return proxy.getMapper();
        else
            return null;
    }

    public ILdapMapper getMapperInstance(String insName) {
        MapperProxy proxy = (MapperProxy) mMapperInsts.get(insName);

        if (proxy == null)
            return null;
        return proxy.getMapper();
    }

    public boolean isPublisherInstanceEnable(String insName) {
        PublisherProxy proxy = (PublisherProxy)
            mPublisherInsts.get(insName);

        if (proxy == null) {
            return false;
        }
        return proxy.isEnable();
    }

    public ILdapPublisher getActivePublisherInstance(String insName) {
        PublisherProxy proxy = (PublisherProxy)
            mPublisherInsts.get(insName);

        if (proxy == null) {
            return null;
        }
        if (proxy.isEnable())
            return proxy.getPublisher();
        else 
            return null;
    }

    public ILdapPublisher getPublisherInstance(String insName) {
        PublisherProxy proxy = (PublisherProxy)
            mPublisherInsts.get(insName);

        if (proxy == null) {
            return null;
        }
        return proxy.getPublisher();
    }

    public Vector getPublisherInstanceParams(String insName) throws
            ELdapException {
        ILdapPublisher publisherInst = getPublisherInstance(insName);

        if (publisherInst == null) {
            return null;
        }
        Vector v = publisherInst.getInstanceParams();

        return v;
    }

    public Vector getRuleDefaultParams(String implName) throws
            ELdapException {
        // is this a registered implname?
        RulePlugin plugin = (RulePlugin)
            mRulePlugins.get(implName);

        if (plugin == null) {
            log(ILogger.LL_FAILURE,
		CMS.getLogMessage("CMSCORE_LDAP_RULE_NOT_FIND", implName));
            throw new ELdapException(implName);
        }
			
        // XXX can find an instance of this plugin in existing
        // rule instantces to avoid instantiation just for this.
			
        // a temporary instance
        ILdapRule ruleInst = null;
        String className = plugin.getClassPath();

        try {
            ruleInst = (ILdapRule)
                    Class.forName(className).newInstance();
			
            Vector v = ruleInst.getDefaultParams();

            return v;
        } catch (InstantiationException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (ClassNotFoundException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (IllegalAccessException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        }
    }

    public Vector getRuleInstanceParams(String implName) throws
            ELdapException {
        // is this a registered implname?
        RulePlugin plugin = (RulePlugin)
            mRulePlugins.get(implName);

        if (plugin == null) {
            log(ILogger.LL_FAILURE,
		CMS.getLogMessage("CMSCORE_LDAP_RULE_NOT_FIND", implName));
            throw new ELdapException(implName);
        }
		   
        // XXX can find an instance of this plugin in existing
        // rule instantces to avoid instantiation just for this.
		   
        // a temporary instance
        ILdapRule ruleInst = null;
        String className = plugin.getClassPath();

        try {
            ruleInst = (ILdapRule)
                    Class.forName(className).newInstance();
            Vector v = ruleInst.getInstanceParams();
            IConfigStore rc = ruleInst.getConfigStore();

            return v;
        } catch (InstantiationException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (ClassNotFoundException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        } catch (IllegalAccessException e) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_NEW_RULE", e.toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_FAIL_LOAD_CLASS", className));
        }
    }

    /**
     * set published flag - true when published, false when unpublished. 
     * not exist means not published. 
     */
    public void setPublishedFlag(BigInteger serialNo, boolean published) {
        if (!(mAuthority instanceof ICertificateAuthority)) 
            return;
        ICertificateAuthority ca = (ICertificateAuthority) mAuthority;

        try {
            ICertificateRepository certdb = (ICertificateRepository) ca.getCertificateRepository();
            ICertRecord certRec = (ICertRecord) certdb.readCertificateRecord(serialNo);
            MetaInfo metaInfo = certRec.getMetaInfo();

            if (metaInfo == null) {
                metaInfo = new MetaInfo();
            }
            metaInfo.set(
                CertRecord.META_LDAPPUBLISH, String.valueOf(published));
            ModificationSet modSet = new ModificationSet();

            modSet.add(ICertRecord.ATTR_META_INFO, 
                Modification.MOD_REPLACE, metaInfo);
            certdb.modifyCertificateRecord(serialNo, modSet);
        } catch (EBaseException e) {
            // not fatal. just log warning.
            log(ILogger.LL_WARN, 
                "Cannot mark cert 0x" + serialNo.toString(16) + " published as " + published +
                " in the ldap directory. Cert Record not found. Error: " +
                e.toString() + 
                " Don't be alarmed if it's a subordinate ca or clone's ca siging cert. Otherwise your internal db may be corrupted.");
        }
    }

    /**
     * Publish ca cert, UpdateDir.java, jobs, request listeners
     */
    public void publishCACert(X509Certificate cert)
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;

            // get mapper and publisher for cert type.
        Enumeration rules = getRules(PROP_LOCAL_CA);

        if (rules == null || !rules.hasMoreElements()) {
            if (isClone()) {
                log(ILogger.LL_WARN, "No rule is found for publishing: " + PROP_LOCAL_CA + " in this clone.");
                return;
            } else {
                Debug.trace(CMS.getLogMessage("CMSCORE_LDAP_NO_RULE_FOUND", PROP_LOCAL_CA));
                //log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_RULE_FOUND", PROP_LOCAL_CA));
                //throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED", PROP_LOCAL_CA));
                return;
            }
        }
        while (rules.hasMoreElements()) {
            LdapRule rule = (LdapRule) rules.nextElement();

            if( rule == null ) {
                CMS.debug( "PublisherProcessor::publishCACert() - "
                         + "rule is null!" );
                throw new ELdapException( "rule is null" );
            }

            log(ILogger.LL_INFO, "publish certificate type=" + PROP_LOCAL_CA +
                " rule=" + rule.getInstanceName() + " publisher=" + 
                rule.getPublisher());

            try {
                ILdapMapper mapper = null;

                String mapperName = rule.getMapper();

                if (mapperName != null &&
                    !mapperName.trim().equals("")) {
                    mapper = getActiveMapperInstance(mapperName);
                }

                publishNow(mapper, getActivePublisherInstance(rule.getPublisher()), null/* NO REQUEsT */, cert);
                log(ILogger.LL_INFO, "published certificate using rule=" + 
                    rule.getInstanceName());
            } catch (Exception e) {
                // continue publishing even publisher has errors
                //log(ILogger.LL_WARN, e.toString());
                error = true;
                errorRule = errorRule + " " + rule.getInstanceName() +
                        " error:" + e.toString();
            }
        }
        // set the ldap published flag.
        if (!error) {
            setPublishedFlag(cert.getSerialNumber(), true);
        } else {
            throw new
                ELdapException(CMS.getUserMessage("CMS_LDAP_PUBLISH_FAILED", errorRule));
        }
    }

    /**
     * This function is never called. CMS does not unpublish
     * CA certificate.
     */
    public void unpublishCACert(X509Certificate cert)
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;

            // get mapper and publisher for cert type.
        Enumeration rules = getRules(PROP_LOCAL_CA);

        if (rules == null || !rules.hasMoreElements()) {
            if (isClone()) {
                log(ILogger.LL_WARN, "No rule is found for unpublishing: " + PROP_LOCAL_CA + " in this clone.");
                return;
            } else {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_UNPUBLISHING_RULE_FOUND", PROP_LOCAL_CA));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED", PROP_LOCAL_CA));
            }
        }

        while (rules.hasMoreElements()) {
            LdapRule rule = (LdapRule) rules.nextElement();

            if( rule == null ) {
                CMS.debug( "PublisherProcessor::unpublishCACert() - "
                         + "rule is null!" );
                throw new ELdapException( "rule is null" );
            }

            try {
                log(ILogger.LL_INFO, "unpublish certificate type=" +
                    PROP_LOCAL_CA + " rule=" + rule.getInstanceName() + 
                    " publisher=" + rule.getPublisher());

                ILdapMapper mapper = null;

                String mapperName = rule.getMapper();

                if (mapperName != null &&
                    !mapperName.trim().equals("")) {
                    mapper = getActiveMapperInstance(mapperName);
                }

                unpublishNow(mapper, getActivePublisherInstance(rule.getPublisher()), null/* NO REQUEST */, cert);
                log(ILogger.LL_INFO, "unpublished certificate using rule=" + 
                    rule.getInstanceName());
            } catch (Exception e) {
                // continue publishing even publisher has errors
                //log(ILogger.LL_WARN, e.toString());
                error = true;
                errorRule = errorRule + " " + rule.getInstanceName();
            }
        }

        // set the ldap published flag.
        if (!error) {
            setPublishedFlag(cert.getSerialNumber(), false);
        } else {
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_UNPUBLISH_FAILED", errorRule));
        }
    }

    /**
     * Publish crossCertificatePair
     */
    public void publishXCertPair(byte[] pair)
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;
		CMS.debug("PublisherProcessor: in publishXCertPair()");

            // get mapper and publisher for cert type.
        Enumeration rules = getRules(PROP_XCERT);

        if (rules == null || !rules.hasMoreElements()) {
            if (isClone()) {
                log(ILogger.LL_WARN, "No rule is found for publishing: " + PROP_LOCAL_CA + " in this clone.");
                return;
            } else {
                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_RULE_FOUND", PROP_XCERT));
                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED", PROP_XCERT));
            }
        }
        while (rules.hasMoreElements()) {
            LdapRule rule = (LdapRule) rules.nextElement();

            if( rule == null ) {
                CMS.debug( "PublisherProcessor::publishXCertPair() - "
                         + "rule is null!" );
                throw new ELdapException( "rule is null" );
            }

            log(ILogger.LL_INFO, "publish certificate type=" + PROP_XCERT +
                " rule=" + rule.getInstanceName() + " publisher=" + 
                rule.getPublisher());
            try {
                ILdapMapper mapper = null;

                String mapperName = rule.getMapper();

                if (mapperName != null &&
                    !mapperName.trim().equals("")) {
                    mapper = getActiveMapperInstance(mapperName);
                }

                publishNow(mapper, getActivePublisherInstance(rule.getPublisher()), null/* NO REQUEsT */, pair);
                log(ILogger.LL_INFO, "published Xcertificates using rule=" + 
                    rule.getInstanceName());
            } catch (Exception e) {
                // continue publishing even publisher has errors
                //log(ILogger.LL_WARN, e.toString());
                error = true;
                errorRule = errorRule + " " + rule.getInstanceName() +
                        " error:" + e.toString();
            }
        }
    }

    /**
     * Publishs regular user certificate based on the criteria
     * set in the request.
     */
    public void publishCert(X509Certificate cert, IRequest req)
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;

            // get mapper and publisher for cert type.
        Enumeration rules = getRules("certs", req);

         // Bugscape  #52306  -  Remove superfluous log messages on failure
        if (rules == null || !rules.hasMoreElements()) {
                  return;
        }
        while (rules.hasMoreElements()) {
            LdapRule rule = (LdapRule) rules.nextElement();

            try {
                log(ILogger.LL_INFO, 
                    "publish certificate (with request) type=" + 
                    "certs" + " rule=" + rule.getInstanceName() + 
                    " publisher=" + rule.getPublisher());
                ILdapPublisher p = getActivePublisherInstance(rule.getPublisher());
                ILdapMapper m = null;
                String mapperName = rule.getMapper();

                if (mapperName != null) {
                    m = getActiveMapperInstance(mapperName);
                }
                publishNow(m, p, req, cert);
                log(ILogger.LL_INFO, "published certificate using rule=" + 
                    rule.getInstanceName());
            } catch (Exception e) {
                // continue publishing even publisher has errors
                //log(ILogger.LL_WARN, e.toString());
                error = true;
                errorRule = errorRule + " " + rule.getInstanceName();
            }
        }
        // set the ldap published flag.
        if (!error) {
            setPublishedFlag(cert.getSerialNumber(), true);
        } else {
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_PUBLISH_FAILED", errorRule));
        }
    }

    /**
     * Unpublish user certificate. This is used by 
     * UnpublishExpiredJob.
     */
    public void unpublishCert(X509Certificate cert, IRequest req)
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;

            // get mapper and publisher for cert type.
        Enumeration rules = getRules("certs", req);

        if (rules == null || !rules.hasMoreElements()) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_UNPUBLISHING_RULE_FOUND_FOR_REQUEST", "certs", req.getRequestId().toString()));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED",
                    req.getRequestId().toString()));
        }

        while (rules.hasMoreElements()) {
            LdapRule rule = (LdapRule) rules.nextElement();

            if( rule == null ) {
                CMS.debug( "PublisherProcessor::unpublishCert() - "
                         + "rule is null!" );
                throw new ELdapException( "rule is null" );
            }

            try {
                log(ILogger.LL_INFO, 
                    "unpublish certificate (with request) type=" + 
                    "certs" + " rule=" + rule.getInstanceName() + 
                    " publisher=" + rule.getPublisher());

                ILdapMapper mapper = null;

                String mapperName = rule.getMapper();

                if (mapperName != null &&
                    !mapperName.trim().equals("")) {
                    mapper = getActiveMapperInstance(mapperName);
                }

                unpublishNow(mapper, getActivePublisherInstance(rule.getPublisher()),
                    req, cert);
                log(ILogger.LL_INFO, "unpublished certificate using rule=" + 
                    rule.getInstanceName());
            } catch (Exception e) {
                // continue publishing even publisher has errors
                //log(ILogger.LL_WARN, e.toString());
                error = true;
                errorRule = errorRule + " " + rule.getInstanceName();
            }
        }

        // set the ldap published flag.
        if (!error) {
            setPublishedFlag(cert.getSerialNumber(), false);
        } else {
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_UNPUBLISH_FAILED", errorRule));
        }
    }

    /**
     * publishes a crl by mapping the issuer name in the crl to an entry
     * and publishing it there. entry must be a certificate authority.
     * Note that this is used by cmsgateway/cert/UpdateDir.java
     */
    public void publishCRL(X509CRLImpl crl, String crlIssuingPointId) 
        throws ELdapException {
        boolean error = false;
        String errorRule = "";


        if (!enabled())
            return;
        ILdapMapper mapper = null;
        ILdapPublisher publisher = null;

        // get mapper and publisher for cert type.
        Enumeration rules = getRules(PROP_LOCAL_CRL);

        if (rules == null || !rules.hasMoreElements()) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_RULE_FOR_CRL"));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED",
                    PROP_LOCAL_CRL));
        }

        LDAPConnection conn = null;
        String dn = null;

        try {
            if (mLdapConnModule != null) {
                conn = mLdapConnModule.getConn();
            }
            while (rules.hasMoreElements()) {
                mapper = null;
                dn = null;
                String result = null;
                LdapRule rule = (LdapRule) rules.nextElement();

                log(ILogger.LL_INFO, "publish crl rule=" + 
                    rule.getInstanceName() + " publisher=" + 
                    rule.getPublisher());
                try {
                    String mapperName = rule.getMapper();

                    if (mapperName != null &&
                        !mapperName.trim().equals("")) {
                        mapper = getActiveMapperInstance(mapperName);
                    }
                    if (mapper == null || mapper.getImplName().equals("NoMap")) {
                        dn = ((X500Name) crl.getIssuerDN()).toLdapDNString();
                    }else {
							
                        result = ((ILdapMapper) mapper).map(conn, crl);
                        dn = result;
                        if (!mCreateOwnDNEntry) {
                            if (dn == null) {  
                                log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_MAPPER_NOT_MAP", rule.getMapper()));
                                throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH", 
                                    crl.getIssuerDN().toString()));
                          
                            }
                        }
                    }
                    publisher = getActivePublisherInstance(rule.getPublisher());
                    if (publisher != null) {
                        if(publisher instanceof com.netscape.cms.publish.publishers.FileBasedPublisher)
                        ((com.netscape.cms.publish.publishers.FileBasedPublisher)publisher).setIssuingPointId(crlIssuingPointId);
                        publisher.publish(conn, dn, crl);
                        log(ILogger.LL_INFO, "published crl using rule=" + rule.getInstanceName());
                    }
                    // continue publishing even publisher has errors
                }catch (Exception e) {
                    //e.printStackTrace();
                    CMS.debug(
                        "Error publishing CRL to " + dn + ": " + e);
                    error = true;
                    errorRule = errorRule + " " + rule.getInstanceName();
                }
            }
        }catch (ELdapException e) {
            //e.printStackTrace();
            CMS.debug(
                "Error publishing CRL to " + dn + ": " + e);
            throw e;
        } finally {
            if (conn != null) {
                mLdapConnModule.returnConn(conn);
            }
        }
        if (error)
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_PUBLISH_FAILED", errorRule));
    }

    /**
     * publishes a crl by mapping the issuer name in the crl to an entry
     * and publishing it there. entry must be a certificate authority.
     */
    public void publishCRL(String dn, X509CRL crl) 
        throws ELdapException {
        boolean error = false;
        String errorRule = "";

        if (!enabled())
            return;
            // get mapper and publisher for cert type.
        Enumeration rules = getRules(PROP_LOCAL_CRL);

        if (rules == null || !rules.hasMoreElements()) {
            log(ILogger.LL_FAILURE, CMS.getLogMessage("CMSCORE_LDAP_NO_RULE_FOR_CRL"));
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_RULE_MATCHED",
                    PROP_LOCAL_CRL));
        }

        LDAPConnection conn = null;
        ILdapPublisher publisher = null;

        try {
            if (mLdapConnModule != null) {
                conn = mLdapConnModule.getConn();
            }
            while (rules.hasMoreElements()) {
                LdapRule rule = (LdapRule) rules.nextElement();

                log(ILogger.LL_INFO, "publish crl dn=" + dn + " rule=" +
                    rule.getInstanceName() + " publisher=" + 
                    rule.getPublisher());
                try {
                    publisher = getActivePublisherInstance(rule.getPublisher());
                    if (publisher != null) {
                        publisher.publish(conn, dn, crl);
                        log(ILogger.LL_INFO, "published crl using rule=" + rule.getInstanceName());
                    }
                }catch (Exception e) {
                    CMS.debug(
                        "Error publishing CRL to " + dn + ": " + e.toString());
                    error = true;
                    errorRule = errorRule + " " + rule.getInstanceName();
                } 
            }
        } catch (ELdapException e) {
            CMS.debug(
                "Error publishing CRL to " + dn + ": " + e.toString());
            throw e;
        } finally {
            if (conn != null) {
                mLdapConnModule.returnConn(conn);
            }
        }
        if (error)
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_PUBLISH_FAILED", errorRule));
    }

    private void publishNow(ILdapMapper mapper, ILdapPublisher publisher,
        IRequest r, Object obj) throws ELdapException {
        if (!enabled())
            return;
CMS.debug("PublisherProcessor: in publishNow()");
        LDAPConnection conn = null;

        try {
            Object dirdn = null;

            if (mapper != null) {
                LdapCertMapResult result = null;

                if (mLdapConnModule != null) {
                    conn = mLdapConnModule.getConn();
                }
                try {
                    if ((mapper instanceof com.netscape.cms.publish.mappers.LdapCertSubjMap) &&
                         ((com.netscape.cms.publish.mappers.LdapCertSubjMap)mapper).useAllEntries()) {
                        dirdn = ((com.netscape.cms.publish.mappers.LdapCertSubjMap)mapper).mapAll(conn, r, obj); 
                    } else {
                       dirdn = mapper.map(conn, r, obj); 
                    }
                } catch (Throwable e1) {
                    CMS.debug("Error mapping: mapper=" + mapper + " error=" + e1.toString());
                    throw e1;
                }
            }

            X509Certificate cert = (X509Certificate) obj;

            try {
                if (dirdn instanceof String) {
                    publisher.publish(conn, (String)dirdn, cert);
                } else if (dirdn instanceof Vector) {
                    int n = ((Vector)dirdn).size();
                    for (int i = 0; i < n; i++) {
                        publisher.publish(conn, (String)(((Vector)dirdn).elementAt(i)), cert);
                    }
                }
            } catch (Throwable e1) {
                CMS.debug("Error publishing: publisher=" + publisher + " error=" + e1.toString());
                throw e1;
            }
            log(ILogger.LL_INFO, "published certificate serial number: 0x" + 
                cert.getSerialNumber().toString(16));
        } catch (ELdapException e) {
            throw e;
        } catch (Throwable e) {
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH", e.toString())); 
        } finally {
            if (conn != null) {
                mLdapConnModule.returnConn(conn);
            }
        }
    }

	// for crosscerts
    private void publishNow(ILdapMapper mapper, ILdapPublisher publisher,
        IRequest r, byte[] bytes) throws ELdapException {
        if (!enabled())
            return;
		CMS.debug("PublisherProcessor: in publishNow() for xcerts");

		// use ca cert publishing map and rule
        ICertificateAuthority ca = (ICertificateAuthority) mAuthority;
		X509Certificate caCert = (X509Certificate) ca.getCACert();

        LDAPConnection conn = null;

        try {
            String dirdn = null;

            if (mapper != null) {
                LdapCertMapResult result = null;

                if (mLdapConnModule != null) {
                    conn = mLdapConnModule.getConn();
                }
                try {
                    dirdn = mapper.map(conn, r, (Object) caCert); 
		CMS.debug("PublisherProcessor: dirdn="+dirdn);

                } catch (Throwable e1) {
                    CMS.debug("Error mapping: mapper=" + mapper + " error=" + e1.toString());
                    throw e1;
                }
            }

            try {
		CMS.debug("PublisherProcessor: publisher impl name="+publisher.getImplName());

                publisher.publish(conn, dirdn, bytes);
            } catch (Throwable e1) {
                CMS.debug("Error publishing: publisher=" + publisher + " error=" + e1.toString());
                throw e1;
            }
            log(ILogger.LL_INFO, "published crossCertPair");
        } catch (ELdapException e) {
            throw e;
        } catch (Throwable e) {
            throw new ELdapException(CMS.getUserMessage("CMS_LDAP_NO_MATCH", e.toString())); 
        } finally {
            if (conn != null) {
                mLdapConnModule.returnConn(conn);
            }
        }
    }

    private void unpublishNow(ILdapMapper mapper, ILdapPublisher publisher,
        IRequest r, Object obj) throws ELdapException {
        if (!enabled())
            return;
        LDAPConnection conn = null;

        try {
            String dirdn = null;

            if (mapper != null) {
                LdapCertMapResult result = null;

                if (mLdapConnModule != null) {
                    conn = mLdapConnModule.getConn();
                }
                dirdn = mapper.map(conn, r, obj); 
            }
            X509Certificate cert = (X509Certificate) obj;

            publisher.unpublish(conn, dirdn, cert);
            log(ILogger.LL_INFO, "unpublished certificate serial number: 0x" + 
                cert.getSerialNumber().toString(16));
        } catch (ELdapException e) {
            throw e;
        } finally {
            if (conn != null) {
                mLdapConnModule.returnConn(conn);
            }
        }
    }

    public boolean ldapEnabled() {
        try {
            if (mInited)
                return mLdapConfig.getBoolean(PROP_ENABLE, false);
            else
                return false;
        } catch (EBaseException e) {
            return false;
        }
    }

    public boolean enabled() {
        try {
            if (mInited)
                return mConfig.getBoolean(PROP_ENABLE, false);
            else
                return false;
        } catch (EBaseException e) {
            return false;
        }
    }

    public ISubsystem getAuthority() {
        return mAuthority;
    }

    public boolean isClone() {
        if ((mAuthority instanceof ICertificateAuthority) && 
            ((ICertificateAuthority) mAuthority).isClone())
            return true;
        else
            return false;
    }

    /**
     * logs an entry in the log file.
     */
    public void log(int level, String msg) {
        if (mLogger == null)
            return;
        mLogger.log(ILogger.EV_SYSTEM, 
            ILogger.S_LDAP, level, "Publishing: " + msg);
    }
}
