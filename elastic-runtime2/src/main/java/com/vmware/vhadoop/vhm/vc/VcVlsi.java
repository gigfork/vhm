package com.vmware.vhadoop.vhm.vc;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLException;

import com.vmware.vhadoop.api.vhm.VCActions;
import com.vmware.vhadoop.api.vhm.events.ClusterStateChangeEvent.VMEventData;
import com.vmware.vhadoop.util.CompoundStatus;
import com.vmware.vhadoop.util.ThreadLocalCompoundStatus;
import com.vmware.vim.binding.impl.vmodl.TypeNameImpl;
import com.vmware.vim.binding.vim.Folder;
import com.vmware.vim.binding.vim.ServiceInstance;
import com.vmware.vim.binding.vim.ServiceInstanceContent;
import com.vmware.vim.binding.vim.SessionManager;
import com.vmware.vim.binding.vim.Task;
import com.vmware.vim.binding.vim.TaskInfo;
import com.vmware.vim.binding.vim.VirtualMachine;
import com.vmware.vim.binding.vim.VirtualMachine.PowerState;
import com.vmware.vim.binding.vim.fault.InvalidLocale;
import com.vmware.vim.binding.vim.fault.InvalidLogin;
import com.vmware.vim.binding.vim.fault.NoClientCertificate;
import com.vmware.vim.binding.vim.fault.NoHost;
import com.vmware.vim.binding.vim.fault.NotFound;
import com.vmware.vim.binding.vim.fault.NotSupportedHost;
import com.vmware.vim.binding.vim.fault.TooManyTickets;
import com.vmware.vim.binding.vim.option.OptionValue;
import com.vmware.vim.binding.vim.version.version8;
import com.vmware.vim.binding.vim.view.ContainerView;
import com.vmware.vim.binding.vim.view.ViewManager;
import com.vmware.vim.binding.vmodl.DynamicProperty;
import com.vmware.vim.binding.vmodl.ManagedObjectReference;
import com.vmware.vim.binding.vmodl.TypeName;
import com.vmware.vim.binding.vmodl.query.InvalidProperty;
import com.vmware.vim.binding.vmodl.query.PropertyCollector;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.Change;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.Filter;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.FilterSpec;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.FilterUpdate;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.ObjectContent;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.ObjectSpec;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.ObjectUpdate;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.ObjectUpdate.Kind;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.PropertySpec;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.RetrieveOptions;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.RetrieveResult;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.SelectionSpec;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.TraversalSpec;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.UpdateSet;
import com.vmware.vim.binding.vmodl.query.PropertyCollector.WaitOptions;
import com.vmware.vim.vmomi.client.Client;
import com.vmware.vim.vmomi.client.exception.ConnectionException;
import com.vmware.vim.vmomi.client.http.HttpClientConfiguration;
import com.vmware.vim.vmomi.client.http.ThumbprintVerifier;
import com.vmware.vim.vmomi.client.http.impl.HttpConfigurationImpl;
import com.vmware.vim.vmomi.core.types.VmodlContext;

public class VcVlsi {

   private static final int SESSION_TIME_OUT = 120000;
   
   private static final Logger _log = Logger.getLogger("VcVlsi");
   private static final VmodlContext vmodlContext = VmodlContext.initContext(new String[] { "com.vmware.vim.binding.vim" });
   private Client defaultClient;
   private String vcThumbprint = null;

   private static final String VC_PROP_VM_NAME = "name";
   private static final String VC_PROP_VM_EXTRA_CONFIG = "config.extraConfig";
   private static final String VC_PROP_VM_UUID = "config.uuid";
   private static final String VC_PROP_VM_NUM_CPU = "config.hardware.numCPU";
   private static final String VC_PROP_VM_POWER_STATE = "runtime.powerState";
   private static final String VC_PROP_VM_HOST = "runtime.host";
   private static final String VC_PROP_VM_GUEST_IP = "guest.ipAddress";
   private static final String VC_PROP_VM_GUEST_HOSTNAME = "guest.hostName";
   
   private static final String VC_MOREF_TYPE_TASK = "Task";
   private static final String VC_MOREF_TYPE_VM = "VirtualMachine";
   private static final String VC_MOREF_TYPE_FOLDER = "Folder";
   private static final String VC_MOREF_TYPE_CONTAINER_VIEW = "ContainerView";
   private static final TypeNameImpl typeTask = new TypeNameImpl(VC_MOREF_TYPE_TASK);
   private static final TypeNameImpl typeVM = new TypeNameImpl(VC_MOREF_TYPE_VM);
   private static final TypeNameImpl typeFolder = new TypeNameImpl(VC_MOREF_TYPE_FOLDER);
   private static final TypeNameImpl typeContainerView = new TypeNameImpl(VC_MOREF_TYPE_CONTAINER_VIEW);

   private static final int propertyCollectorTimeout = 300;

   private static final String VHM_EXTRA_CONFIG_PREFIX = "vhmInfo.";
   private static final String VHM_EXTRA_CONFIG_UUID = "vhmInfo.serengeti.uuid";
   private static final String VHM_EXTRA_CONFIG_MASTER_UUID = "vhmInfo.masterVM.uuid";
   private static final String VHM_EXTRA_CONFIG_MASTER_MOREF = "vhmInfo.masterVM.moid";
   private static final String VHM_EXTRA_CONFIG_ELASTIC = "vhmInfo.elastic";
   private static final String VHM_EXTRA_CONFIG_AUTOMATION_ENABLE = "vhmInfo.vhm.enable";
   private static final String VHM_EXTRA_CONFIG_AUTOMATION_MIN_INSTANCES = "vhmInfo.min.computeNodeNum";
   private static final String VHM_EXTRA_CONFIG_JOB_TRACKER_PORT = "vhmInfo.jobtracker.port";

   private static final String TASK_INFO_STATE = "info.state";
      
   private ThreadLocalCompoundStatus _threadLocalStatus;
   
   void setThreadLocalCompoundStatus(ThreadLocalCompoundStatus tlcs) {
      _threadLocalStatus = tlcs;
   }
   
   private CompoundStatus getCompoundStatus() {
      if (_threadLocalStatus == null) {
         return new CompoundStatus("DUMMY_STATUS");
      }
      return _threadLocalStatus.get();
   }

   private ThumbprintVerifier getThumbprintVerifier() {
      return new ThumbprintVerifier() {
         @Override
         public Result verify(String thumbprint) {
            if (vcThumbprint == null) {
               return Result.MATCH;
            } else if (thumbprint.equalsIgnoreCase(vcThumbprint)) {
               return Result.MATCH;
            } else {
               _log.log(Level.SEVERE, "Thumbprint mismatch: remote=" + thumbprint);
               return Result.MISMATCH;
            }
         }

         @Override
         public void onSuccess(X509Certificate[] chain, String thumbprint,
               Result verifyResult, boolean trustedChain,
               boolean verifiedAssertions) throws SSLException {
         }
      };
   }

   private ServiceInstance getServiceInstance(Client vcClient) {
      ManagedObjectReference svcRef = new ManagedObjectReference();
      svcRef.setType("ServiceInstance");
      svcRef.setValue("ServiceInstance");
      return vcClient.createStub(ServiceInstance.class, svcRef);
   }

   private ServiceInstanceContent getServiceInstanceContent(Client vcClient) {
      ServiceInstance svc = getServiceInstance(vcClient);
      return svc.retrieveContent();
   }
   
   /*
    * Create a temporary connection to VC to login using extension certificate via sdkTunnel,
    * and get the session ticket to use for the normal connection.
    */
   private String getSessionTicket(String vcIP, String keyStoreFile, String keyStorePwd, String vcExtKey)
         throws URISyntaxException, KeyStoreException, NoSuchAlgorithmException, CertificateException, FileNotFoundException, IOException, InvalidLogin, InvalidLocale, NoClientCertificate, NoHost, NotSupportedHost, NotFound, TooManyTickets {

      URI uri = new URI("https://sdkTunnel:8089/sdk/vimService"); 
      KeyStore keyStore = KeyStore.getInstance("JKS");
      keyStore.load(new FileInputStream(keyStoreFile), keyStorePwd.toCharArray());

      HttpConfigurationImpl httpConfig = new HttpConfigurationImpl();
      httpConfig.setKeyStore(keyStore);
      httpConfig.setDefaultProxy(vcIP, 80, "http");
      httpConfig.getKeyStoreConfig().setKeyAlias(keyStore.aliases().nextElement());
      httpConfig.getKeyStoreConfig().setKeyPassword(keyStorePwd);
      
      httpConfig.setTimeoutMs(SESSION_TIME_OUT);
      httpConfig.setThumbprintVerifier(getThumbprintVerifier());

      HttpClientConfiguration clientConfig = HttpClientConfiguration.Factory.newInstance();
      clientConfig.setHttpConfiguration(httpConfig);
      Client newClient = Client.Factory.createClient(uri, version8.class, clientConfig);
      
      ServiceInstanceContent sic = getServiceInstanceContent(newClient);
      SessionManager sm = newClient.createStub(SessionManager.class, sic.getSessionManager());
      sm.loginExtensionByCertificate(vcExtKey, null);
      String ticket = sm.acquireSessionTicket(null);
      
      return ticket;
   }

   private String getCloneTicket() {
      ServiceInstanceContent sic = getServiceInstanceContent(defaultClient);
      SessionManager sm = defaultClient.createStub(SessionManager.class, sic.getSessionManager());
      return sm.acquireCloneTicket();
   }
   
   public Client connect(VcCredentials credentials, boolean useKey, boolean cloneSession) 
         throws Exception {
      vcThumbprint = credentials.vcThumbprint;
      String sessionTicket = null;
      
      if (cloneSession) {
         sessionTicket = getCloneTicket();
      } else if (useKey) {
         sessionTicket = getSessionTicket(credentials.vcIP, credentials.keyStoreFile, credentials.keyStorePwd, credentials.vcExtKey);
      }
      
      URI uri = new URI("https://"+credentials.vcIP+":443/sdk");

      // each VLSI call consumes an executor thread for the duration of the blocking call 
      ThreadPoolExecutor executor =
            new ThreadPoolExecutor(1,  // core pool size
                  4,  // max pool size
                  10, TimeUnit.SECONDS, // max thread idle time
                  new LinkedBlockingQueue<Runnable>()); // work queue

      HttpConfigurationImpl httpConfig = new HttpConfigurationImpl();
      httpConfig.setTimeoutMs(SESSION_TIME_OUT);
      httpConfig.setThumbprintVerifier(getThumbprintVerifier());

      HttpClientConfiguration clientConfig = HttpClientConfiguration.Factory.newInstance();
      clientConfig.setHttpConfiguration(httpConfig);
      clientConfig.setExecutor(executor);

      Client newClient = Client.Factory.createClient(uri, version8.class, clientConfig);

      ServiceInstanceContent sic = getServiceInstanceContent(newClient);
      SessionManager sm = newClient.createStub(SessionManager.class, sic.getSessionManager());

      if (cloneSession) {
         sm.cloneSession(sessionTicket);
      } else {
         // set this as the default client
         defaultClient = newClient;
         if (useKey) {
            sm.loginBySessionTicket(sessionTicket);
         } else {
            sm.login(credentials.user, credentials.password, null);
         }
      }
      
      return newClient;
   }

   @SuppressWarnings("finally")
   public boolean testConnection() {
      // Test the operation of the current connection using the standard simple call for this purpose.
      Calendar vcTime = null;
      try {
         ServiceInstance si = getServiceInstance(defaultClient);
         vcTime = si.currentTime();
      } finally {
         if (vcTime == null) {
            _log.log(Level.SEVERE, "testConnection found VC connection dropped; caller will reconnect");
         }
         return vcTime != null;
      }
   }


   private Folder getRootFolder() {
      ServiceInstanceContent sic = getServiceInstanceContent(defaultClient);
      return defaultClient.createStub(Folder.class, sic.getRootFolder());
   }

   /**
    * Property filters are used a lot when querying the JAX-WS API for information about VC entities
    * The code is pretty ugly, so it makes sense to encapsulate it in a utility class.
    * The class is dual-purpose - it can be created with either constructor depending on the need.
    * Properties can then be added to the filter and once that's completed, 
    * retrieveProperties() or getPropertyCollector() can be called, depending on the requirement
    * 
    */
   public class PropertyFilter {
      PropertyCollector _propertyCollector;
      Filter _filter;
      PropertySpec _propertySpec;
      FilterSpec _propertyFilterSpec;
      boolean _initialized = false;
      ContainerView _containerView;
      Client _vcClient;

      public PropertyFilter(Client vcClient, ContainerView containerView, TypeName type) {
         _vcClient = vcClient;
         _containerView = containerView;
         TraversalSpec tSpec = getTraversalSpecFromView();
         ObjectSpec objectSpec = new ObjectSpec();
         objectSpec.setObj(containerView._getRef());
         objectSpec.setSelectSet(new SelectionSpec[] {tSpec});
         _propertyFilterSpec = new FilterSpec();
         _propertyFilterSpec.setObjectSet(new ObjectSpec[] {objectSpec});
         _propertySpec = new PropertySpec();
         _propertySpec.setAll(Boolean.FALSE);
         _propertySpec.setType(type);
      }

      public PropertyFilter(Client vcClient, Task task) {
         _vcClient = vcClient;
         ObjectSpec objectSpec = new ObjectSpec();
         objectSpec.setObj(task._getRef());
         objectSpec.setSkip(Boolean.FALSE);
         _propertyFilterSpec = new FilterSpec();
         _propertyFilterSpec.setObjectSet(new ObjectSpec[] {objectSpec});
         _propertySpec = new PropertySpec();
         _propertySpec.setType(typeTask);
      }

      private TraversalSpec getTraversalSpecFromView() {
         // Create a traversal spec that starts from the ListView object
         // and traverses to its "view" property containing the managed object references.

         TraversalSpec viewToObject = new TraversalSpec();
         viewToObject.setName("viewToObject");
         viewToObject.setType(typeContainerView);
         viewToObject.setPath("view");
         viewToObject.setSkip(false);

         return viewToObject;
      }

      public void setPropToFilter(String property) throws InvalidProperty {
         _propertySpec.setPathSet(new String[] {property});
         init();
      }

      public void setPropsToFilter(String[] properties) throws InvalidProperty {
         _propertySpec.setPathSet(properties);
         init();
      }

      private void init() throws InvalidProperty  {
         if (!_initialized) {
            _propertyFilterSpec.setPropSet(new PropertySpec [] {_propertySpec});

            ServiceInstanceContent sic = getServiceInstanceContent(_vcClient);
            _propertyCollector = _vcClient.createStub(PropertyCollector.class, sic.getPropertyCollector());

            _filter = _vcClient.createStub(Filter.class, _propertyCollector.createFilter(_propertyFilterSpec, true));
         }
         _initialized = true;
      }

      public PropertyCollector getPropertyCollector() throws InvalidProperty  {
         init();
         return _propertyCollector;
      }

      public RetrieveResult retrieveProperties() throws InvalidProperty {
         init();
         RetrieveOptions retrieveOptions = new RetrieveOptions();

         return _propertyCollector.retrievePropertiesEx(new FilterSpec[] {_propertyFilterSpec}, retrieveOptions);
      }

      public RetrieveResult continueRetrieve(String token) throws InvalidProperty {
         return _propertyCollector.continueRetrievePropertiesEx(token);
      }
      
      public void cleanup() {
         if (_filter != null) {
            _filter.destroy();
         }
         if (_containerView != null) {
            _containerView.destroy();
         }
      }

   }

   private List<ManagedObjectReference> findObjectsInFolder(Folder baseFolder, TypeName type, String restrictToName) 
         throws InvalidProperty {
      List<ManagedObjectReference> resultRefs = new ArrayList<ManagedObjectReference>();
      ServiceInstanceContent sic = getServiceInstanceContent(defaultClient);
      
      ViewManager viewMgr = defaultClient.createStub(ViewManager.class, sic.getViewManager());
      ContainerView cView = defaultClient.createStub(ContainerView.class,
            viewMgr.createContainerView(baseFolder._getRef(), new TypeName[] {type}, true));

      PropertyFilter propFilter = new PropertyFilter(defaultClient, cView, type);
      propFilter.setPropToFilter("name");

      RetrieveResult rr = propFilter.retrieveProperties();
      boolean done = false;

      while ((rr != null) && !done) {
         ObjectContent[] oca = rr.getObjects();

         for (ObjectContent oc : oca) {
            if (restrictToName == null) {
               resultRefs.add(oc.getObj());
            } else {
               // filter out by name
               DynamicProperty[] dps = oc.getPropSet();
               for (DynamicProperty dp : dps) {
                  if (dp.getName().equals("name") && dp.getVal().equals(restrictToName)) {
                     resultRefs.add(oc.getObj());
                     done = true;
                     break;
                  }
               }
            }
         }
         if (rr.getToken() == null) {
            done = true;
         } else if (!done) {
            // get the next batch of results from VC
            rr = propFilter.continueRetrieve(rr.getToken());
         }
      }  
      
      propFilter.cleanup();
      return resultRefs;
   }

   private Folder getFolderForName(Folder baseFolder, String restrictToName) throws InvalidProperty {
      if (baseFolder == null) {
         baseFolder = getRootFolder();
      }
      List<ManagedObjectReference> refs = findObjectsInFolder(baseFolder, typeFolder, restrictToName);
      if (refs.size() > 0) {
         return defaultClient.createStub(Folder.class, refs.get(0));
      }
      return null;
   }

   private PropertyFilter setupWaitForUpdates(Client vcClient, Folder baseFolder, TypeName type, String[] statePropsToGet)
         throws InvalidProperty {
      PropertyFilter propFilter = null;
      ServiceInstanceContent sic = getServiceInstanceContent(vcClient);

      ViewManager viewMgr = vcClient.createStub(ViewManager.class, sic.getViewManager());
      ContainerView cView = vcClient.createStub(ContainerView.class,
            viewMgr.createContainerView(baseFolder._getRef(), new TypeName[] {type}, true));

      propFilter = new PropertyFilter(vcClient, cView, type);

      propFilter.setPropsToFilter(statePropsToGet);
      return propFilter;
   }
   
   private UpdateSet callWaitForUpdates(PropertyCollector propCollector, String version)
         throws Exception {
      UpdateSet updateSet = null;
      if (version == null) {
         version = "";
      }

      WaitOptions waitOptions = new WaitOptions();
      waitOptions.setMaxWaitSeconds(propertyCollectorTimeout);

      updateSet = propCollector.waitForUpdatesEx(version, waitOptions);
      return updateSet;
   }

   private void cleanupWaitForUpdates(PropertyFilter propFilter) {
      propFilter.cleanup();
   }


   private void parseExtraConfig(VMEventData vmData, String key, String value) {
      if (key.startsWith(VHM_EXTRA_CONFIG_PREFIX)) {
         //_log.log(Level.INFO, "PEC key:val = " + key + " : " + value);
         if (key.equals(VHM_EXTRA_CONFIG_UUID)) {
            vmData._serengetiFolder = value;
         } else if (key.equals(VHM_EXTRA_CONFIG_MASTER_UUID)) {
            vmData._masterUUID = value;
         } else if (key.equals(VHM_EXTRA_CONFIG_MASTER_MOREF)) {
            vmData._masterMoRef = value;
         } else if (key.equals(VHM_EXTRA_CONFIG_ELASTIC)) {
            vmData._isElastic = value.equalsIgnoreCase("true");
         } else if (key.equals(VHM_EXTRA_CONFIG_AUTOMATION_ENABLE)) {
            vmData._enableAutomation = value.equalsIgnoreCase("true");
         } else if (key.equals(VHM_EXTRA_CONFIG_AUTOMATION_MIN_INSTANCES)) {
            vmData._minInstances = Integer.valueOf(value);
         } else if (key.equals(VHM_EXTRA_CONFIG_JOB_TRACKER_PORT)) {
            vmData._jobTrackerPort = Integer.valueOf(value);
         }
      }
   }
   
   private VMEventData parseObjUpdate(ObjectUpdate obj) {
      VMEventData vmData = new VMEventData();
      vmData._vmMoRef = obj.getObj().getValue();

      Kind kind = obj.getKind();
      _log.log(Level.FINE, "Pobj kind= " + kind + " obj= " + obj.getObj().getValue());
      if (kind == Kind.leave) {
         vmData._isLeaving = true;
      } else if (kind == Kind.modify || kind == Kind.enter) {
         vmData._isLeaving = false;
         for (Change pc : obj.getChangeSet()) {
            String pcName = pc.getName();
            Object pcValue = pc.getVal();
            _log.log(Level.FINE, "Pobj prop= " + pcName + " val= " + pcValue);
            if (pcValue != null) {
               if (pcName.equals(VC_PROP_VM_UUID)) {
                  vmData._myUUID = (String)pcValue;
               } else if (pcName.equals(VC_PROP_VM_NUM_CPU)) {
                  vmData._vCPUs = (Integer)pcValue; 
               } else if (pcName.equals(VC_PROP_VM_NAME)) {
                  vmData._myName = (String)pcValue;
               } else if (pcName.equals(VC_PROP_VM_POWER_STATE)) {
                  PowerState ps = (PowerState)pcValue;
                  if (ps == PowerState.poweredOn) {
                     vmData._powerState = true;
                  } else {
                     vmData._powerState = false;
                  }
               } else if (pcName.equals(VC_PROP_VM_HOST)) {
                  vmData._hostMoRef = ((ManagedObjectReference)pcValue).getValue();
               } else if (pcName.equals(VC_PROP_VM_GUEST_IP)) {
                  vmData._ipAddr = (String)pcValue; 
               } else if (pcName.equals(VC_PROP_VM_GUEST_HOSTNAME)) {
                  vmData._dnsName = (String)pcValue; 
               } else if (pcName.equals(VC_PROP_VM_EXTRA_CONFIG)) {
                  // extraConfig updates can be returned as an array (pcName == config.extraConfig), or individual key (below)
                  OptionValue[] ecl = (OptionValue[]) pcValue;
                  for (OptionValue ec : ecl) {
                     if (ec.getKey().startsWith(VHM_EXTRA_CONFIG_PREFIX)) {
                        parseExtraConfig(vmData, ec.getKey(), (String)ec.getValue());
                     }
                  }
               } else if (pcName.lastIndexOf(VC_PROP_VM_EXTRA_CONFIG) >= 0) {
                  // individual extraConfig entries (pcName = config.extraConfig["xxx"].value)
                  String [] parts = pcName.split("\"",3);
                  if (parts.length > 1) {
                     if (parts[1].startsWith(VHM_EXTRA_CONFIG_PREFIX)) {
                        // sometimes pcValue is a String, and sometimes its OptionValue...
                        String valueString;
                        if (pcValue instanceof String) {
                           valueString = (String)pcValue;
                        } else {
                           valueString = (String) ((OptionValue)pcValue).getValue();
                        }
                        parseExtraConfig(vmData, parts[1], valueString);
                     }
                  }
               } else {
                  _log.log(Level.WARNING, "Unexpected update: prop= " + pcName + " val= " + pcValue);
               }
            }
         }
      }
      return vmData;
   }

   private String pcVMsInFolder(Client vcClient, Folder folder, String version, List<VMEventData> vmDataList)
         throws Exception {
      if (version == null) {
         version = "";
      }
      if (version.equals("")) {
         String [] props = {VC_PROP_VM_NAME, VC_PROP_VM_EXTRA_CONFIG, VC_PROP_VM_UUID, VC_PROP_VM_NUM_CPU,
               VC_PROP_VM_POWER_STATE, VC_PROP_VM_HOST, VC_PROP_VM_GUEST_IP, VC_PROP_VM_GUEST_HOSTNAME};
         setupWaitForUpdates(vcClient, folder, typeVM, props);
      }
      ServiceInstanceContent sic = getServiceInstanceContent(vcClient);
      PropertyCollector propertyCollector = vcClient.createStub(PropertyCollector.class, sic.getPropertyCollector());

      UpdateSet updateSet = null;
      try {
         updateSet = callWaitForUpdates(propertyCollector, version);
      } catch (ConnectionException e) {
         Throwable cause = e.getCause();
         /*
          * SocketTimeoutException is caused when we hit SESSION_TIME_OUT
          * If that happens, hide the exception, and just return with no changes
          */
         if ((cause != null) && (cause instanceof SocketTimeoutException)) {
            return version;
         }
         throw e;
      }
      
      if (updateSet != null) {
         version = updateSet.getVersion();
         FilterUpdate[] updates = updateSet.getFilterSet();

         //_log.log(Level.INFO, "WFU new version= " + version + " fs= " + updates);
         if (updates != null) {
            for (FilterUpdate pfu : updates) {
               ObjectUpdate[] objectSet = pfu.getObjectSet();

               for (ObjectUpdate obj : objectSet) {
                  VMEventData vmData = parseObjUpdate(obj);
                  if (vmData != null) {
                     vmDataList.add(vmData);
                  }
               }
            }
         }
      }
      return version;
   }

   boolean waitForTask(Task task) {
      CompoundStatus status = new CompoundStatus("waitForTask");
      boolean result = false;
      PropertyFilter propFilter = new PropertyFilter(defaultClient, task);
      try {
         propFilter.setPropsToFilter(new String [] {TASK_INFO_STATE });
         UpdateSet updateSet = null;
         String version = "";

         WaitOptions waitOptions = new WaitOptions();
         waitOptions.setMaxWaitSeconds(propertyCollectorTimeout);

         _mainLoop: while (true) {

            _log.log(Level.INFO, "WFT waiting vesrion=" + version);
            updateSet = propFilter.getPropertyCollector().waitForUpdatesEx(version, waitOptions);
            if (updateSet != null) {
               version = updateSet.getVersion();
               FilterUpdate[] updates = updateSet.getFilterSet();

               if (updates != null) {
                  for (FilterUpdate pfu : updates) {
                     ObjectUpdate[] objectSet = pfu.getObjectSet();

                     for (ObjectUpdate obj : objectSet) {
                        Kind kind = obj.getKind();
                        if (kind == Kind.modify || kind == Kind.enter || kind == Kind.leave) {
                           for (Change pc : obj.getChangeSet()) {
                              String pcName = pc.getName();
                              Object pcValue = pc.getVal();
                              if (pcName.lastIndexOf(TASK_INFO_STATE) >= 0) {
                                 TaskInfo.State state = (TaskInfo.State)pcValue;
                                 if (state == TaskInfo.State.error) {
                                    result = false;
                                    break _mainLoop;
                                 } else if (state == TaskInfo.State.success) {
                                    result = true;
                                    break _mainLoop;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
         status.registerTaskSucceeded();
      } catch (Exception e) {
         reportException("Unexpected exception waiting for task completion", e, status);
      } finally {
         propFilter.cleanup();
      }
      getCompoundStatus().addStatus(status);
      return result;
   }

   
   public String waitForUpdates(Client vcClient, String baseFolderName, String version, List<VMEventData> vmDataList) {
      CompoundStatus status = new CompoundStatus("waitForUpdates");
      String result = version;
      try {
         Folder f = getFolderForName(null, baseFolderName);
         if (f == null) {
            // This is normal state when user hasn't created any hadoop clusters yet
            _log.log(Level.INFO, "Couldn't find folder " + baseFolderName);
         } else {
            result = pcVMsInFolder(vcClient, f, version, vmDataList);
         }
         status.registerTaskSucceeded();
      } catch (Exception e) {
         reportException("Unexpected exception waiting for updates", e, status);
      }
      getCompoundStatus().addStatus(status);
      return result;
   }

   public List<String> getVMsInFolder(String baseFolderName, String folderName) {
      CompoundStatus status = new CompoundStatus("getVMsInFolder");
      List<String> result = null;
      try {
         Folder baseFolder = getFolderForName(null, baseFolderName);
         
         Folder folder = getFolderForName(baseFolder, folderName);
         
         List<ManagedObjectReference> refs = findObjectsInFolder(folder, typeVM, null);
         if ((refs != null) && (refs.size() > 0)) {
            result = new ArrayList<String>();
            for (ManagedObjectReference ref : refs) {
               result.add(ref.getValue());
            }
         }
         status.registerTaskSucceeded();
      } catch (Exception e) {
         reportException("Unexpected exception in getVMsInFolder", e, status);
      }
      getCompoundStatus().addStatus(status);
      return result;
   }
   
   private void reportException(String msg, Exception e, CompoundStatus status) {
      _log.log(Level.INFO, msg, e);
      status.registerTaskFailed(false, msg+": "+e.getMessage());
   }

   public Map<String, Task> powerOnVMs(Set<String> vmMoRefs) {
      CompoundStatus status = new CompoundStatus(VCActions.VC_POWER_ON_STATUS_KEY);
      Map<String, Task> result = new HashMap<String, Task>();
      for (String moRef : vmMoRefs) {
         ManagedObjectReference ref = new ManagedObjectReference();
         ref.setValue(moRef);
         VirtualMachine vm = defaultClient.createStub(VirtualMachine.class, ref);
         try {
            ManagedObjectReference taskRef = vm.powerOn(null);
            Task task = defaultClient.createStub(Task.class, taskRef);
            result.put(moRef, task);
            status.registerTaskSucceeded();
         } catch (Exception e) {
            reportException("Error powering on VM", e, status);
         }
      }
      getCompoundStatus().addStatus(status);
      return result;
   }

   public Map<String, Task> powerOffVMs(Set<String> vmMoRefs) {
      CompoundStatus status = new CompoundStatus(VCActions.VC_POWER_OFF_STATUS_KEY);
      Map<String, Task> result = new HashMap<String, Task>();
      for (String moRef : vmMoRefs) {
         ManagedObjectReference ref = new ManagedObjectReference();
         ref.setValue(moRef);
         VirtualMachine vm = defaultClient.createStub(VirtualMachine.class, ref);
         try {
            ManagedObjectReference taskRef = vm.powerOff();
            Task task = defaultClient.createStub(Task.class, taskRef);
            result.put(moRef, task);
            status.registerTaskSucceeded();
         } catch (Exception e) {
            reportException("Error powering off VM", e, status);
         }
      }
      getCompoundStatus().addStatus(status);
      return result;
   }

   /*
   
   VirtualMachine vm = getVMForName(f, "xxxxx");
   _log.log(Level.INFO, "TPC vm.name = " + vm.getName());
   _log.log(Level.INFO, "TPC vm = " + vm);
   PowerState ps = vm.getRuntime().getPowerState();
   _log.log(Level.INFO, "TPC vm ps = " + ps);
   ManagedObjectReference taskRef;
   if (ps == PowerState.poweredOn) {
      taskRef = vm.powerOff();
   } else {
       taskRef = vm.powerOn(null);
   }
   Task t = defaultClient.createStub(Task.class, taskRef);
   boolean success = waitForTask(t);
   _log.log(Level.INFO, "TPC task success=" + success);
   
   ps = vm.getRuntime().getPowerState();
   _log.log(Level.INFO, "TPC vm new ps = " + ps);
*/       

}
