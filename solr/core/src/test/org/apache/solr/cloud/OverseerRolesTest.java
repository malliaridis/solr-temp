/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import static org.apache.solr.cloud.OverseerCollectionConfigSetProcessor.getLeaderNode;
import static org.apache.solr.cloud.OverseerTaskProcessor.getSortedElectionNodes;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.util.TimeSources;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OverseerRolesTest extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(4).addConfig("conf", configset("cloud-minimal")).configure();
  }

  public static void waitForNewOverseer(
      int seconds, Predicate<String> state, boolean failOnIntermediateTransition) throws Exception {
    TimeOut timeout = new TimeOut(seconds, TimeUnit.SECONDS, TimeSources.NANO_TIME);
    String current = null;
    while (timeout.hasTimedOut() == false) {
      String prev = current;
      current = OverseerCollectionConfigSetProcessor.getLeaderNode(zkClient());
      if (state.test(current)) return;
      else if (failOnIntermediateTransition) {
        if (prev != null && current != null && !current.equals(prev)) {
          fail(
              "There was an intermediate transition, previous: "
                  + prev
                  + ", intermediate transition: "
                  + current);
        }
      }
      Thread.sleep(100);
    }
    fail("Timed out waiting for overseer state change. The current overseer is: " + current);
  }

  public static void waitForNewOverseer(
      int seconds, String expected, boolean failOnIntermediateTransition) throws Exception {
    log.info("Expecting node: {}", expected);
    waitForNewOverseer(seconds, s -> Objects.equals(s, expected), failOnIntermediateTransition);
  }

  private JettySolrRunner getOverseerJetty() throws Exception {
    String overseer = getLeaderNode(zkClient());
    URI overseerUrl = URI.create("http://" + overseer.substring(0, overseer.indexOf('_')));
    int hostPort = overseerUrl.getPort();
    for (JettySolrRunner jetty : cluster.getJettySolrRunners()) {
      try {
        if (jetty.getBaseUrl().getPort() == hostPort) return jetty;
      } catch (IllegalStateException e) {

      }
    }
    fail("Couldn't find overseer node " + overseer);
    return null; // to keep the compiler happy
  }

  private void logOverseerState() throws KeeperException, InterruptedException {
    if (log.isInfoEnabled()) {
      log.info("Overseer: {}", getLeaderNode(zkClient()));
      log.info(
          "Election queue: {}",
          getSortedElectionNodes(zkClient(), "/overseer_elect/election")); // nowarn
    }
  }

  @Test
  public void testOverseerRole() throws Exception {
    if (new CollectionAdminRequest.RequestApiDistributedProcessing()
        .process(cluster.getSolrClient())
        .getIsCollectionApiDistributed()) {
      log.info("Skipping test because Collection API is distributed");
      return;
    }

    logOverseerState();
    List<String> nodes =
        OverseerCollectionConfigSetProcessor.getSortedOverseerNodeNames(zkClient());
    // Remove the OVERSEER role, in case it was already assigned by another test in this suite
    for (String node : nodes) {
      CollectionAdminRequest.removeRole(node, "overseer").process(cluster.getSolrClient());
    }
    String overseer1 = OverseerCollectionConfigSetProcessor.getLeaderNode(zkClient());
    nodes.remove(overseer1);

    Collections.shuffle(nodes, random());
    String overseer2 = nodes.get(0);
    log.info("### Setting overseer designate {}", overseer2);

    CollectionAdminRequest.addRole(overseer2, "overseer").process(cluster.getSolrClient());

    waitForNewOverseer(15, overseer2, false);

    // add another node as overseer
    nodes.remove(overseer2);
    Collections.shuffle(nodes, random());

    String overseer3 = nodes.get(0);
    log.info("### Adding another overseer designate {}", overseer3);
    CollectionAdminRequest.addRole(overseer3, "overseer").process(cluster.getSolrClient());

    // kill the current overseer, and check that the new designate becomes the new overseer
    JettySolrRunner leaderJetty = getOverseerJetty();
    logOverseerState();

    leaderJetty.stop();
    waitForNewOverseer(10, overseer3, false);

    // add another node as overseer
    nodes.remove(overseer3);
    Collections.shuffle(nodes, random());
    String overseer4 = nodes.get(0);
    log.info("### Adding last overseer designate {}", overseer4);
    CollectionAdminRequest.addRole(overseer4, "overseer").process(cluster.getSolrClient());
    logOverseerState();

    // remove the overseer role from the current overseer
    CollectionAdminRequest.removeRole(overseer3, "overseer").process(cluster.getSolrClient());
    waitForNewOverseer(15, overseer4, false);

    // Add it back again - we now have two delegates, 4 and 3
    CollectionAdminRequest.addRole(overseer3, "overseer").process(cluster.getSolrClient());

    // explicitly tell the overseer to quit
    String leaderId = OverseerCollectionConfigSetProcessor.getLeaderId(zkClient());
    String leader = OverseerCollectionConfigSetProcessor.getLeaderNode(zkClient());
    log.info("### Sending QUIT to overseer {}", leader);
    getOverseerJetty()
        .getCoreContainer()
        .getZkController()
        .getOverseer()
        .sendQuitToOverseer(leaderId);

    waitForNewOverseer(15, s -> Objects.equals(leader, s) == false, false);

    Thread.sleep(1000);

    logOverseerState();
    assertTrue(
        "The old leader should have rejoined election",
        OverseerCollectionConfigSetProcessor.getSortedOverseerNodeNames(zkClient())
            .contains(leader));

    leaderJetty.start(); // starting this back, just for good measure
  }

  @Test
  public void testDesignatedOverseerRestarts() throws Exception {
    if (new CollectionAdminRequest.RequestApiDistributedProcessing()
        .process(cluster.getSolrClient())
        .getIsCollectionApiDistributed()) {
      log.info("Skipping test because Collection API is distributed");
      return;
    }
    logOverseerState();
    // Remove the OVERSEER role, in case it was already assigned by another test in this suite
    List<String> nodes =
        OverseerCollectionConfigSetProcessor.getSortedOverseerNodeNames(zkClient());
    // We want to remove from the last (in election order) to the first.
    // This way the current overseer will have its role removed last,
    // so there will not be any elections.
    Collections.reverse(nodes);
    for (String node : nodes) {
      CollectionAdminRequest.removeRole(node, "overseer").process(cluster.getSolrClient());
    }
    String overseer1 = OverseerCollectionConfigSetProcessor.getLeaderNode(zkClient());
    int counter = 0;
    while (overseer1 == null && counter < 10) {
      overseer1 = OverseerCollectionConfigSetProcessor.getLeaderNode(zkClient());
      Thread.sleep(1000);
    }

    // Setting overseer role to the current overseer
    CollectionAdminRequest.addRole(overseer1, "overseer").process(cluster.getSolrClient());
    waitForNewOverseer(15, overseer1, false);
    JettySolrRunner leaderJetty = getOverseerJetty();

    logOverseerState();
    // kill the current overseer, and check that the next node in the election queue assumes
    // leadership
    leaderJetty.stop();
    log.info("Killing designated overseer: {}", overseer1);

    // after 5 seconds, bring back dead designated overseer and assert that it assumes leadership
    // "right away", i.e. without any other node assuming leadership before this node becomes
    // leader.
    Thread.sleep(5);
    logOverseerState();
    log.info("Starting back the prioritized overseer..");
    leaderJetty.start();
    // assert that there is just a single leadership transition
    waitForNewOverseer(15, overseer1, true);
  }
}
