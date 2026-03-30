package com.raftDB.raft.model;

import com.raftDB.raft.config.NodeConfig;

public class RaftNode {
    private final NodeConfig config;
    private final RaftNodeState state;

    public RaftNode(NodeConfig config) {
        this.config = config;
        this.state = new RaftNodeState(config.getNodeId());
        System.out.println("Initial role: " + state.getRole());
    }

    public void start() {
        System.out.println("Starting Raft node: " + config.getNodeId());
        for (PeerInfo peer : config.getPeers()) {
            System.out.println("Peer: " + peer.getNodeId()
                    + " at " + peer.getHost() + ":" + peer.getPort());
        }
    }
}
