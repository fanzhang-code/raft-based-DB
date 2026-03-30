package com.raftDB.raft.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.raftDB.raft.model.PeerInfo;

import java.util.List;

public class NodeConfig {
    private final String nodeId;
    private final String host;
    private final int port;
    private final List<PeerInfo> peers;

    @JsonCreator
    public NodeConfig(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("peers") List<PeerInfo> peers) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.peers = peers;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public List<PeerInfo> getPeers() {
        return peers;
    }
}
