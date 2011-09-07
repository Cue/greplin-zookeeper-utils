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
import com.google.common.io.Files;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Make it easy to run a real zookeeper server (in a 1-node quorom) in the current JVM. Useful for testing.
 */
public class EmbeddedZookeeperServer {

  private static final Log log = LogFactory.getLog(EmbeddedZookeeperServer.class);

  private final AtomicBoolean shutdown;
  private final int clientPort;
  private final File dataDir;
  private final long tickTime;


  private final Thread serverThread;
  private final NIOServerCnxn.Factory connectionFactory;
  private final ZooKeeperServer zooKeeperServer;

  public EmbeddedZookeeperServer(Integer clientPort, File dataDir, Long tickTime) throws
      QuorumPeerConfig.ConfigException, IOException {
    Preconditions.checkNotNull(dataDir);
    Preconditions.checkNotNull(clientPort);
    Preconditions.checkNotNull(tickTime);

    Preconditions.checkArgument(clientPort > 0);
    Preconditions.checkArgument(clientPort < 65536);
    Preconditions.checkArgument(tickTime > 0);

    this.shutdown = new AtomicBoolean(false);
    this.clientPort = clientPort;
    this.dataDir = dataDir;
    this.tickTime = tickTime;

    Properties properties = new Properties();
    properties.setProperty("tickTime", tickTime.toString());
    properties.setProperty("clientPort", clientPort.toString());
    properties.setProperty("dataDir", dataDir.getAbsolutePath());

    QuorumPeerConfig qpc = new QuorumPeerConfig();
    try {
      qpc.parseProperties(properties);
    } catch (IOException e) {
      throw new RuntimeException("This is impossible - no I/O to configure a quorumpeer from a properties object", e);
    }

    // don't ask me why ...
    ServerConfig config = new ServerConfig();
    config.readFrom(qpc);

    log.info("Starting embedded zookeeper server on port " + clientPort);
    this.zooKeeperServer = new ZooKeeperServer();
    this.zooKeeperServer.setTxnLogFactory(new FileTxnSnapLog(new File(config.getDataLogDir()),
        new File(config.getDataDir())));
    this.zooKeeperServer.setTickTime(config.getTickTime());
    this.zooKeeperServer.setMinSessionTimeout(config.getMinSessionTimeout());
    this.zooKeeperServer.setMaxSessionTimeout(config.getMaxSessionTimeout());

    this.connectionFactory = new NIOServerCnxn.Factory(config.getClientPortAddress(), config.getMaxClientCnxns());
    try {
      connectionFactory.startup(zooKeeperServer);
    } catch (InterruptedException e) {
      throw new RuntimeException("Server Interrupted", e);
    }

    serverThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          connectionFactory.join();
        } catch (InterruptedException e) {
          log.error("Zookeeper Connection Factory Interrupted", e);
        }
      }
    });

    serverThread.start();
  }

  public void shutdown() throws InterruptedException {
    boolean alreadyShutdown = shutdown.getAndSet(true);

    if (alreadyShutdown) {
      return;
    } else {
      connectionFactory.shutdown();

      // block until the server is actually shutdown
      serverThread.join();
    }
  }

  public int getClientPort() {
    return clientPort;
  }

  public File getDataDir() {
    return dataDir;
  }

  public long getTickTime() {
    return tickTime;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder pattern for starting an embedded zookeeper server (so you don't have to set stuff you don't care about).
   */
  public static class Builder {

    // some reasonable defaults
    private int clientPort = 2181;
    private File dataDir = Files.createTempDir();
    private long tickTime = 2000;

    public Builder() {
      // no required parameters - my defaults work pretty well.
    }

    public Builder clientPort(int port) {
      this.clientPort = port;
      return this;
    }

    public Builder dataDir(File dataDir) {
      this.dataDir = dataDir;
      return this;
    }

    public Builder tickTime(Long tickTime) {
      this.tickTime = tickTime;
      return this;
    }

    public EmbeddedZookeeperServer build() throws IOException, QuorumPeerConfig.ConfigException {
      return new EmbeddedZookeeperServer(clientPort, dataDir, tickTime);
    }
  }
}
