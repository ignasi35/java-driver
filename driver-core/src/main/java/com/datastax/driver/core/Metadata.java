/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.datastax.driver.core.exceptions.DriverInternalError;

/**
 * Keeps metadata on the connected cluster, including known nodes and schema definitions.
 */
public class Metadata {

    final Cluster.Manager cluster;
    volatile String clusterName;
    volatile String partitioner;
    private final ConcurrentMap<InetSocketAddress, Host> hosts = new ConcurrentHashMap<InetSocketAddress, Host>();
    final ConcurrentMap<String, KeyspaceMetadata> keyspaces = new ConcurrentHashMap<String, KeyspaceMetadata>();
    volatile TokenMap tokenMap;

    final ReentrantLock lock = new ReentrantLock();

    private static final Pattern cqlId = Pattern.compile("\\w+");
    private static final Pattern lowercaseId = Pattern.compile("[a-z][a-z0-9_]*");

    Metadata(Cluster.Manager cluster) {
        this.cluster = cluster;
    }

    void rebuildTokenMap(String partitioner, Map<Host, Collection<String>> allTokens) {
        lock.lock();
        try {
            if (allTokens.isEmpty())
                return;

            Token.Factory factory = partitioner == null
                ? (tokenMap == null ? null : tokenMap.factory)
                : Token.getFactory(partitioner);
            if (factory == null)
                return;

        this.tokenMap = TokenMap.build(factory, allTokens, keyspaces.values());
        } finally {
            lock.unlock();
        }
    }

    Host add(InetSocketAddress address) {
        Host newHost = new Host(address, cluster.convictionPolicyFactory, cluster);
        Host previous = hosts.putIfAbsent(address, newHost);
        return previous == null ? newHost : null;
    }

    boolean remove(Host host) {
        return hosts.remove(host.getSocketAddress()) != null;
    }

    Host getHost(InetSocketAddress address) {
        return hosts.get(address);
    }

    // For internal use only
    Collection<Host> allHosts() {
        return hosts.values();
    }

    // Deal with case sensitivity for a given keyspace or table id
    static String handleId(String id) {
        // Shouldn't really happen for this method, but no reason to fail here
        if (id == null)
            return null;

        if (cqlId.matcher(id).matches())
            return id.toLowerCase();

        // Check if it's enclosed in quotes. If it is, remove them
        if (id.charAt(0) == '"' && id.charAt(id.length() - 1) == '"')
            return id.substring(1, id.length() - 1);

        // otherwise, just return the id.
        return id;
    }

    // Escape a CQL3 identifier based on its value as read from the schema
    // tables. Because it comes from Cassandra, we could just always quote it,
    // but to get a nicer output we don't do it if it's not necessary.
    static String escapeId(String ident) {
        // we don't need to escape if it's lowercase and match non-quoted CQL3 ids.
        return lowercaseId.matcher(ident).matches() ? ident : quote(ident);
    }

    // Builds the internal name of a function/aggregate
    // Note that if simpleName comes from the user, the caller must call handleId on it before passing it to this method
    static String fullFunctionName(String simpleName, Collection<?> argumentTypes) {
        return String.format("%s(%s)",
            simpleName, COMMAS.join(argumentTypes));
    }

    static final Joiner COMMAS = Joiner.on(",");

    /**
     * Quote a keyspace, table or column identifier to make it case sensitive.
     * <p>
     * CQL identifiers, including keyspace, table and column ones, are case insensitive
     * by default. Case sensitive identifiers can however be provided by enclosing
     * the identifier in double quotes (see the
     * <a href="http://cassandra.apache.org/doc/cql3/CQL.html#identifiers">CQL documentation</a>
     * for details). If you are using case sensitive identifiers, this method
     * can be used to enclose such identifier in double quotes, making it case
     * sensitive.
     *
     * @param id the keyspace or table identifier.
     * @return {@code id} enclosed in double-quotes, for use in methods like
     * {@link #getReplicas}, {@link #getKeyspace}, {@link KeyspaceMetadata#getTable}
     * or even {@link Cluster#connect(String)}.
     */
    public static String quote(String id) {
        return '"' + id + '"';
    }

    /**
     * Returns the token ranges that define data distribution in the ring.
     * <p>
     * Note that this information is refreshed asynchronously by the control
     * connection, when schema or ring topology changes. It might occasionally
     * be stale.
     *
     * @return the token ranges.
     */
    public Set<TokenRange> getTokenRanges() {
        TokenMap current = tokenMap;
        return (current == null) ? Collections.<TokenRange>emptySet() : current.tokenRanges;
    }

    /**
     * Returns the token ranges that are replicated on the given host, for the given
     * keyspace.
     * <p>
     * Note that this information is refreshed asynchronously by the control
     * connection, when schema or ring topology changes. It might occasionally
     * be stale (or even empty).
     *
     * @param keyspace the name of the keyspace to get token ranges for.
     * @param host the host.
     * @return the (immutable) set of token ranges for {@code host} as known
     * by the driver.
     */
    public Set<TokenRange> getTokenRanges(String keyspace, Host host) {
        keyspace = handleId(keyspace);
        TokenMap current = tokenMap;
        if (current == null) {
            return Collections.emptySet();
        } else {
            Map<Host, Set<TokenRange>> dcRanges = current.hostsToRanges.get(keyspace);
            if (dcRanges == null) {
                return Collections.emptySet();
            } else {
                Set<TokenRange> ranges = dcRanges.get(host);
                return (ranges == null) ? Collections.<TokenRange>emptySet() : ranges;
            }
        }
    }

    /**
     * Returns the set of hosts that are replica for a given partition key.
     * <p>
     * Note that this information is refreshed asynchronously by the control
     * connection, when schema or ring topology changes. It might occasionally
     * be stale (or even empty).
     *
     * @param keyspace the name of the keyspace to get replicas for.
     * @param partitionKey the partition key for which to find the set of
     * replica.
     * @return the (immutable) set of replicas for {@code partitionKey} as known
     * by the driver.
     */
    public Set<Host> getReplicas(String keyspace, ByteBuffer partitionKey) {
        keyspace = handleId(keyspace);
        TokenMap current = tokenMap;
        if (current == null) {
            return Collections.emptySet();
        } else {
            Set<Host> hosts = current.getReplicas(keyspace, current.factory.hash(partitionKey));
            return hosts == null ? Collections.<Host>emptySet() : hosts;
        }
    }

    /**
     * Returns the set of hosts that are replica for a given token range.
     * <p>
     * Note that this information is refreshed asynchronously by the control
     * connection, when schema or ring topology changes. It might occasionally
     * be stale (or even empty).
     *
     * @param keyspace the name of the keyspace to get replicas for.
     * @param range the token range.
     * @return the (immutable) set of replicas for {@code range} as known by the driver.
     */
    public Set<Host> getReplicas(String keyspace, TokenRange range) {
        keyspace = handleId(keyspace);
        TokenMap current = tokenMap;
        if (current == null) {
            return Collections.emptySet();
        } else {
            Set<Host> hosts = current.getReplicas(keyspace, range.getEnd());
            return hosts == null ? Collections.<Host>emptySet() : hosts;
        }
    }

    /**
     * The Cassandra name for the cluster connect to.
     *
     * @return the Cassandra name for the cluster connect to.
     */
    public String getClusterName() {
        return clusterName;
    }

    /**
     * The partitioner in use as reported by the Cassandra nodes.
     *
     * @return the partitioner in use as reported by the Cassandra nodes.
     */
    public String getPartitioner() {
        return partitioner;
    }

    /**
     * Returns the known hosts of this cluster.
     *
     * @return A set will all the know host of this cluster.
     */
    public Set<Host> getAllHosts() {
        return new HashSet<Host>(allHosts());
    }

    /**
     * Checks whether hosts that are currently up agree on the schema definition.
     * <p>
     * This method performs a one-time check only, without any form of retry; therefore {@link Cluster.Builder#withMaxSchemaAgreementWaitSeconds(int)}
     * does not apply in this case.
     *
     * @return {@code true} if all hosts agree on the schema; {@code false} if they don't agree, or if the check could not be performed
     * (for example, if the control connection is down).
     */
    public boolean checkSchemaAgreement() {
        return cluster.controlConnection.checkSchemaAgreement();
    }

    /**
     * Returns the metadata of a keyspace given its name.
     *
     * @param keyspace the name of the keyspace for which metadata should be
     * returned.
     * @return the metadata of the requested keyspace or {@code null} if {@code
     * keyspace} is not a known keyspace.
     */
    public KeyspaceMetadata getKeyspace(String keyspace) {
        return keyspaces.get(handleId(keyspace));
    }

    void removeKeyspace(String keyspace) {
        keyspaces.remove(keyspace);
        if (tokenMap != null)
            tokenMap.tokenToHosts.remove(keyspace);
    }

    /**
     * Returns a list of all the defined keyspaces.
     *
     * @return a list of all the defined keyspaces.
     */
    public List<KeyspaceMetadata> getKeyspaces() {
        return new ArrayList<KeyspaceMetadata>(keyspaces.values());
    }

    /**
     * Returns a {@code String} containing CQL queries representing the schema
     * of this cluster.
     *
     * In other words, this method returns the queries that would allow to
     * recreate the schema of this cluster.
     *
     * Note that the returned String is formatted to be human readable (for
     * some definition of human readable at least).
     *
     * @return the CQL queries representing this cluster schema as a {code
     * String}.
     */
    public String exportSchemaAsString() {
        StringBuilder sb = new StringBuilder();

        for (KeyspaceMetadata ksm : keyspaces.values())
            sb.append(ksm.exportAsString()).append('\n');

        return sb.toString();
    }

    /**
     * Creates a tuple type given a list of types.
     *
     * @param types the types for the tuple type.
     * @return the newly created tuple type.
     */
    public TupleType newTupleType(DataType... types) {
        return new TupleType(Arrays.asList(types), cluster.protocolVersion(), cluster.configuration.getCodecRegistry());
    }

    /**
     * Builds a new {@link Token} from its string representation, according to the partitioner
     * reported by the Cassandra nodes.
     *
     * @param tokenStr the string representation.
     * @return the token.
     */
    public Token newToken(String tokenStr) {
        TokenMap current = tokenMap;
        if (current == null)
            throw new DriverInternalError("Token factory not set. This should only happen at initialization time");

        return current.factory.fromString(tokenStr);
    }

    /**
     * Builds a new {@link TokenRange}.
     *
     * @param start the start token.
     * @param end the end token.
     * @return the range.
     */
    public TokenRange newTokenRange(Token start, Token end) {
        TokenMap current = tokenMap;
        if (current == null)
            throw new DriverInternalError("Token factory not set. This should only happen at initialization time");

        return new TokenRange(start, end, current.factory);
    }

    Token.Factory tokenFactory() {
        TokenMap current = tokenMap;
        return (current == null) ? null : current.factory;
    }

    static class TokenMap {

        private final Token.Factory factory;
        private final Map<String, Map<Token, Set<Host>>> tokenToHosts;
        private final Map<String, Map<Host, Set<TokenRange>>> hostsToRanges;
        private final List<Token> ring;
        private final Set<TokenRange> tokenRanges;
        final Set<Host> hosts;

        private TokenMap(Token.Factory factory,
                         Map<Host, Set<Token>> primaryToTokens,
                         Map<String, Map<Token, Set<Host>>> tokenToHosts,
                         Map<String, Map<Host, Set<TokenRange>>> hostsToRanges,
                         List<Token> ring, Set<TokenRange> tokenRanges, Set<Host> hosts) {
            this.factory = factory;
            this.tokenToHosts = tokenToHosts;
            this.hostsToRanges = hostsToRanges;
            this.ring = ring;
            this.tokenRanges = tokenRanges;
            this.hosts = hosts;
            for (Map.Entry<Host, Set<Token>> entry : primaryToTokens.entrySet()) {
                Host host = entry.getKey();
                host.setTokens(ImmutableSet.copyOf(entry.getValue()));
            }
        }

        public static TokenMap build(Token.Factory factory, Map<Host, Collection<String>> allTokens, Collection<KeyspaceMetadata> keyspaces) {

            Set<Host> hosts = allTokens.keySet();
            Map<Token, Host> tokenToPrimary = new HashMap<Token, Host>();
            Map<Host, Set<Token>> primaryToTokens = new HashMap<Host, Set<Token>>();
            Set<Token> allSorted = new TreeSet<Token>();

            for (Map.Entry<Host, Collection<String>> entry : allTokens.entrySet()) {
                Host host = entry.getKey();
                for (String tokenStr : entry.getValue()) {
                    try {
                        Token t = factory.fromString(tokenStr);
                        allSorted.add(t);
                        tokenToPrimary.put(t, host);
                        Set<Token> hostTokens = primaryToTokens.get(host);
                        if (hostTokens == null) {
                            hostTokens = new HashSet<Token>();
                            primaryToTokens.put(host, hostTokens);
                        }
                        hostTokens.add(t);
                    } catch (IllegalArgumentException e) {
                        // If we failed parsing that token, skip it
                    }
                }
            }

            List<Token> ring = new ArrayList<Token>(allSorted);
            Set<TokenRange> tokenRanges = makeTokenRanges(ring, factory);

            Map<String, Map<Token, Set<Host>>> tokenToHosts = new HashMap<String, Map<Token, Set<Host>>>();
            Map<String, Map<Host, Set<TokenRange>>> hostsToRanges = new HashMap<String, Map<Host, Set<TokenRange>>>();
            for (KeyspaceMetadata keyspace : keyspaces)
            {
                ReplicationStrategy strategy = keyspace.replicationStrategy();
                Map<Token, Set<Host>> ksTokens = (strategy == null)
                    ? makeNonReplicatedMap(tokenToPrimary)
                    : strategy.computeTokenToReplicaMap(tokenToPrimary, ring);

                tokenToHosts.put(keyspace.getName(), ksTokens);

                Map<Host, Set<TokenRange>> ksRanges;
                if (ring.size() == 1) {
                    // We forced the single range to ]minToken,minToken], make sure to use that instead of relying on the host's token
                    ImmutableMap.Builder<Host, Set<TokenRange>> builder = ImmutableMap.builder();
                    for (Host host : allTokens.keySet())
                        builder.put(host, tokenRanges);
                    ksRanges = builder.build();
                } else {
                    ksRanges = computeHostsToRangesMap(tokenRanges, ksTokens, hosts.size());
                }
                hostsToRanges.put(keyspace.getName(), ksRanges);
            }
            return new TokenMap(factory, primaryToTokens, tokenToHosts, hostsToRanges, ring, tokenRanges, hosts);
        }

        private Set<Host> getReplicas(String keyspace, Token token) {

            Map<Token, Set<Host>> keyspaceHosts = tokenToHosts.get(keyspace);
            if (keyspaceHosts == null)
                return Collections.emptySet();

            // If the token happens to be one of the "primary" tokens, get result directly
            Set<Host> hosts = keyspaceHosts.get(token);
            if (hosts != null)
                return hosts;

            // Otherwise, find closest "primary" token on the ring
            int i = Collections.binarySearch(ring, token);
            if (i < 0) {
                i = -i - 1;
                if (i >= ring.size())
                    i = 0;
            }

            return keyspaceHosts.get(ring.get(i));
        }

        private static Map<Token, Set<Host>> makeNonReplicatedMap(Map<Token, Host> input) {
            Map<Token, Set<Host>> output = new HashMap<Token, Set<Host>>(input.size());
            for (Map.Entry<Token, Host> entry : input.entrySet())
                output.put(entry.getKey(), ImmutableSet.of(entry.getValue()));
            return output;
        }

        private static Set<TokenRange> makeTokenRanges(List<Token> ring, Token.Factory factory) {
            ImmutableSet.Builder<TokenRange> builder = ImmutableSet.builder();
            // JAVA-684: if there is only one token, return the range ]minToken, minToken]
            if(ring.size() == 1) {
                builder.add(new TokenRange(factory.minToken(), factory.minToken(), factory));                
            } else {
                for (int i = 0; i < ring.size(); i++) {
                    Token start = ring.get(i);
                    Token end = ring.get((i + 1) % ring.size());
                    builder.add(new TokenRange(start, end, factory));
                }
            }
            return builder.build();
        }

        private static Map<Host, Set<TokenRange>> computeHostsToRangesMap(Set<TokenRange> tokenRanges, Map<Token, Set<Host>> ksTokens, int hostCount) {
            Map<Host, ImmutableSet.Builder<TokenRange>> builders = Maps.newHashMapWithExpectedSize(hostCount);
            for (TokenRange range : tokenRanges) {
                Set<Host> replicas = ksTokens.get(range.getEnd());
                for (Host host : replicas) {
                    ImmutableSet.Builder<TokenRange> hostRanges = builders.get(host);
                    if (hostRanges == null) {
                        hostRanges = ImmutableSet.builder();
                        builders.put(host, hostRanges);
                    }
                    hostRanges.add(range);
                }
            }
            Map<Host, Set<TokenRange>> ksRanges = Maps.newHashMapWithExpectedSize(hostCount);
            for (Map.Entry<Host, ImmutableSet.Builder<TokenRange>> entry : builders.entrySet()) {
                ksRanges.put(entry.getKey(), entry.getValue().build());
            }
            return ksRanges;
        }
    }
}