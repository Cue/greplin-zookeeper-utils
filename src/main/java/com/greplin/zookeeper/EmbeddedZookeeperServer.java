/*
 * Copyright 2010 The Greplin Zookeeper Utility Authors.
 *
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
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Make it easy to run a real zookeeper server (in a 1-node quorom) in the current JVM. Useful for testing.
 */
public class EmbeddedZookeeperServer {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedZookeeperServer.class);

  private final AtomicBoolean shutdown;
  private final int clientPort;
  private final File dataDir;
  private final boolean isTemporary;
  private final long tickTime;


  private final Thread serverThread;
  private final NIOServerCnxnFactory connectionFactory;


  /**
   * Creates a new embeddable ZookeeperServer.
   *
   * @param clientPort The port for the server to listen for client connections one
   * @param dataDir    The directory for zookeeper to store memory snapshots and transaction logs
   * @param tickTime   Server ticktime, used for heartbeats and determining session timeouts
   */
  public EmbeddedZookeeperServer(Integer clientPort, File dataDir, Long tickTime) throws
      QuorumPeerConfig.ConfigException, IOException {
    Preconditions.checkNotNull(clientPort);
    Preconditions.checkNotNull(tickTime);

    Preconditions.checkArgument(clientPort > 0 && clientPort < 65536);
    Preconditions.checkArgument(tickTime > 0);

    this.shutdown = new AtomicBoolean(false);
    this.clientPort = clientPort;
    this.isTemporary = dataDir == null;
    if (this.isTemporary) {
      this.dataDir = Files.createTempDir();
    } else {
      this.dataDir = dataDir;
    }

    this.tickTime = tickTime;

    Properties properties = new Properties();
    properties.setProperty("tickTime", tickTime.toString());
    properties.setProperty("clientPort", clientPort.toString());
    properties.setProperty("dataDir", this.dataDir.getAbsolutePath());

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
    ZooKeeperServer zooKeeperServer = new ZooKeeperServer();
    configure(zooKeeperServer, config);

    this.connectionFactory = new NIOServerCnxnFactory();
    this.connectionFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns());
    try {
      this.connectionFactory.startup(zooKeeperServer);
    } catch (InterruptedException e) {
      throw new RuntimeException("Server Interrupted", e);
    }

    this.serverThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          EmbeddedZookeeperServer.this.connectionFactory.join();
        } catch (InterruptedException e) {
          log.error("Zookeeper Connection Factory Interrupted", e);
        }
      }
    });

    this.serverThread.start();
  }

  private void configure(ZooKeeperServer zooKeeperServer, ServerConfig config) throws IOException {
    zooKeeperServer.setTxnLogFactory(
        new FileTxnSnapLog(new File(config.getDataLogDir()), new File(config.getDataDir())));
    zooKeeperServer.setTickTime(config.getTickTime());
    zooKeeperServer.setMinSessionTimeout(config.getMinSessionTimeout());
    zooKeeperServer.setMaxSessionTimeout(config.getMaxSessionTimeout());
  }

  /**
   * Shuts down this server instance.
   */
  public void shutdown() throws InterruptedException {
    boolean alreadyShutdown = this.shutdown.getAndSet(true);

    if (!alreadyShutdown) {
      this.connectionFactory.shutdown();

      // block until the server is actually shutdown
      this.serverThread.join();

      if (this.isTemporary) {
        try {
          FileUtils.deleteDirectory(this.dataDir);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Returns the port this server is listening on.
   * @return The port thi server is listening on
   */
  public int getClientPort() {
    return this.clientPort;
  }

  /**
   * Returns this server's configured data directory.
   * @return The directory this server is storing snapshots and transaction logs in
   */
  public File getDataDir() {
    return this.dataDir;
  }

  /**
   * This server's configured ticktime.
   * @return This server's configured ticktime
   */
  public long getTickTime() {
    return this.tickTime;
  }

  /**
   * Returns a new EmbeddedZookeeperServer Builder.
   * @return A new server builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder pattern for starting an embedded zookeeper server (so you don't have to set stuff you don't care about).
   */
  public static class Builder {

    private int clientPort = 2181; // MUTABLE: Builder.
    private File dataDir = null; // MUTABLE: Builder.
    private long tickTime = 2000; // MUTABLE: Builder.

    /**
     * Constructs a new default builder.
     */
    public Builder() {
      // no required parameters - my defaults work pretty well.
    }

    /**
     * Sets the port for an EmbeddedZookeeperServer to listen on.
     */
    public Builder clientPort(int port) {
      this.clientPort = port;
      return this;
    }

    /**
     * Sets the data directory for an EmbeddedZookeeperServer to store files.
     */
    public Builder dataDir(File dataDir) {
      this.dataDir = dataDir;
      return this;
    }

    /**
     * Sets the ticktime for an EmbeddedZookeeperServer.
     */
    public Builder tickTime(Long tickTime) {
      this.tickTime = tickTime;
      return this;
    }

    /**
     * Builds and returns a new EmbeddedZookeeperServer based on this Builder's configuration.
     */
    public EmbeddedZookeeperServer build() throws IOException, QuorumPeerConfig.ConfigException {
      return new EmbeddedZookeeperServer(this.clientPort, this.dataDir, this.tickTime);
    }
  }
}
