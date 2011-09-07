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

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test our zookeeper client (and the utilities it has).
 */
public class RobustZooKeeperTest {

  private static final int ZK_PORT = 29980;
  private static final Random RANDOM = new Random();

  private EmbeddedZookeeperServer zookeeperServer;
  private RobustZooKeeper rbz;

  @Before
  public void setup() throws QuorumPeerConfig.ConfigException, IOException {
    zookeeperServer = EmbeddedZookeeperServer.builder().clientPort(ZK_PORT).build();
    rbz = new RobustZooKeeper("localhost:" + ZK_PORT);
  }

  @After
  public void tearDown() throws InterruptedException {
    zookeeperServer.shutdown();
    rbz.shutdown();
  }

  private void checkNoRemainingLocks(String lockName) throws IOException, InterruptedException, KeeperException {
    ZooKeeper verifier = new ZooKeeper("localhost:" + ZK_PORT, Integer.MAX_VALUE, new Watcher() {
      @Override
      public void process(WatchedEvent event) {
        // no-op watcher
      }
    });
    List<String> children = verifier.getChildren(RobustZooKeeper.LOCK_NODE_PREFIX + lockName, false);
    Assert.assertEquals(0, children.size());
  }

  @Test
  public void testLockBasic() throws IOException, InterruptedException, KeeperException {
    final AtomicInteger lockCounter = new AtomicInteger(0);

    rbz.withLock("testLock", new Runnable() {
      @Override
      public void run() {
        lockCounter.incrementAndGet();
      }
    });

    Thread.sleep(200);

    Assert.assertEquals(1, lockCounter.get());

    checkNoRemainingLocks("testLock");
  }

  @Test
  public void testSingleClientLockContention() throws IOException, InterruptedException, KeeperException {
    final AtomicInteger withLockRuns = new AtomicInteger(0);
    final AtomicInteger currentWithLockRuns = new AtomicInteger(0);

    for (int i = 0; i < 10; i++) {
      rbz.withLock("testLock", new Runnable() {
        @Override
        public void run() {
          withLockRuns.incrementAndGet();
          int currentlyRunning = currentWithLockRuns.incrementAndGet();
          Assert.assertEquals(1, currentlyRunning);
          try {
            Thread.sleep(RANDOM.nextInt(100));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          currentlyRunning = currentWithLockRuns.decrementAndGet();
          Assert.assertEquals(0, currentlyRunning);
        }
      });
    }

    Thread.sleep(1000);

    Assert.assertEquals(10, withLockRuns.get());
    Assert.assertEquals(0, currentWithLockRuns.get());

    checkNoRemainingLocks("testLock");
  }

  @Test
  public void testMultiClientLockContention() throws IOException, InterruptedException, KeeperException {
    final AtomicInteger withLockRuns = new AtomicInteger(0);
    final AtomicInteger currentWithLockRuns = new AtomicInteger(0);

    for (int i = 0; i < 5; i++) {
      RobustZooKeeper zk = new RobustZooKeeper("localhost:" + ZK_PORT);
      zk.withLock("testLock", new Runnable() {
        @Override
        public void run() {
          withLockRuns.incrementAndGet();
          int currentlyRunning = currentWithLockRuns.incrementAndGet();
          Assert.assertEquals(1, currentlyRunning);
          try {
            Thread.sleep(RANDOM.nextInt(100));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          currentlyRunning = currentWithLockRuns.decrementAndGet();
          Assert.assertEquals(0, currentlyRunning);
        }
      });
    }

    Thread.sleep(1000);

    Assert.assertEquals(5, withLockRuns.get());
    Assert.assertEquals(0, currentWithLockRuns.get());

    checkNoRemainingLocks("testLock");
  }

  @Test
  public void testLockWithClientFailure() throws IOException, InterruptedException, KeeperException {
    final AtomicInteger withLockRuns = new AtomicInteger(0);
    final AtomicInteger currentWithLockRuns = new AtomicInteger(0);

    for (int i = 0; i < 5; i++) {
      final RobustZooKeeper zk = new RobustZooKeeper("localhost:" + ZK_PORT);

      if (i == 0) {
        // client 0 fails while holding the lock
          zk.withLock("testLock", new Runnable() {
            @Override
            public void run() {
              withLockRuns.incrementAndGet();
              try {
                zk.getClient().close(); // NEVER do this for real - but it's a reasonable way to simulate a failure
              } catch (InterruptedException e) {
                Assert.fail();
              } catch (IOException e) {
                Assert.fail();
              }
            }
          });

      } else {
        zk.withLock("testLock", new Runnable() {
          @Override
          public void run() {
            withLockRuns.incrementAndGet();
            int currentlyRunning = currentWithLockRuns.incrementAndGet();
            Assert.assertEquals(1, currentlyRunning);
            try {
              Thread.sleep(RANDOM.nextInt(100));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
            currentlyRunning = currentWithLockRuns.decrementAndGet();
            Assert.assertEquals(0, currentlyRunning);
          }
        });
      }
    }

    Thread.sleep(1000);

    Assert.assertEquals(5, withLockRuns.get());
    Assert.assertEquals(0, currentWithLockRuns.get());

    checkNoRemainingLocks("testLock");
  }
}
