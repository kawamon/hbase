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
package org.apache.hadoop.hbase.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.backoff.ServerStatistics;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Test that we can actually send and use region metrics to slowdown client writes
 */
@Category(MediumTests.class)
public class TestClientPushback {

  private static final Log LOG = LogFactory.getLog(TestClientPushback.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();

  private static final byte[] tableName = Bytes.toBytes("client-pushback");
  private static final byte[] family = Bytes.toBytes("f");
  private static final byte[] qualifier = Bytes.toBytes("q");
  private static long flushSizeBytes = 1024;

  @BeforeClass
  public static void setupCluster() throws Exception{
    Configuration conf = UTIL.getConfiguration();
    // enable backpressure
    conf.setBoolean(HConstants.ENABLE_CLIENT_BACKPRESSURE, true);
    // turn the memstore size way down so we don't need to write a lot to see changes in memstore
    // load
    conf.setLong(HConstants.HREGION_MEMSTORE_FLUSH_SIZE, flushSizeBytes);
    // ensure we block the flushes when we are double that flushsize
    conf.setLong("hbase.hregion.memstore.block.multiplier", 2);

    UTIL.startMiniCluster(1);
    UTIL.createTable(tableName, family);
  }

  @AfterClass
  public static void teardownCluster() throws Exception{
    UTIL.shutdownMiniCluster();
  }

  @Test
  public void testClientTracksServerPushback() throws Exception{
    Configuration conf = UTIL.getConfiguration();
    TableName tablename = TableName.valueOf(tableName);
    Connection conn = ConnectionFactory.createConnection(conf);
    HTable table = (HTable) conn.getTable(tablename);

    HRegionServer rs = UTIL.getHBaseCluster().getRegionServer(0);
    HRegion region = rs.getOnlineRegions(tablename).get(0);

    LOG.debug("Writing some data to "+tablename);
    // write some data
    Put p = new Put(Bytes.toBytes("row"));
    p.add(family, qualifier, Bytes.toBytes("value1"));
    table.put(p);
    table.flushCommits();

    // get the current load on RS. Hopefully memstore isn't flushed since we wrote the the data
    int load = (int)((region.addAndGetGlobalMemstoreSize(0) * 100) / flushSizeBytes);
    LOG.debug("Done writing some data to "+tablename);

    // get the stats for the region hosting our table
    ClusterConnection connection = table.connection;
    ServerStatisticTracker stats = connection.getStatisticsTracker();
    assertNotNull( "No stats configured for the client!", stats);
    // get the names so we can query the stats
    ServerName server = rs.getServerName();
    byte[] regionName = region.getRegionName();

    // check to see we found some load on the memstore
    ServerStatistics serverStats = stats.getServerStatsForTesting(server);
    ServerStatistics.RegionStatistics regionStats = serverStats.getStatsForRegion(regionName);
    assertEquals(load, regionStats.getMemstoreLoadPercent());
  }
}