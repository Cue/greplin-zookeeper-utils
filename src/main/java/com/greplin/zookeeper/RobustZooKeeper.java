/*
 * Copyright 2010 The Greplin Zookeeper Utility Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.greplin.zookeeper;

import com.google.common.base.Preconditions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The org.apache.zookeeper.ZooKeeper client won't automatically reconnect, and is a bit low-level for many uses
 * (e.g., no built-in locks)
 * <p/>
 * This handles the reconnection logic, and implements some common idioms that are useful to have available.
 */
public class RobustZooKeeper {

  private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0); // help make log msgs clearer
  
  private static final Log log = LogFactory.getLog(RobustZooKeeper.class);

  protected static final String LOCK_NODE_PREFIX = "/_greplin_robustZK_"; // prefixes all node names
  protected static final String LOCK_NAME = "lock-";
  protected static final List<ACL> DEFAULT_ACL = ZooDefs.Ids.OPEN_ACL_UNSAFE;

  private volatile ZooKeeper client;
  private final String ensembleAddress;
  private final Lock reconnectLock;
  private final AtomicBoolean shutdown;
  private final AtomicInteger reconnectCount;
  private final int clientNumber;

  private class ConnectionWatcher implements Watcher {
    @Override
    public void process(WatchedEvent event) {
      // eventually, we should probably force a 'reconnect' on seeing an unexpected disconnect, but for now don't care
    }
  }

  public RobustZooKeeper(String ensembleAddresses) throws IOException {
    this.reconnectCount = new AtomicInteger(-1); // start at -1 so that the initial connection doesn't count
    this.shutdown = new AtomicBoolean(false);
    this.reconnectLock = new ReentrantLock();
    this.ensembleAddress = ensembleAddresses;
    this.client = null;
    clientNumber = INSTANCE_COUNTER.incrementAndGet();
  }

  private static boolean isAlive(ZooKeeper zk) {
    return zk != null && zk.getState().isAlive();
  }

  /**
   * Get the number of times the underlying zookeeper connection has had to be reconnected.
   * Useful for monitoring/etc
   *
   * @return The number of reconnections that have successfully completed.
   */
  public int getReconnectionCount() {
    return reconnectCount.get();
  }

  public void shutdown() throws InterruptedException {
    boolean alreadyShutdown = this.shutdown.getAndSet(true);

    if (!alreadyShutdown) {
      if (this.client != null) {
        this.client.close();
        this.client = null;
      }
    }
  }

  // blocking zookeeper 'sync' on /
  public void sync() throws IOException, InterruptedException {

    log.info("Called sync() on client " + clientNumber);
    final CountDownLatch waitForSync = new CountDownLatch(1);
    final ZooKeeper c = getClient();
    assert c.getState().isAlive();

    c.sync("/", new AsyncCallback.VoidCallback() {
      @Override
      public void processResult(int rc, String path, Object ctx) {
        log.info("Sync callback triggered on client " + clientNumber);
        waitForSync.countDown();
      }
    }, null);

    log.info("Waitng for sync callback on client " + clientNumber);
    waitForSync.await();

    log.info("sync() finished on " + clientNumber);
    return;
  }

  // returns an 'alive' client - with no lock in the common case
  public ZooKeeper getClient() throws IOException {
    Preconditions.checkState(!shutdown.get());
    ZooKeeper res = client;

    if (!isAlive(res)) {
      reconnectLock.lock();
      try {
        res = client;
        if (!isAlive(res)) {
          res = new ZooKeeper(ensembleAddress, Integer.MAX_VALUE, new ConnectionWatcher());
          reconnectCount.incrementAndGet();
          client = res;
        }
      } finally {
        reconnectLock.unlock();
      }
    }

    // not actually guaranteed to be true - the connection could have died between when I last checked and now
    assert isAlive(res);
    return res;
  }

  private static String getLockParent(String lockName) {
    return LOCK_NODE_PREFIX + lockName;
  }

  private static String getLockNode(String lockName) {
    return getLockParent(lockName) + "/" + LOCK_NAME;
  }

  /**
   * Execute the given Runnable once we obtain the zookeeper lock with the given name.
   * Automatically release the lock once the Runnable completes.
   * <p/>
   * We use the 'lock' recipe from the ZooKeeper documentation to help prevent stampedes:
   * 1. Call create( ) with a pathname of "_locknode_/lock-" and the sequence and ephemeral flags set.
   * 2. Call getChildren() on the lock node without setting the watch flag (this is important to avoid the herd effect).
   * 3. If the pathname created in step 1 has the lowest sequence number suffix, the client
   * has the lock and the client exits the protocol.
   * 4. The client calls exists( ) with the watch flag set on the path in the lock directory with the next
   * lowest sequence number.
   * 5. if exists( ) returns false, go to step 2. Otherwise, wait for a notification for the pathname from the
   * previous step before going to step 2.
   *
   * @param lockName The name of the lock you want to obtain in Zookeeper before running the action
   * @param action   The action to execute, while holding the lock.
   * @throws java.io.IOException
   * @throws InterruptedException
   * @throws org.apache.zookeeper.KeeperException
   *
   */
  public void withLock(final String lockName, final Runnable action)
      throws IOException, InterruptedException, KeeperException {
    Preconditions.checkArgument(!lockName.contains("/"));
    withLock(lockName, new byte[0], action);
  }

  public void withLock(final String lockName, byte[] lockData, final Runnable action)
      throws IOException, InterruptedException, KeeperException {
    Preconditions.checkArgument(!lockName.contains("/"));
    try {
      getClient().create(getLockParent(lockName), new byte[0], DEFAULT_ACL, CreateMode.PERSISTENT);
    } catch (KeeperException.NodeExistsException e) {
      // ignore - the prior 'create' is only needed for the first locker
    }

    final String myNodeFullyQualified = getClient().create(getLockNode(lockName), lockData, DEFAULT_ACL,
        CreateMode.EPHEMERAL_SEQUENTIAL);
    log.info("Client " + clientNumber +" attempts to get the '"  + lockName + "'" + " lock: " + myNodeFullyQualified);

    final String[] nodePathname = myNodeFullyQualified.split("/");
    final String relativePath = nodePathname[nodePathname.length - 1];

    lockRecipeStepTwo(myNodeFullyQualified, relativePath, lockName, action);
  }


  private void lockRecipeStepTwo(final String fullPath, final String relativePath,
                                 final String lockName, final Runnable action)
      throws IOException, InterruptedException, KeeperException {

    log.info("Client " + clientNumber + " at start of lockRecipeStepTwo with relativePath = " + relativePath);
    if (shutdown.get()) {
      log.warn("Client " + clientNumber + " is shutdown - so I'm going to give up my attempt to lock");
      return;
    }
    
    // step 2
    final List<String> children = getClient().getChildren(getLockParent(lockName), false);
    assert children.size() > 0; // at the ver least, my node should be there.
    Collections.sort(children);

    // step 3
    if (relativePath.equals(children.get(0))) {
      log.info("Client " + clientNumber + " has the lowest number lock attempt ("
          + relativePath + "), so it holds the lock");
      try {
        action.run();
      } finally {
        log.info("Client " + clientNumber + " finished doing my work with "
            + relativePath + ", so I'm giving up the lock.");
        try {
          getClient().delete(fullPath, -1);
        } catch (KeeperException.NoNodeException e) {
          log.warn("After I finished running an action with lock " + lockName + " the actions lock node ("
              + fullPath + ") no longer exists. This should only happen if you manually deleted "
              + fullPath + " or there was an underlying network failure, and we had to reconnect");
        }
      }
      return;
    }

    // step 4
    final int indexOfNodeBefore = children.indexOf(relativePath) - 1;
    if (indexOfNodeBefore < 0) {
      throw new IllegalStateException("indexOfNodeBefore is " + indexOfNodeBefore + ", and children are: " + children);
    }

    final String nodeBeforeMine = children.get(indexOfNodeBefore);
    log.info("Client " + clientNumber + "  (at " + relativePath + ") is setting a watch on the node before me ("
        + nodeBeforeMine + ")");
    final Stat exists = getClient().exists(getLockParent(lockName) + "/" + nodeBeforeMine, new Watcher() {
      @Override
      public void process(WatchedEvent event) {
        try {
          log.info("Client " + clientNumber + ", when watching " + nodeBeforeMine + ", got notified: " + event);
          lockRecipeStepTwo(fullPath, relativePath, lockName, action);
        } catch (Exception e) {
          log.warn("Unable to execute action with lock " + lockName, e);
        }
      }
    });

    // step 5
    if (exists == null) {
      log.info("Client " + clientNumber + " expected " + nodeBeforeMine
          + " to exist - but it doesn't so re-running step 2");
      lockRecipeStepTwo(fullPath, relativePath, lockName, action);
    }
  }

  public int getInstanceNumber() {
    return clientNumber;
  }
}
