greplin-zookeeper-utils
======================

Greplin Zookeeper Utilities
---------------------

[Zookeeper] (http://zookeeper.apache.org/) is an amazingly useful tool for building distributed systems. But the primitives it provides - while complete - are a bit low level.

### Status:

This is a very early stage project.  It works for our needs.  We haven't verified it works beyond that.  Issue reports
and patches are very much appreciated!

This project contains a wrapper around the [ZooKeeper Client] (http://zookeeper.apache.org/doc/r3.3.3/api/org/apache/zookeeper/ZooKeeper.html) that provides more resilient reconnection logic, and implementations of useful distributed 'recipes' (notably locks).

It also contains an in-JVM 'embeddable' zookeeper server that you can use in your own testing.


Some improvements we'd love to see include:

* [Read/write locks] (http://zookeeper.apache.org/doc/trunk/recipes.html#Shared+Locks)

* [Double Barriers] (http://zookeeper.apache.org/doc/trunk/recipes.html#sc_doubleBarriers)

* [Two-phased Commit] (http://zookeeper.apache.org/doc/trunk/recipes.html#sc_recipes_twoPhasedCommit)

### Pre-requisites:

[Maven] (http://maven.apache.org/)

## Installation

    git clone https://github.com/Greplin/greplin-zookeeper-utils.git

    cd greplin-zookeeper-utils

    mvn install

## Usage

    final int port = 2182;
    final EmbeddedZookeeperServer server = EmbeddedZookeeperServer.builder().clientPort(port).build();
    final RobustZooKeeper client = new RobustZooKeeper("localhost:" + port);

    client.withLock("lockName",
        new Runnable() {
          @Override
          public void run() {
            System.out.println("I've got the lock while I'm running!");
          }
        });

    client.shutdown();
    server.shutdown();

## Authors
[Greplin, Inc.](http://www.greplin.com)

