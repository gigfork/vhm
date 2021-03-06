package com.vmware.vhadoop.api.vhm.events;

/* If the ClusterStateChangeListenerImpl notices a delta change, it generates one of these events for the VHM to consume */
public interface ClusterStateChangeEvent extends NotificationEvent {

   public class VMEventData {
      // these two fields must always be filled
      public String _vmMoRef;
      public boolean _isLeaving;

      // these fields can be left as null if there is no new information
      public String _myName;
      public String _hostMoRef;
      public String _serengetiFolder;
      public String _masterUUID;
      public String _myUUID;
      public Boolean _powerState;
      public String _masterMoRef;
      public Boolean _enableAutomation;
      public Integer _minInstances;
      public Boolean _isElastic;
      public String _ipAddr;
      public String _dnsName;
      public Integer _jobTrackerPort;
      public Integer _vCPUs;
   }
}
