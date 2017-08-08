/*
 * *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership. The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.privileged.PrivilegedOperationExecutor;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * Provides mechanisms to get various resource handlers - cpu, memory, network,
 * disk etc., - based on configuration.
 */

@InterfaceAudience.Private
@InterfaceStability.Unstable
public class ResourceHandlerModule {
  private static volatile ResourceHandlerChain resourceHandlerChain;

  /**
   * This specific implementation might provide resource management as well
   * as resource metrics functionality. We need to ensure that the same
   * instance is used for both.
   */
  private static volatile TrafficControlBandwidthHandlerImpl
      trafficControlBandwidthHandler;
  private static volatile CGroupsHandler cGroupsHandler;
  private static volatile CGroupsBlkioResourceHandlerImpl
      cGroupsBlkioResourceHandler;

  /**
   * Returns an initialized, thread-safe CGroupsHandler instance.
   */
  public static CGroupsHandler getCGroupsHandler(Configuration conf)
      throws ResourceHandlerException {
    if (cGroupsHandler == null) {
      synchronized (CGroupsHandler.class) {
        if (cGroupsHandler == null) {
          cGroupsHandler = new CGroupsHandlerImpl(conf,
              PrivilegedOperationExecutor.getInstance(conf));
        }
      }
    }

    return cGroupsHandler;
  }

  private static TrafficControlBandwidthHandlerImpl
  getTrafficControlBandwidthHandler(Configuration conf)
      throws ResourceHandlerException {
    if (conf.getBoolean(YarnConfiguration.NM_NETWORK_RESOURCE_ENABLED,
        YarnConfiguration.DEFAULT_NM_NETWORK_RESOURCE_ENABLED)) {
      if (trafficControlBandwidthHandler == null) {
        synchronized (OutboundBandwidthResourceHandler.class) {
          if (trafficControlBandwidthHandler == null) {
            trafficControlBandwidthHandler = new
                TrafficControlBandwidthHandlerImpl(PrivilegedOperationExecutor
                .getInstance(conf), getCGroupsHandler(conf),
                new TrafficController(conf, PrivilegedOperationExecutor
                    .getInstance(conf)));
          }
        }
      }

      return trafficControlBandwidthHandler;
    } else {
      return null;
    }
  }

  public static OutboundBandwidthResourceHandler
  getOutboundBandwidthResourceHandler(Configuration conf)
      throws ResourceHandlerException {
    return getTrafficControlBandwidthHandler(conf);
  }

  public static DiskResourceHandler getDiskResourceHandler(Configuration conf)
      throws ResourceHandlerException {
    if (conf.getBoolean(YarnConfiguration.NM_DISK_RESOURCE_ENABLED,
        YarnConfiguration.DEFAULT_NM_DISK_RESOURCE_ENABLED)) {
      return getCgroupsBlkioResourceHandler(conf);
    }
    return null;
  }

  private static CGroupsBlkioResourceHandlerImpl getCgroupsBlkioResourceHandler(
      Configuration conf) throws ResourceHandlerException {
    if (cGroupsBlkioResourceHandler == null) {
      synchronized (DiskResourceHandler.class) {
        if (cGroupsBlkioResourceHandler == null) {
          cGroupsBlkioResourceHandler =
              new CGroupsBlkioResourceHandlerImpl(getCGroupsHandler(conf));
        }
      }
    }
    return cGroupsBlkioResourceHandler;
  }

  private static void addHandlerIfNotNull(List<ResourceHandler> handlerList,
      ResourceHandler handler) {
    if (handler != null) {
      handlerList.add(handler);
    }
  }

  private static void initializeConfiguredResourceHandlerChain(
      Configuration conf) throws ResourceHandlerException {
    ArrayList<ResourceHandler> handlerList = new ArrayList<>();

    addHandlerIfNotNull(handlerList, getOutboundBandwidthResourceHandler(conf));
    addHandlerIfNotNull(handlerList, getDiskResourceHandler(conf));
    resourceHandlerChain = new ResourceHandlerChain(handlerList);
  }

  public static ResourceHandlerChain getConfiguredResourceHandlerChain(
      Configuration conf) throws ResourceHandlerException {
    if (resourceHandlerChain == null) {
      synchronized (ResourceHandlerModule.class) {
        if (resourceHandlerChain == null) {
          initializeConfiguredResourceHandlerChain(conf);
        }
      }
    }

    if (resourceHandlerChain.getResourceHandlerList().size() != 0) {
      return resourceHandlerChain;
    } else {
      return null;
    }
  }

  @VisibleForTesting
  static void nullifyResourceHandlerChain() throws ResourceHandlerException {
    resourceHandlerChain = null;
  }

  /**
   * If a cgroup mount directory is specified, it returns cgroup directories
   * with valid names.
   * The requirement is that each hierarchy has to be named with the comma
   * separated names of subsystems supported.
   * For example: /sys/fs/cgroup/cpu,cpuacct
   * @param cgroupMountPath Root cgroup mount path (/sys/fs/cgroup in the
   *                        example above)
   * @return A path to cgroup subsystem set mapping in the same format as
   *         {@link CGroupsHandlerImpl#parseMtab(String)}
   * @throws IOException if the specified directory cannot be listed
   */
  public static Map<String, List<String>> parseConfiguredCGroupPath(
      String cgroupMountPath) throws IOException {
    File cgroupDir = new File(cgroupMountPath);
    File[] list = cgroupDir.listFiles();
    if (list == null) {
      throw new IOException("Empty cgroup mount directory specified: " +
          cgroupMountPath);
    }

    Map<String, List<String>> pathSubsystemMappings = new HashMap<>();
    Set<String> validCGroups =
        CGroupsHandler.CGroupController.getValidCGroups();
    for (File candidate: list) {
      List<String> cgroupList =
          new LinkedList<>(Arrays.asList(candidate.getName().split(",")));
      // Collect the valid subsystem names
      cgroupList.retainAll(validCGroups);
      if (!cgroupList.isEmpty()) {
        if (candidate.isDirectory() && candidate.canWrite()) {
          pathSubsystemMappings.put(candidate.getAbsolutePath(), cgroupList);
        }
      }
    }
    return pathSubsystemMappings;
  }
}
