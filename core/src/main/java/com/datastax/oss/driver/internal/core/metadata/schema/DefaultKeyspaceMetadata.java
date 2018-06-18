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
package com.datastax.oss.driver.internal.core.metadata.schema;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.schema.AggregateMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionSignature;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import net.jcip.annotations.Immutable;

@Immutable
public class DefaultKeyspaceMetadata implements KeyspaceMetadata {

  @NonNull private final CqlIdentifier name;
  private final boolean durableWrites;
  @NonNull private final Map<String, String> replication;
  @NonNull private final Map<CqlIdentifier, UserDefinedType> types;
  @NonNull private final Map<CqlIdentifier, TableMetadata> tables;
  @NonNull private final Map<CqlIdentifier, ViewMetadata> views;
  @NonNull private final Map<FunctionSignature, FunctionMetadata> functions;
  @NonNull private final Map<FunctionSignature, AggregateMetadata> aggregates;

  public DefaultKeyspaceMetadata(
      @NonNull CqlIdentifier name,
      boolean durableWrites,
      @NonNull Map<String, String> replication,
      @NonNull Map<CqlIdentifier, UserDefinedType> types,
      @NonNull Map<CqlIdentifier, TableMetadata> tables,
      @NonNull Map<CqlIdentifier, ViewMetadata> views,
      @NonNull Map<FunctionSignature, FunctionMetadata> functions,
      @NonNull Map<FunctionSignature, AggregateMetadata> aggregates) {
    this.name = name;
    this.durableWrites = durableWrites;
    this.replication = replication;
    this.types = types;
    this.tables = tables;
    this.views = views;
    this.functions = functions;
    this.aggregates = aggregates;
  }

  @NonNull
  @Override
  public CqlIdentifier getName() {
    return name;
  }

  @Override
  public boolean isDurableWrites() {
    return durableWrites;
  }

  @NonNull
  @Override
  public Map<String, String> getReplication() {
    return replication;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, UserDefinedType> getUserDefinedTypes() {
    return types;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, TableMetadata> getTables() {
    return tables;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, ViewMetadata> getViews() {
    return views;
  }

  @NonNull
  @Override
  public Map<FunctionSignature, FunctionMetadata> getFunctions() {
    return functions;
  }

  @NonNull
  @Override
  public Map<FunctionSignature, AggregateMetadata> getAggregates() {
    return aggregates;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof KeyspaceMetadata) {
      KeyspaceMetadata that = (KeyspaceMetadata) other;
      return Objects.equals(this.name, that.getName())
          && this.durableWrites == that.isDurableWrites()
          && Objects.equals(this.replication, that.getReplication())
          && Objects.equals(this.types, that.getUserDefinedTypes())
          && Objects.equals(this.tables, that.getTables())
          && Objects.equals(this.views, that.getViews())
          && Objects.equals(this.functions, that.getFunctions())
          && Objects.equals(this.aggregates, that.getAggregates());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name, durableWrites, replication, types, tables, views, functions, aggregates);
  }
}
