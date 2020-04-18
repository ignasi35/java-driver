/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.driver.internal.core.metadata.token;

import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.shaded.guava.common.base.Preconditions;
import java.util.Map;
import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public class DefaultReplicationStrategyFactory implements ReplicationStrategyFactory {

  public static final String LOCAL_STRATEGY = "org.apache.cassandra.locator.LocalStrategy";

  public static final String SIMPLE_STRATEGY = "org.apache.cassandra.locator.SimpleStrategy";

  public static final String NETWORK_TOPOLOGY_STRATEGY =
      "org.apache.cassandra.locator.NetworkTopologyStrategy";

  public static final String EVERYWHERE_STRATEGY =
      "org.apache.cassandra.locator.EverywhereStrategy";

  private final String logPrefix;

  public DefaultReplicationStrategyFactory(InternalDriverContext context) {
    this.logPrefix = context.getSessionName();
  }

  @Override
  public ReplicationStrategy newInstance(Map<String, String> replicationConfig) {
    String strategyClass = replicationConfig.get("class");
    Preconditions.checkNotNull(
        strategyClass, "Missing replication strategy class in " + replicationConfig);
    switch (strategyClass) {
      case LOCAL_STRATEGY:
        return new LocalReplicationStrategy();
      case SIMPLE_STRATEGY:
        return new SimpleReplicationStrategy(replicationConfig);
      case NETWORK_TOPOLOGY_STRATEGY:
        return new NetworkTopologyReplicationStrategy(replicationConfig, logPrefix);
      case EVERYWHERE_STRATEGY:
        return new EverywhereReplicationStrategy();
      default:
        throw new IllegalArgumentException("Unsupported replication strategy: " + strategyClass);
    }
  }
}
