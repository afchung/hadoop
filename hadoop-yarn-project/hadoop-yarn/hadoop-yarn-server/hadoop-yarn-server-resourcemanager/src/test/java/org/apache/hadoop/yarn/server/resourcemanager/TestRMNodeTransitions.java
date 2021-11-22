/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager;

import static org.apache.hadoop.yarn.server.resourcemanager.MockNM.createMockNodeStatus;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.hadoop.util.HostsFileReader;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.ExecutionType;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.api.records.ResourceUtilization;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.event.InlineDispatcher;
import org.apache.hadoop.yarn.nodelabels.CommonNodeLabelsManager;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.api.protocolrecords.NodeHeartbeatResponse;
import org.apache.hadoop.yarn.server.api.records.NodeHealthStatus;
import org.apache.hadoop.yarn.server.api.records.NodeStatus;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer
    .AllocationExpirationInfo;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.ContainerAllocationExpirer;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeCleanAppEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeCleanContainerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeFinishedContainersPulledByAMEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeReconnectEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeResourceUpdateEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStartedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStatusEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.UpdatedContainerInfo;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.security.DelegationTokenRenewer;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.Records;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestRMNodeTransitions {

  RMNodeImpl node;

  private RMContext rmContext;
  private YarnScheduler scheduler;

  private SchedulerEventType eventType;
  private List<ContainerStatus> completedContainers = new ArrayList<ContainerStatus>();
  
  private final class TestSchedulerEventDispatcher implements
  EventHandler<SchedulerEvent> {
    @Override
    public void handle(SchedulerEvent event) {
      scheduler.handle(event);
    }
  }

  private NodesListManagerEvent nodesListManagerEvent = null;
  private List<NodeState> nodesListManagerEventsNodeStateSequence =
      new LinkedList<>();

  private class TestNodeListManagerEventDispatcher implements
      EventHandler<NodesListManagerEvent> {
    
    @Override
    public void handle(NodesListManagerEvent event) {
      nodesListManagerEvent = event;
      nodesListManagerEventsNodeStateSequence.add(event.getNode().getState());
    }

  }

  @Before
  public void setUp() throws Exception {
    InlineDispatcher rmDispatcher = new InlineDispatcher();
    
    rmContext =
        new RMContextImpl(rmDispatcher, mock(ContainerAllocationExpirer.class),
          null, null, mock(DelegationTokenRenewer.class), null, null, null,
          null, getMockResourceScheduler());
    NodesListManager nodesListManager = mock(NodesListManager.class);
    HostsFileReader reader = mock(HostsFileReader.class);
    when(nodesListManager.getHostsReader()).thenReturn(reader);
    ((RMContextImpl) rmContext).setNodesListManager(nodesListManager);
    scheduler = mock(YarnScheduler.class);
    doAnswer(
        new Answer<Void>() {

          @Override
          public Void answer(InvocationOnMock invocation) throws Throwable {
            final SchedulerEvent event = (SchedulerEvent)(invocation.getArguments()[0]);
            eventType = event.getType();
            if (eventType == SchedulerEventType.NODE_UPDATE) {
              List<UpdatedContainerInfo> lastestContainersInfoList = 
                  ((NodeUpdateSchedulerEvent)event).getRMNode().pullContainerUpdates();
              for(UpdatedContainerInfo lastestContainersInfo : lastestContainersInfoList) {
            	  completedContainers.addAll(lastestContainersInfo.getCompletedContainers()); 
              }
            }
            return null;
          }
        }
        ).when(scheduler).handle(any(SchedulerEvent.class));
    
    rmDispatcher.register(SchedulerEventType.class, 
        new TestSchedulerEventDispatcher());
    
    rmDispatcher.register(NodesListManagerEventType.class,
        new TestNodeListManagerEventDispatcher());

    NodeId nodeId = BuilderUtils.newNodeId("localhost", 0);
    node = new RMNodeImpl(nodeId, rmContext, null, 0, 0, null, null, null);
    nodesListManagerEvent =  null;
    nodesListManagerEventsNodeStateSequence.clear();
  }
  
  @After
  public void tearDown() throws Exception {
  }
  
  private RMNodeStatusEvent getMockRMNodeStatusEvent(
      List<ContainerStatus> containerStatus) {
    final NodeHealthStatus healthStatus = NodeHealthStatus.newInstance(true, null, 0);
    final NodeStatus nodeStatus = NodeStatus.newInstance(
        null, 0, containerStatus, null, healthStatus, null, null, null);
    return new RMNodeStatusEvent(null, nodeStatus);
  }
  
  private RMNodeStatusEvent getMockRMNodeStatusEventWithRunningApps() {
    NodeHealthStatus healthStatus = mock(NodeHealthStatus.class);
    Boolean yes = new Boolean(true);
    doReturn(yes).when(healthStatus).getIsNodeHealthy();

    RMNodeStatusEvent event = mock(RMNodeStatusEvent.class);
    doReturn(healthStatus).when(event).getNodeHealthStatus();
    doReturn(RMNodeEventType.STATUS_UPDATE).when(event).getType();
    doReturn(getAppIdList()).when(event).getKeepAliveAppIds();
    return event;
  }

  private ResourceScheduler getMockResourceScheduler() {
    ResourceScheduler resourceScheduler = mock(ResourceScheduler.class);
    SchedulerNode schedulerNode = mock(SchedulerNode.class);
    when(schedulerNode.getCopiedListOfRunningContainers())
        .thenReturn(Collections.emptyList());
    when(resourceScheduler.getSchedulerNode(ArgumentMatchers.any()))
        .thenReturn(schedulerNode);
    return resourceScheduler;
  }

  private List<ApplicationId> getAppIdList() {
    List<ApplicationId> appIdList = new ArrayList<ApplicationId>();
    appIdList.add(BuilderUtils.newApplicationId(0, 0));
    return appIdList;
  }

  private List<ContainerId> getContainerIdList() {
    List<ContainerId> containerIdList = new ArrayList<ContainerId>();
    containerIdList.add(BuilderUtils.newContainerId(BuilderUtils
        .newApplicationAttemptId(BuilderUtils.newApplicationId(0, 0), 0), 0));
    return containerIdList;
  }

  private RMNodeStatusEvent getMockRMNodeStatusEventWithoutRunningApps() {
    NodeHealthStatus healthStatus = mock(NodeHealthStatus.class);
    Boolean yes = new Boolean(true);
    doReturn(yes).when(healthStatus).getIsNodeHealthy();

    RMNodeStatusEvent event = mock(RMNodeStatusEvent.class);
    doReturn(healthStatus).when(event).getNodeHealthStatus();
    doReturn(RMNodeEventType.STATUS_UPDATE).when(event).getType();
    doReturn(null).when(event).getKeepAliveAppIds();
    return event;
  }

  private static ContainerStatus getMockContainerStatus(
      final ContainerId containerId, final Resource capability,
      final ContainerState containerState) {
    return getMockContainerStatus(containerId, capability, containerState,
        ExecutionType.GUARANTEED);
  }

  private static ContainerStatus getMockContainerStatus(
      final ContainerId containerId, final Resource capability,
      final ContainerState containerState, final ExecutionType executionType) {
    final ContainerStatus containerStatus = ContainerStatus.newInstance(
        containerId, executionType, containerState, capability, "", 0);
    return containerStatus;
  }

  private static NMContainerStatus createNMContainerStatus(
      final ContainerId containerId, final ExecutionType executionType,
      final ContainerState containerState, final Resource capability) {
    return NMContainerStatus.newInstance(containerId, 0, containerState,
        capability, "", 0, Priority.newInstance(0), 0,
        CommonNodeLabelsManager.NO_LABEL, executionType, -1);
  }

  @Test (timeout = 5000)
  public void testExpiredContainer() {
    NodeStatus mockNodeStatus = createMockNodeStatus();
    // Start the node
    node.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));
    verify(scheduler).handle(any(NodeAddedSchedulerEvent.class));
    
    // Expire a container
    ContainerId completedContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(0, 0), 0), 0);
    node.handle(new RMNodeCleanContainerEvent(null, completedContainerId));
    Assert.assertEquals(1, node.getContainersToCleanUp().size());
    
    // Now verify that scheduler isn't notified of an expired container
    // by checking number of 'completedContainers' it got in the previous event
    RMNodeStatusEvent statusEvent = getMockRMNodeStatusEvent(null);
    ContainerStatus containerStatus = getMockContainerStatus(
        completedContainerId, null, ContainerState.COMPLETE);
    doReturn(Collections.singletonList(containerStatus)).
        when(statusEvent).getContainers();
    node.handle(statusEvent);
    /* Expect the scheduler call handle function 2 times
     * 1. RMNode status from new to Running, handle the add_node event
     * 2. handle the node update event
     */
    verify(scheduler, times(1)).handle(any(NodeAddedSchedulerEvent.class));
    verify(scheduler, times(1)).handle(any(NodeUpdateSchedulerEvent.class));
  }

  @Test
  public void testStatusUpdateOnDecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    // Verify node in DECOMMISSIONING won't be changed by status update
    // with running apps
    RMNodeStatusEvent statusEvent = getMockRMNodeStatusEventWithRunningApps();
    node.handle(statusEvent);
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning,
        cm.getNumDecommissioningNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned,
        cm.getNumDecommisionedNMs());
  }

  @Test
  public void testRecommissionNode() {
    RMNodeImpl node = getDecommissioningNode();
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.RECOMMISSION));
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    Assert
        .assertEquals("Active Nodes", initialActive + 1, cm.getNumActiveNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning - 1,
        cm.getNumDecommissioningNMs());
  }

  @Test (timeout = 5000)
  public void testContainerUpdate() throws InterruptedException{
    NodeStatus mockNodeStatus = createMockNodeStatus();
    //Start the node
    node.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));
    
    NodeId nodeId = BuilderUtils.newNodeId("localhost:1", 1);
    RMNodeImpl node2 = new RMNodeImpl(nodeId, rmContext, null, 0, 0, null, null, null);
    node2.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));

    ApplicationId app0 = BuilderUtils.newApplicationId(0, 0);
    ApplicationId app1 = BuilderUtils.newApplicationId(1, 1);
    ContainerId completedContainerIdFromNode1 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 0);
    ContainerId completedContainerIdFromNode2_1 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app1, 1), 1);
    ContainerId completedContainerIdFromNode2_2 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app1, 1), 2);
    rmContext.getRMApps().put(app0, Mockito.mock(RMApp.class));
    rmContext.getRMApps().put(app1, Mockito.mock(RMApp.class));

    RMNodeStatusEvent statusEventFromNode1 = getMockRMNodeStatusEvent(null);
    RMNodeStatusEvent statusEventFromNode2_1 = getMockRMNodeStatusEvent(null);
    RMNodeStatusEvent statusEventFromNode2_2 = getMockRMNodeStatusEvent(null);

    ContainerStatus containerStatusFromNode1 = getMockContainerStatus(
        completedContainerIdFromNode1, null, ContainerState.COMPLETE);
    ContainerStatus containerStatusFromNode2_1 = getMockContainerStatus(
        completedContainerIdFromNode2_1, null, ContainerState.COMPLETE);
    ContainerStatus containerStatusFromNode2_2 = getMockContainerStatus(
        completedContainerIdFromNode2_2, null, ContainerState.COMPLETE);

    doReturn(Collections.singletonList(containerStatusFromNode1))
        .when(statusEventFromNode1).getContainers();
    node.handle(statusEventFromNode1);
    Assert.assertEquals(1, completedContainers.size());
    Assert.assertEquals(completedContainerIdFromNode1,
        completedContainers.get(0).getContainerId());

    completedContainers.clear();

    doReturn(Collections.singletonList(containerStatusFromNode2_1))
        .when(statusEventFromNode2_1).getContainers();

    doReturn(Collections.singletonList(containerStatusFromNode2_2))
        .when(statusEventFromNode2_2).getContainers();

    node2.setNextHeartBeat(false);
    node2.handle(statusEventFromNode2_1);
    node2.setNextHeartBeat(true);
    node2.handle(statusEventFromNode2_2);

    Assert.assertEquals(2, completedContainers.size());
    Assert.assertEquals(completedContainerIdFromNode2_1,completedContainers.get(0)
        .getContainerId()); 
    Assert.assertEquals(completedContainerIdFromNode2_2,completedContainers.get(1)
        .getContainerId());
  }

  /**
   * Tests that allocated resources are counted correctly on new nodes
   * that are added to the cluster.
   */
  @Test
  public void testAddWithAllocatedContainers() {
    NodeStatus mockNodeStatus = createMockNodeStatus();
    RMNodeImpl node = getNewNode();
    ApplicationId app0 = BuilderUtils.newApplicationId(0, 0);

    // Independently computed expected allocated resource to verify against
    final Resource expectedResource = Resource.newInstance(Resources.none());

    // Guaranteed containers
    final ContainerId newContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 0);
    final Resource newContainerCapability =
        Resource.newInstance(100, 1);
    Resources.addTo(expectedResource, newContainerCapability);
    final NMContainerStatus newContainerStatus = createNMContainerStatus(
        newContainerId, ExecutionType.GUARANTEED,
        ContainerState.NEW, newContainerCapability);

    final ContainerId runningContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 1);
    final Resource runningContainerCapability =
        Resource.newInstance(200, 2);
    Resources.addTo(expectedResource, runningContainerCapability);
    final NMContainerStatus runningContainerStatus = createNMContainerStatus(
        runningContainerId, ExecutionType.GUARANTEED,
        ContainerState.RUNNING, runningContainerCapability);

    // Opportunistic containers
    final ContainerId newOppContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 2);
    final Resource newOppContainerCapability =
        Resource.newInstance(300, 3);
    Resources.addTo(expectedResource, newOppContainerCapability);
    final NMContainerStatus newOppContainerStatus = createNMContainerStatus(
        newOppContainerId, ExecutionType.OPPORTUNISTIC,
        ContainerState.NEW, newOppContainerCapability);

    final ContainerId runningOppContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 3);
    final Resource runningOppContainerCapability =
        Resource.newInstance(400, 4);
    Resources.addTo(expectedResource, runningOppContainerCapability);
    final NMContainerStatus runningOppContainerStatus = createNMContainerStatus(
        runningOppContainerId, ExecutionType.OPPORTUNISTIC,
        ContainerState.RUNNING, runningOppContainerCapability);

    node.handle(new RMNodeStartedEvent(node.getNodeID(),
        Arrays.asList(newContainerStatus, runningContainerStatus,
            newOppContainerStatus, runningOppContainerStatus),
        null, mockNodeStatus));
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    Assert.assertNotNull(nodesListManagerEvent);
    Assert.assertEquals(NodesListManagerEventType.NODE_USABLE,
        nodesListManagerEvent.getType());
    Assert.assertEquals(expectedResource, node.getAllocatedContainerResource());
  }

  @Test
  public void testAllocatedContainerTimes() {
    System.out.println("NEW TEST");
    final int numNodes = 20000;
    final int numContainersPerNode = 12;
    final int numHeartBeatsToTest = 100;
    final Map<Integer, RMNodeImpl> nodeMap = new HashMap<>();
    int containerId = 0;

    ApplicationId app0 = BuilderUtils.newApplicationId(0, 0);
    rmContext.getRMApps().put(app0, Mockito.mock(RMApp.class));

    // Maps node to list of containers to report
    Map<Integer, RMNodeStatusEvent> containerStatuses = new HashMap<>();

    for (int i = 0; i < numNodes; i++) {
      NodeStatus mockNodeStatus = createMockNodeStatus();
      NodeId nodeId = BuilderUtils.newNodeId("localhost", i);
      RMNodeImpl currNode = new RMNodeImpl(
          nodeId, rmContext, null, 0, 0, null, null, null);
      //Start the node
      currNode.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));

      // Make sure that the node starts with no allocated resources
      // Assert.assertEquals(Resources.none(), currNode.getAllocatedContainerResource());
      final List<ContainerStatus> statusList = new ArrayList<>();
      final Resource capability = Resource.newInstance(1000, 2);
      for (int j = 0; j < numContainersPerNode; j++) {
        final ContainerId id = BuilderUtils.newContainerId(
            BuilderUtils.newApplicationAttemptId(app0, 0), containerId);
        containerId += 1;
        final ContainerStatus containerStatus = getMockContainerStatus(
            id, capability, ContainerState.RUNNING);
        statusList.add(containerStatus);
      }

      RMNodeStatusEvent statusEventFromNode = getMockRMNodeStatusEvent(statusList);
      containerStatuses.put(i, statusEventFromNode);
      nodeMap.put(i, currNode);
    }

    long sumNano = 0;
    long start = System.currentTimeMillis();
    for (int hb = 0; hb < numHeartBeatsToTest; hb++) {
      for (int i = 0; i < numNodes; i++) {
        final RMNodeImpl currNode = nodeMap.get(i);
        long innerStart = System.nanoTime();
        currNode.handle(containerStatuses.get(i));
        long innerFinish = System.nanoTime();
        sumNano += innerFinish - innerStart;
      }
    }

    long finish = System.currentTimeMillis();
    long timeElapsedMillis = finish - start;
    System.out.println("Milliseconds elapsed overall: " + timeElapsedMillis);
    System.out.println("Nanoseconds per heartbeat:: " + (sumNano / (numHeartBeatsToTest * numNodes)));

    for (int i = 0; i < numNodes; i++) {
      final RMNodeImpl currNode = nodeMap.get(i);
      Assert.assertEquals(Resource.newInstance(12 * 1000, 12 * 2),
          currNode.getAllocatedContainerResource());
    }
  }

  /**
   * Tests that allocated container resources are counted correctly in
   * {@link org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode}
   * upon a node update. Resources should be counted for both GUARANTEED
   * and OPPORTUNISTIC containers.
   */
  @Test (timeout = 5000)
  public void testAllocatedContainerUpdate() {
    NodeStatus mockNodeStatus = createMockNodeStatus();
    //Start the node
    node.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));

    // Make sure that the node starts with no allocated resources
    Assert.assertEquals(Resources.none(), node.getAllocatedContainerResource());

    ApplicationId app0 = BuilderUtils.newApplicationId(0, 0);
    final ContainerId newContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 0);
    final ContainerId runningContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 1);

    rmContext.getRMApps().put(app0, Mockito.mock(RMApp.class));

    RMNodeStatusEvent statusEventFromNode1 = getMockRMNodeStatusEvent(null);

    final List<ContainerStatus> containerStatuses = new ArrayList<>();

    // Use different memory and VCores for new and running state containers
    // to test that they add up correctly
    final Resource newContainerCapability =
        Resource.newInstance(100, 1);
    final Resource runningContainerCapability =
        Resource.newInstance(200, 2);
    final Resource completedContainerCapability =
        Resource.newInstance(50, 3);
    final ContainerStatus newContainerStatusFromNode = getMockContainerStatus(
        newContainerId, newContainerCapability, ContainerState.NEW);
    final ContainerStatus runningContainerStatusFromNode =
        getMockContainerStatus(runningContainerId, runningContainerCapability,
            ContainerState.RUNNING);

    containerStatuses.addAll(Arrays.asList(
        newContainerStatusFromNode, runningContainerStatusFromNode));
    doReturn(containerStatuses).when(statusEventFromNode1).getContainers();
    node.handle(statusEventFromNode1);
    Assert.assertEquals(Resource.newInstance(300, 3),
        node.getAllocatedContainerResource());

    final ContainerId newOppContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 2);
    final ContainerId runningOppContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 3);

    // Use the same resource capability as in previous for opportunistic case
    RMNodeStatusEvent statusEventFromNode2 = getMockRMNodeStatusEvent(null);
    final ContainerStatus newOppContainerStatusFromNode =
        getMockContainerStatus(newOppContainerId, newContainerCapability,
            ContainerState.NEW, ExecutionType.OPPORTUNISTIC);
    final ContainerStatus runningOppContainerStatusFromNode =
        getMockContainerStatus(runningOppContainerId,
            runningContainerCapability, ContainerState.RUNNING,
            ExecutionType.OPPORTUNISTIC);

    containerStatuses.addAll(Arrays.asList(
        newOppContainerStatusFromNode, runningOppContainerStatusFromNode));

    // Pass in both guaranteed and opportunistic container statuses
    doReturn(containerStatuses).when(statusEventFromNode2).getContainers();

    node.handle(statusEventFromNode2);

    // The result here should be double the first check,
    // since allocated resources are doubled, just
    // with different execution types
    Assert.assertEquals(Resource.newInstance(600, 6),
        node.getAllocatedContainerResource());

    RMNodeStatusEvent statusEventFromNode3 = getMockRMNodeStatusEvent(null);
    final ContainerId completedContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 4);
    final ContainerId completedOppContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(app0, 0), 5);
    final ContainerStatus completedContainerStatusFromNode =
        getMockContainerStatus(completedContainerId, completedContainerCapability,
            ContainerState.COMPLETE, ExecutionType.OPPORTUNISTIC);
    final ContainerStatus completedOppContainerStatusFromNode =
        getMockContainerStatus(completedOppContainerId,
            completedContainerCapability, ContainerState.COMPLETE,
            ExecutionType.OPPORTUNISTIC);

    containerStatuses.addAll(Arrays.asList(
        completedContainerStatusFromNode, completedOppContainerStatusFromNode));

    doReturn(containerStatuses).when(statusEventFromNode3).getContainers();
    node.handle(statusEventFromNode3);

    // Adding completed containers should not have changed
    // the resources allocated
    Assert.assertEquals(Resource.newInstance(600, 6),
        node.getAllocatedContainerResource());

    RMNodeStatusEvent emptyStatusEventFromNode =
        getMockRMNodeStatusEvent(null);

    doReturn(Collections.emptyList())
        .when(emptyStatusEventFromNode).getContainers();
    node.handle(emptyStatusEventFromNode);

    // Passing an empty containers list should yield no resources allocated
    Assert.assertEquals(Resources.none(),
        node.getAllocatedContainerResource());
  }

  @Test (timeout = 5000)
  public void testStatusChange(){
    NodeStatus mockNodeStatus = createMockNodeStatus();
    //Start the node
    node.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));
    //Add info to the queue first
    node.setNextHeartBeat(false);

    ContainerId completedContainerId1 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(0, 0), 0), 0);
    ContainerId completedContainerId2 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(1, 1), 1), 1);
        
    RMNodeStatusEvent statusEvent1 = getMockRMNodeStatusEvent(null);
    RMNodeStatusEvent statusEvent2 = getMockRMNodeStatusEvent(null);

    ContainerStatus containerStatus1 = getMockContainerStatus(
        completedContainerId1, null, null);
    ContainerStatus containerStatus2 = getMockContainerStatus(
        completedContainerId2, null, null);

    doReturn(Collections.singletonList(containerStatus1))
        .when(statusEvent1).getContainers();
     
    doReturn(Collections.singletonList(containerStatus2))
        .when(statusEvent2).getContainers();

    verify(scheduler, times(1)).handle(any(NodeAddedSchedulerEvent.class));
    node.handle(statusEvent1);
    node.handle(statusEvent2);
    verify(scheduler, times(1)).handle(any(NodeAddedSchedulerEvent.class));
    Assert.assertEquals(2, node.getQueueSize());
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.EXPIRE));
    Assert.assertEquals(0, node.getQueueSize());
  }

  @Test
  public void testRunningExpire() {
    RMNodeImpl node = getRunningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.EXPIRE));
    Assert.assertEquals("Active Nodes", initialActive - 1, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost + 1, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.LOST, node.getState());
  }

  @Test
  public void testRunningExpireMultiple() {
    RMNodeImpl node1 = getRunningNode(null, 10001);
    RMNodeImpl node2 = getRunningNode(null, 10002);
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node1.handle(new RMNodeEvent(node1.getNodeID(), RMNodeEventType.EXPIRE));
    Assert.assertEquals("Active Nodes", initialActive - 1, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost + 1, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes", initialUnhealthy,
        cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned,
        cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes", initialRebooted,
        cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.LOST, node1.getState());
    Assert.assertTrue("Node " + node1.toString() + " should be inactive",
        rmContext.getInactiveRMNodes().containsKey(node1.getNodeID()));
    Assert.assertFalse("Node " + node2.toString() + " should not be inactive",
        rmContext.getInactiveRMNodes().containsKey(node2.getNodeID()));

    node2.handle(new RMNodeEvent(node1.getNodeID(), RMNodeEventType.EXPIRE));
    Assert.assertEquals("Active Nodes", initialActive - 2, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost + 2, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes", initialUnhealthy,
        cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned,
        cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes", initialRebooted,
        cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.LOST, node2.getState());
    Assert.assertTrue("Node " + node1.toString() + " should be inactive",
        rmContext.getInactiveRMNodes().containsKey(node1.getNodeID()));
    Assert.assertTrue("Node " + node2.toString() + " should be inactive",
        rmContext.getInactiveRMNodes().containsKey(node2.getNodeID()));
  }

  @Test
  public void testUnhealthyExpire() {
    RMNodeImpl node = getUnhealthyNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.EXPIRE));
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost + 1, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy - 1, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.LOST, node.getState());
  }

  @Test
  public void testUnhealthyExpireForSchedulerRemove() {
    RMNodeImpl node = getUnhealthyNode();
    verify(scheduler, times(1)).handle(any(NodeRemovedSchedulerEvent.class));
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.EXPIRE));
    verify(scheduler, times(1)).handle(any(NodeRemovedSchedulerEvent.class));
    Assert.assertEquals(NodeState.LOST, node.getState());
  }

  @Test
  public void testRunningDecommission() {
    RMNodeImpl node = getRunningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.DECOMMISSION));
    Assert.assertEquals("Active Nodes", initialActive - 1, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned + 1, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
  }

  @Test
  public void testDecommissionOnDecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.DECOMMISSION));
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes", initialUnhealthy,
        cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning - 1,
        cm.getNumDecommissioningNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned + 1,
        cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes", initialRebooted,
        cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
  }

  @Test
  public void testUnhealthyDecommission() {
    RMNodeImpl node = getUnhealthyNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.DECOMMISSION));
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy - 1, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned + 1, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
  }

  // Test Decommissioning on a unhealthy node will make it decommissioning.
  @Test
  public void testUnhealthyDecommissioning() {
    RMNodeImpl node = getUnhealthyNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.GRACEFUL_DECOMMISSION));
    Assert.assertEquals("Active Nodes", initialActive,
        cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy - 1, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned,
        cm.getNumDecommisionedNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning + 1,
        cm.getNumDecommissioningNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
  }

  @Test
  public void testRunningRebooting() {
    RMNodeImpl node = getRunningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.REBOOTING));
    Assert.assertEquals("Active Nodes", initialActive - 1, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted + 1, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.REBOOTED, node.getState());
  }

  @Test
  public void testUnhealthyRebooting() {
    RMNodeImpl node = getUnhealthyNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.REBOOTING));
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy - 1, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted + 1, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.REBOOTED, node.getState());
  }

  @Test
  public void testAddUnhealthyNode() {
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();

    NodeHealthStatus status = NodeHealthStatus.newInstance(false, "sick",
        System.currentTimeMillis());
    NodeStatus nodeStatus = NodeStatus.newInstance(node.getNodeID(), 0,
        new ArrayList<>(), null, status, null, null, null);
    node.handle(new RMNodeStartedEvent(node.getNodeID(), null, null,
        nodeStatus));

    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy + 1, cm.getUnhealthyNMs());
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.UNHEALTHY, node.getState());
  }

  @Test
  public void testNMShutdown() {
    RMNodeImpl node = getRunningNode();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.SHUTDOWN));
    Assert.assertEquals(NodeState.SHUTDOWN, node.getState());
  }

  @Test
  public void testUnhealthyNMShutdown() {
    RMNodeImpl node = getUnhealthyNode();
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.SHUTDOWN));
    Assert.assertEquals(NodeState.SHUTDOWN, node.getState());
  }

  @Test(timeout=20000)
  public void testUpdateHeartbeatResponseForCleanup() {
    RMNodeImpl node = getRunningNode();
    NodeId nodeId = node.getNodeID();

    // Expire a container
    ContainerId completedContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(0, 0), 0), 0);
    node.handle(new RMNodeCleanContainerEvent(nodeId, completedContainerId));
    Assert.assertEquals(1, node.getContainersToCleanUp().size());

    // Finish an application
    ApplicationId finishedAppId = BuilderUtils.newApplicationId(0, 1);
    node.handle(new RMNodeCleanAppEvent(nodeId, finishedAppId));
    Assert.assertEquals(1, node.getAppsToCleanup().size());

    // Verify status update does not clear containers/apps to cleanup
    // but updating heartbeat response for cleanup does
    RMNodeStatusEvent statusEvent = getMockRMNodeStatusEvent(null);
    node.handle(statusEvent);
    Assert.assertEquals(1, node.getContainersToCleanUp().size());
    Assert.assertEquals(1, node.getAppsToCleanup().size());
    NodeHeartbeatResponse hbrsp = Records.newRecord(NodeHeartbeatResponse.class);
    node.setAndUpdateNodeHeartbeatResponse(hbrsp);
    Assert.assertEquals(0, node.getContainersToCleanUp().size());
    Assert.assertEquals(0, node.getAppsToCleanup().size());
    Assert.assertEquals(1, hbrsp.getContainersToCleanup().size());
    Assert.assertEquals(completedContainerId, hbrsp.getContainersToCleanup().get(0));
    Assert.assertEquals(1, hbrsp.getApplicationsToCleanup().size());
    Assert.assertEquals(finishedAppId, hbrsp.getApplicationsToCleanup().get(0));
  }

  @Test(timeout=20000)
  public void testUpdateHeartbeatResponseForAppLifeCycle() {
    RMNodeImpl node = getRunningNode();
    NodeId nodeId = node.getNodeID();

    ApplicationId runningAppId = BuilderUtils.newApplicationId(0, 1);
    rmContext.getRMApps().put(runningAppId, Mockito.mock(RMApp.class));
    // Create a running container
    ContainerId runningContainerId = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
        runningAppId, 0), 0);

    ContainerStatus status = ContainerStatus.newInstance(runningContainerId,
        ContainerState.RUNNING, "", 0);
    List<ContainerStatus> statusList = new ArrayList<ContainerStatus>();
    statusList.add(status);
    NodeHealthStatus nodeHealth = NodeHealthStatus.newInstance(true,
        "", System.currentTimeMillis());
    NodeStatus nodeStatus = NodeStatus.newInstance(nodeId, 0, statusList, null,
        nodeHealth, null, null, null);
    node.handle(new RMNodeStatusEvent(nodeId, nodeStatus, null));

    Assert.assertEquals(1, node.getRunningApps().size());

    // Finish an application
    ApplicationId finishedAppId = runningAppId;
    node.handle(new RMNodeCleanAppEvent(nodeId, finishedAppId));
    Assert.assertEquals(1, node.getAppsToCleanup().size());
    Assert.assertEquals(0, node.getRunningApps().size());
  }

  @Test
  public void testUnknownNodeId() {
    NodeId nodeId =
        NodesListManager.createUnknownNodeId("host1");
    RMNodeImpl node =
        new RMNodeImpl(nodeId, rmContext, null, 0, 0, null, null, null);
    rmContext.getInactiveRMNodes().putIfAbsent(nodeId,node);
    node.handle(
        new RMNodeEvent(node.getNodeID(), RMNodeEventType.DECOMMISSION));
    Assert.assertNull(
        "Must be null as there is no NODE_UNUSABLE update",
        nodesListManagerEvent);
  }

  private RMNodeImpl getRunningNode() {
    return getRunningNode(null, 0);
  }

  private RMNodeImpl getRunningNode(String nmVersion) {
    return getRunningNode(nmVersion, 0);
  }

  private RMNodeImpl getRunningNode(String nmVersion, int port) {
    NodeId nodeId = BuilderUtils.newNodeId("localhost", port);
    Resource capability = Resource.newInstance(4096, 4);
    RMNodeImpl node = new RMNodeImpl(nodeId, rmContext, null, 0, 0, null,
        capability, nmVersion);
    NodeStatus mockNodeStatus = createMockNodeStatus();
    node.handle(new RMNodeStartedEvent(node.getNodeID(), null, null,
        mockNodeStatus));
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    return node;
  }

  private RMNodeImpl getDecommissioningNode() {
    RMNodeImpl node = getRunningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.GRACEFUL_DECOMMISSION));
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    Assert.assertEquals(Arrays.asList(NodeState.NEW, NodeState.RUNNING),
        nodesListManagerEventsNodeStateSequence);
    Assert
        .assertEquals("Active Nodes", initialActive - 1, cm.getNumActiveNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning + 1,
        cm.getNumDecommissioningNMs());
    return node;
  }

  private RMNodeImpl getUnhealthyNode() {
    RMNodeImpl node = getRunningNode();
    NodeHealthStatus status = NodeHealthStatus.newInstance(false, "sick",
        System.currentTimeMillis());
    NodeStatus nodeStatus = NodeStatus.newInstance(node.getNodeID(), 0,
      new ArrayList<ContainerStatus>(), null, status, null, null, null);
    node.handle(new RMNodeStatusEvent(node.getNodeID(), nodeStatus, null));
    Assert.assertEquals(NodeState.UNHEALTHY, node.getState());
    return node;
  }

  private RMNodeImpl getNewNode() {
    NodeId nodeId = BuilderUtils.newNodeId("localhost", 0);
    RMNodeImpl node = new RMNodeImpl(nodeId, rmContext, null, 0, 0, null, null, null);
    return node;
  }

  private RMNodeImpl getNewNode(Resource capability) {
    NodeId nodeId = BuilderUtils.newNodeId("localhost", 0);
    RMNodeImpl node = new RMNodeImpl(nodeId, rmContext, null, 0, 0, null, 
        capability, null);
    return node;
  }

  private RMNodeImpl getRebootedNode() {
    NodeId nodeId = BuilderUtils.newNodeId("localhost", 0);
    Resource capability = Resource.newInstance(4096, 4);
    RMNodeImpl node = new RMNodeImpl(nodeId, rmContext,null, 0, 0,
        null, capability, null);
    NodeStatus mockNodeStatus = createMockNodeStatus();

    node.handle(new RMNodeStartedEvent(node.getNodeID(), null, null,
        mockNodeStatus));
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    node.handle(new RMNodeEvent(node.getNodeID(), RMNodeEventType.REBOOTING));
    Assert.assertEquals(NodeState.REBOOTED, node.getState());
    return node;
  }

  @Test
  public void testAdd() {
    RMNodeImpl node = getNewNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    NodeStatus mockNodeStatus = createMockNodeStatus();
    node.handle(new RMNodeStartedEvent(node.getNodeID(), null, null,
        mockNodeStatus));
    Assert.assertEquals("Active Nodes", initialActive + 1, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    Assert.assertNotNull(nodesListManagerEvent);
    Assert.assertEquals(NodesListManagerEventType.NODE_USABLE, 
        nodesListManagerEvent.getType());
  }

  @Test
  public void testReconnect() {
    RMNodeImpl node = getRunningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialLost = cm.getNumLostNMs();
    int initialUnhealthy = cm.getUnhealthyNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();
    int initialRebooted = cm.getNumRebootedNMs();
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), node, null, null));
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Lost Nodes", initialLost, cm.getNumLostNMs());
    Assert.assertEquals("Unhealthy Nodes",
        initialUnhealthy, cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioned Nodes",
        initialDecommissioned, cm.getNumDecommisionedNMs());
    Assert.assertEquals("Rebooted Nodes",
        initialRebooted, cm.getNumRebootedNMs());
    Assert.assertEquals(NodeState.RUNNING, node.getState());
    Assert.assertNotNull(nodesListManagerEvent);
    Assert.assertEquals(NodesListManagerEventType.NODE_USABLE,
        nodesListManagerEvent.getType());
  }

  @Test
  public void testReconnectOnDecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    int initialDecommissioned = cm.getNumDecommisionedNMs();

    // Reconnect event with running app
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), node,
        getAppIdList(), null));
    // still decommissioning
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning,
        cm.getNumDecommissioningNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned,
        cm.getNumDecommisionedNMs());

    // Reconnect event without any running app
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), node, null, null));
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning - 1,
        cm.getNumDecommissioningNMs());
    Assert.assertEquals("Decommissioned Nodes", initialDecommissioned + 1,
        cm.getNumDecommisionedNMs());
  }

  @Test
  public void testReconnectWithNewPortOnDecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    Random r= new Random();
    node.setHttpPort(r.nextInt(10000));
    // Reconnect event with running app
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), node,
        getAppIdList(), null));
    // still decommissioning
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());

    node.setHttpPort(r.nextInt(10000));
    // Reconnect event without any running app
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), node, null, null));
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
  }

  @Test
  public void testResourceUpdateOnRunningNode() {
    RMNodeImpl node = getRunningNode();
    Resource oldCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", oldCapacity.getMemorySize(), 4096);
    assertEquals("CPU resource is not match.", oldCapacity.getVirtualCores(), 4);
    node.handle(new RMNodeResourceUpdateEvent(node.getNodeID(),
        ResourceOption.newInstance(Resource.newInstance(2048, 2),
            ResourceOption.OVER_COMMIT_TIMEOUT_MILLIS_DEFAULT)));
    Resource newCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", newCapacity.getMemorySize(), 2048);
    assertEquals("CPU resource is not match.", newCapacity.getVirtualCores(), 2);

    Assert.assertEquals(NodeState.RUNNING, node.getState());
    Assert.assertNotNull(nodesListManagerEvent);
    Assert.assertEquals(NodesListManagerEventType.NODE_USABLE,
        nodesListManagerEvent.getType());
  }

  @Test
  public void testDecommissioningOnRunningNode(){
    getDecommissioningNode();
  }

  @Test
  public void testResourceUpdateOnNewNode() {
    RMNodeImpl node = getNewNode(Resource.newInstance(4096, 4));
    Resource oldCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", oldCapacity.getMemorySize(), 4096);
    assertEquals("CPU resource is not match.", oldCapacity.getVirtualCores(), 4);
    node.handle(new RMNodeResourceUpdateEvent(node.getNodeID(),
        ResourceOption.newInstance(Resource.newInstance(2048, 2), 
            ResourceOption.OVER_COMMIT_TIMEOUT_MILLIS_DEFAULT)));
    Resource newCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", newCapacity.getMemorySize(), 2048);
    assertEquals("CPU resource is not match.", newCapacity.getVirtualCores(), 2);

    Assert.assertEquals(NodeState.NEW, node.getState());
  }

  @Test
  public void testResourceUpdateOnRebootedNode() {
    RMNodeImpl node = getRebootedNode();
    ClusterMetrics cm = ClusterMetrics.getMetrics();
    int initialActive = cm.getNumActiveNMs();
    int initialUnHealthy = cm.getUnhealthyNMs();
    int initialDecommissioning = cm.getNumDecommissioningNMs();
    Resource oldCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", oldCapacity.getMemorySize(), 4096);
    assertEquals("CPU resource is not match.", oldCapacity.getVirtualCores(), 4);
    node.handle(new RMNodeResourceUpdateEvent(node.getNodeID(), ResourceOption
        .newInstance(Resource.newInstance(2048, 2),
            ResourceOption.OVER_COMMIT_TIMEOUT_MILLIS_DEFAULT)));
    Resource newCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", newCapacity.getMemorySize(), 2048);
    assertEquals("CPU resource is not match.", newCapacity.getVirtualCores(), 2);

    Assert.assertEquals(NodeState.REBOOTED, node.getState());
    Assert.assertEquals("Active Nodes", initialActive, cm.getNumActiveNMs());
    Assert.assertEquals("Unhealthy Nodes", initialUnHealthy,
        cm.getUnhealthyNMs());
    Assert.assertEquals("Decommissioning Nodes", initialDecommissioning,
        cm.getNumDecommissioningNMs());
  }

  // Test unhealthy report on a decommissioning node will make it
  // keep decommissioning as long as there's a running or keep alive app.
  // Otherwise, it will go to decommissioned
  @Test
  public void testDecommissioningUnhealthy() {
    RMNodeImpl node = getDecommissioningNode();
    NodeHealthStatus status = NodeHealthStatus.newInstance(false, "sick",
        System.currentTimeMillis());
    List<ApplicationId> keepAliveApps = new ArrayList<>();
    keepAliveApps.add(BuilderUtils.newApplicationId(1, 1));
    NodeStatus nodeStatus = NodeStatus.newInstance(node.getNodeID(), 0,
        null, keepAliveApps, status, null, null, null);
    node.handle(new RMNodeStatusEvent(node.getNodeID(), nodeStatus, null));
    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    nodeStatus.setKeepAliveApplications(null);
    node.handle(new RMNodeStatusEvent(node.getNodeID(), nodeStatus, null));
    Assert.assertEquals(NodeState.DECOMMISSIONED, node.getState());
  }

  @Test
  public void testReconnnectUpdate() {
    final String nmVersion1 = "nm version 1";
    final String nmVersion2 = "nm version 2";
    RMNodeImpl node = getRunningNode(nmVersion1);
    Assert.assertEquals(nmVersion1, node.getNodeManagerVersion());
    RMNodeImpl reconnectingNode = getRunningNode(nmVersion2);
    node.handle(new RMNodeReconnectEvent(node.getNodeID(), reconnectingNode,
        null, null));
    Assert.assertEquals(nmVersion2, node.getNodeManagerVersion());
  }

  @Test
  public void testContainerExpire() throws Exception {
    ContainerAllocationExpirer mockExpirer =
        mock(ContainerAllocationExpirer.class);
    ApplicationId appId =
        ApplicationId.newInstance(System.currentTimeMillis(), 1);
    ApplicationAttemptId appAttemptId =
        ApplicationAttemptId.newInstance(appId, 1);
    rmContext.getRMApps().put(appId, Mockito.mock(RMApp.class));
    ContainerId containerId1 = ContainerId.newContainerId(appAttemptId, 1L);
    ContainerId containerId2 = ContainerId.newContainerId(appAttemptId, 2L);
    AllocationExpirationInfo expirationInfo1 =
        new AllocationExpirationInfo(containerId1);
    AllocationExpirationInfo expirationInfo2 =
        new AllocationExpirationInfo(containerId2);
    mockExpirer.register(expirationInfo1);
    mockExpirer.register(expirationInfo2);
    verify(mockExpirer).register(expirationInfo1);
    verify(mockExpirer).register(expirationInfo2);
    ((RMContextImpl) rmContext).setContainerAllocationExpirer(mockExpirer);
    RMNodeImpl rmNode = getRunningNode();
    ContainerStatus status1 =
        ContainerStatus
          .newInstance(containerId1, ContainerState.RUNNING, "", 0);
    ContainerStatus status2 =
        ContainerStatus.newInstance(containerId2, ContainerState.COMPLETE, "",
          0);
    List<ContainerStatus> statusList = new ArrayList<ContainerStatus>();
    statusList.add(status1);
    statusList.add(status2);
    RMNodeStatusEvent statusEvent = getMockRMNodeStatusEvent(statusList);
    rmNode.handle(statusEvent);
    verify(mockExpirer).unregister(expirationInfo1);
    verify(mockExpirer).unregister(expirationInfo2);
  }

  @Test
  public void testResourceUpdateOnDecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    Resource oldCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", oldCapacity.getMemorySize(), 4096);
    assertEquals("CPU resource is not match.", oldCapacity.getVirtualCores(), 4);
    node.handle(new RMNodeResourceUpdateEvent(node.getNodeID(),
        ResourceOption.newInstance(Resource.newInstance(2048, 2),
            ResourceOption.OVER_COMMIT_TIMEOUT_MILLIS_DEFAULT)));
    Resource originalCapacity = node.getOriginalTotalCapability();
    assertEquals("Memory resource is not match.", originalCapacity.getMemorySize(), oldCapacity.getMemorySize());
    assertEquals("CPU resource is not match.", originalCapacity.getVirtualCores(), oldCapacity.getVirtualCores());
    Resource newCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", newCapacity.getMemorySize(), 2048);
    assertEquals("CPU resource is not match.", newCapacity.getVirtualCores(), 2);

    Assert.assertEquals(NodeState.DECOMMISSIONING, node.getState());
    Assert.assertNotNull(nodesListManagerEvent);
    Assert.assertEquals(NodesListManagerEventType.NODE_DECOMMISSIONING,
        nodesListManagerEvent.getType());
  }

  @Test
  public void testResourceUpdateOnRecommissioningNode() {
    RMNodeImpl node = getDecommissioningNode();
    Resource oldCapacity = node.getTotalCapability();
    assertEquals("Memory resource is not match.", oldCapacity.getMemorySize(), 4096);
    assertEquals("CPU resource is not match.", oldCapacity.getVirtualCores(), 4);
    assertFalse("updatedCapability should be false.",
        node.isUpdatedCapability());
    node.handle(new RMNodeEvent(node.getNodeID(),
        RMNodeEventType.RECOMMISSION));
    Resource originalCapacity = node.getOriginalTotalCapability();
    assertEquals("Original total capability not null after recommission", null, originalCapacity);
    assertTrue("updatedCapability should be set.", node.isUpdatedCapability());
  }

  @Test
  public void testDisappearingContainer() {
    ContainerId cid1 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(1, 1), 1), 1);
    ContainerId cid2 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(
            BuilderUtils.newApplicationId(2, 2), 2), 2);
    ArrayList<ContainerStatus> containerStats =
        new ArrayList<ContainerStatus>();
    containerStats.add(ContainerStatus.newInstance(cid1,
        ContainerState.RUNNING, "", -1));
    containerStats.add(ContainerStatus.newInstance(cid2,
        ContainerState.RUNNING, "", -1));
    node = getRunningNode();
    node.handle(getMockRMNodeStatusEvent(containerStats));
    assertEquals("unexpected number of running containers",
        2, node.getLaunchedContainers().size());
    Assert.assertTrue("first container not running",
        node.getLaunchedContainers().contains(cid1));
    Assert.assertTrue("second container not running",
        node.getLaunchedContainers().contains(cid2));
    assertEquals("unexpected number of running containers",
        2, node.getUpdatedExistContainers().size());
    Assert.assertTrue("first container not running",
        node.getUpdatedExistContainers().containsKey(cid1));
    Assert.assertTrue("second container not running",
        node.getUpdatedExistContainers().containsKey(cid2));
    assertEquals("already completed containers",
        0, completedContainers.size());
    containerStats.remove(0);
    node.handle(getMockRMNodeStatusEvent(containerStats));
    assertEquals("expected one container to be completed",
        1, completedContainers.size());
    ContainerStatus cs = completedContainers.get(0);
    assertEquals("first container not the one that completed",
        cid1, cs.getContainerId());
    assertEquals("completed container not marked complete",
        ContainerState.COMPLETE, cs.getState());
    assertEquals("completed container not marked aborted",
        ContainerExitStatus.ABORTED, cs.getExitStatus());
    Assert.assertTrue("completed container not marked missing",
        cs.getDiagnostics().contains("not reported"));
    assertEquals("unexpected number of running containers",
        1, node.getLaunchedContainers().size());
    Assert.assertTrue("second container not running",
        node.getLaunchedContainers().contains(cid2));
    assertEquals("unexpected number of running containers",
        1, node.getUpdatedExistContainers().size());
    Assert.assertTrue("second container not running",
        node.getUpdatedExistContainers().containsKey(cid2));
  }

  @Test
  public void testForHandlingDuplicatedCompltedContainers() {
    NodeStatus mockNodeStatus = createMockNodeStatus();
    // Start the node
    node.handle(new RMNodeStartedEvent(null, null, null, mockNodeStatus));
    // Add info to the queue first
    node.setNextHeartBeat(false);

    ContainerId completedContainerId1 = BuilderUtils.newContainerId(
        BuilderUtils.newApplicationAttemptId(BuilderUtils.newApplicationId(0, 0), 0), 0);

    RMNodeStatusEvent statusEvent1 = getMockRMNodeStatusEvent(null);

    ContainerStatus containerStatus1 = getMockContainerStatus(
        completedContainerId1, null, ContainerState.COMPLETE);

    doReturn(Collections.singletonList(containerStatus1)).when(statusEvent1)
        .getContainers();

    verify(scheduler, times(1)).handle(any(NodeAddedSchedulerEvent.class));
    node.handle(statusEvent1);
    verify(scheduler, times(1)).handle(any(NodeAddedSchedulerEvent.class));
    Assert.assertEquals(1, node.getQueueSize());
    Assert.assertEquals(1, node.getCompletedContainers().size());

    // test for duplicate entries
    node.handle(statusEvent1);
    Assert.assertEquals(1, node.getQueueSize());

    // send clean up container event
    node.handle(new RMNodeFinishedContainersPulledByAMEvent(node.getNodeID(),
        Collections.singletonList(completedContainerId1)));

    NodeHeartbeatResponse hbrsp =
        Records.newRecord(NodeHeartbeatResponse.class);
    node.setAndUpdateNodeHeartbeatResponse(hbrsp);

    Assert.assertEquals(1, hbrsp.getContainersToBeRemovedFromNM().size());
    Assert.assertEquals(0, node.getCompletedContainers().size());
  }

  @Test
  public void testFinishedContainersPulledByAMOnNewNode() {
    RMNodeImpl rmNode = getNewNode();
    NodeId nodeId = BuilderUtils.newNodeId("localhost", 0);

    rmNode.handle(new RMNodeFinishedContainersPulledByAMEvent(nodeId,
        getContainerIdList()));
    Assert.assertEquals(1, rmNode.getContainersToBeRemovedFromNM().size());

  }

  private void calcIntervalTest(RMNodeImpl rmNode, ResourceUtilization nodeUtil,
      long hbDefault, long hbMin, long hbMax, float speedup, float slowdown,
                                float cpuUtil, long expectedHb) {
    nodeUtil.setCPU(cpuUtil);
    rmNode.setNodeUtilization(nodeUtil);
    long hbInterval = rmNode.calculateHeartBeatInterval(hbDefault, hbMin, hbMax,
        speedup, slowdown);
    assertEquals("heartbeat interval incorrect", expectedHb, hbInterval);
  }

  @Test
  public void testCalculateHeartBeatInterval() {
    RMNodeImpl rmNode = getRunningNode();
    Resource nodeCapability = rmNode.getTotalCapability();
    ClusterMetrics metrics = ClusterMetrics.getMetrics();
    // Set cluster capability to 10 * nodeCapability
    int vcoreUnit = nodeCapability.getVirtualCores();
    rmNode.setPhysicalResource(nodeCapability);
    int clusterVcores = vcoreUnit * 10;
    metrics.incrCapability(
        Resource.newInstance(10 * nodeCapability.getMemorySize(),
            clusterVcores));

    long hbDefault = 2000;
    long hbMin = 1500;
    long hbMax = 2500;
    float speedup = 1.0F;
    float slowdown = 1.0F;
    metrics.incrUtilizedVirtualCores(vcoreUnit * 5); // 50 % cluster util
    ResourceUtilization nodeUtil = ResourceUtilization.newInstance(
        1024, vcoreUnit, 0.0F * vcoreUnit); // 0% rmNode util
    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.0F, hbMin); // 0%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.10F, hbMin); // 10%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.20F, hbMin); // 20%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.30F, 1600); // 30%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.40F, 1800); // 40%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.50F, hbDefault); // 50%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.60F, 2200); // 60%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.70F, 2400); // 70%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.80F, hbMax); // 80%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.90F, hbMax); // 90%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 1.0F, hbMax); // 100%

    // Try with 50% speedup/slowdown factors
    speedup = 0.5F;
    slowdown = 0.5F;
    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.0F, hbMin); // 0%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.10F, 1600); // 10%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.20F, 1700); // 20%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.30F, 1800); // 30%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.40F, 1900); // 40%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.50F, hbDefault); // 50%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.60F, 2100); // 60%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.70F, 2200); // 70%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.80F, 2300); // 80%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.90F, 2400); // 90%

    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 1.0F, hbMax); // 100%

    // With Physical Resource null, it should always return default
    rmNode.setPhysicalResource(null);
    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 0.1F, hbDefault); // 10%
    calcIntervalTest(rmNode, nodeUtil, hbDefault, hbMin, hbMax,
        speedup, slowdown, vcoreUnit * 1.0F, hbDefault); // 100%
  }
}
