package com.vmware.vhadoop.api.vhm.strategy;

import java.util.*;

import com.vmware.vhadoop.api.vhm.ClusterMapReader;

/* Takes a set of VMs and either enables or disables them, based on whatever strategy it needs */
public interface EDPolicy extends ClusterMapReader {
   void enableTTs(Set<String> toEnable, int totalTargetEnabled, String clusterId) throws Exception;

   /* Caller should expect this to block */
   void disableTTs(Set<String> toDisable, int totalTargetEnabled, String clusterId) throws Exception;
}
