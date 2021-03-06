package com.vmware.vhadoop.vhm.strategy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import com.vmware.vhadoop.api.vhm.ClusterMap;
import com.vmware.vhadoop.api.vhm.strategy.VMChooser;
import com.vmware.vhadoop.vhm.AbstractClusterMapReader;

public class BalancedVMChooser extends AbstractClusterMapReader implements VMChooser {
   private static final Logger _log = Logger.getLogger(BalancedVMChooser.class.getName());

   class Host {
      Set<String> candidates;
      int on;
   }

   /**
    * Selects the VMs to operate on from the candidates passed in. Candidates are passed in grouped via host and held in a priority queue.
    * The queues comparator uses the details held per host to determine which host should be the next to have a VM operated on.
    *
    * Currently we operate on candidates on the host in the order they're found, however we could pair this with a call to chooseVmOnHost
    * to provide a more discriminating mechanism.
    *
    * @param targets - candidate VMs, grouped by host
    * @param delta - the number of VMs to operate on (>= 0)
    * @param targetPowerState - true to enable VMs, false to disable
    * @return the chosen set of VMs
    */
   protected Set<String> selectVMs(final Queue<Host> targets, final int delta, final boolean targetPowerState) {
      Set<String> result = new HashSet<String>();
      int remaining = delta;

      while (remaining-- > 0) {
         Host host = targets.poll();
         if (host == null) {
            /* there are no candidates left, so return what we have */
            return result;
         }

         Iterator<String> itr = host.candidates.iterator();
         if (itr.hasNext()) {
            if (targetPowerState) {
               host.on++;
            } else {
               host.on--;
            }
            String vmid = itr.next();
            result.add(vmid);
            itr.remove();
            _log.info("BalancedVMChooser adding VM "+vmid+" to results");
         }

         /* if this host still has candidates remaining, then insert it back into the queue */
         if (itr.hasNext()) {
            targets.add(host);
         }
      }

      return result;
   }

   public Set<String> chooseVMs(final String clusterId, final int delta, final boolean targetPowerState) {
      ClusterMap clusterMap = getAndReadLockClusterMap();
      Set<String> hosts = clusterMap.listHostsWithComputeVMsForCluster(clusterId);
      if (hosts.size() == 0) {
         unlockClusterMap(clusterMap);
         return new TreeSet<String>();
      }

      PriorityQueue<Host> targets = new PriorityQueue<Host>(hosts.size(), new Comparator<Host>() {
         @Override
         public int compare(final Host a, final Host b) { return targetPowerState ? a.on - b.on : b.on - a.on; }
      });

      /* build the entries for the priority queue */
      for (String host : hosts) {
         Set<String> on = clusterMap.listComputeVMsForClusterHostAndPowerState(clusterId, host, true);
         Set<String> candidateVMs = targetPowerState ? clusterMap.listComputeVMsForClusterHostAndPowerState(clusterId, host, false) : on;
         if (candidateVMs.size() > 0) {
            Host h = new Host();
            h.candidates = candidateVMs;
            h.on = on.size();
            targets.add(h);
         }
      }

      unlockClusterMap(clusterMap);

      return selectVMs(targets, delta, targetPowerState);
   }

   @Override
   public Set<String> chooseVMsToEnable(final String clusterId, final int delta) {
      return chooseVMs(clusterId, delta, true);
   }

   /**
    * Delta is negative for disabling VMs
    */
   @Override
   public Set<String> chooseVMsToDisable(final String clusterId, final int delta) {
      return chooseVMs(clusterId, Math.abs(delta), false);
   }

   @Override
   public String chooseVMToEnableOnHost(final Set<String> candidates) {
      /* TODO: decide whether we ever want a more sophisticated solution */
      if (candidates.isEmpty()) {
         return null;
      }

      return candidates.iterator().next();
   }

   @Override
   public String chooseVMToDisableOnHost(final Set<String> candidates) {
      /* TODO: decide whether we ever want a more sophisticated solution */
      if (candidates.isEmpty()) {
         return null;
      }

      return candidates.iterator().next();
   }

   @Override
   public Set<String> chooseVMsToEnable(final Set<String> candidates, final int delta) {
      return chooseVMs(candidates, delta, true);
   }

   @Override
   public Set<String> chooseVMsToDisable(final Set<String> candidates, final int delta) {
      return chooseVMs(candidates, Math.abs(delta), false);
   }

   public Set<String> chooseVMs(final Set<String> candidates, final int delta, final boolean targetPowerState) {
      if (candidates.isEmpty()) {
         return new TreeSet<String>();
      }

      ClusterMap clusterMap = getAndReadLockClusterMap();
      Map<String,Host> hosts = new HashMap<String,Host>();

      for (String vm : candidates) {
         String hostid = clusterMap.getHostIdForVm(vm);
         Host h = hosts.get(hostid);
         if (h == null) {
            h = new Host();
            h.candidates = new HashSet<String>();
            h.on = clusterMap.listComputeVMsForClusterHostAndPowerState(clusterMap.getClusterIdForVm(vm), hostid, true).size();
         }

         h.candidates.add(vm);
         hosts.put(hostid, h);
      }

      unlockClusterMap(clusterMap);

      PriorityQueue<Host> targets = new PriorityQueue<Host>(hosts.size(), new Comparator<Host>() {
         @Override
         public int compare(final Host a, final Host b) { return targetPowerState ? a.on - b.on : b.on - a.on; }
      });

      targets.addAll(hosts.values());

      return selectVMs(targets, Math.abs(delta), targetPowerState);
   }
}
