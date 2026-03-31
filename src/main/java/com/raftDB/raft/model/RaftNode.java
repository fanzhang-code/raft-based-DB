package com.raftDB.raft.model;

import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.core.RaftServiceImpl;
import com.raftDB.raft.rpc.RaftServiceGrpc;
import com.raftDB.raft.rpc.RequestVoteRequest;
import com.raftDB.raft.rpc.RequestVoteResponse;
import io.grpc.ServerBuilder;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RaftNode {

    private final NodeConfig config;
    private final RaftNodeState state;

    private Server server;

    private final Map<String, ManagedChannel> peerChannels = new HashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceBlockingStub> peerStubs = new HashMap<>();

    public RaftNode(NodeConfig config) {
        this.config = config;
        this.state = new RaftNodeState(config.getNodeId());
    }

    public void start() throws IOException {
        startServer();
        createPeerStubs();

        System.out.println("Raft node started: " + config.getNodeId());
        System.out.println("Listening on port: " + config.getPort());
    }

    private void startServer() throws IOException {
        server = ServerBuilder.forPort(config.getPort())
                .addService(new RaftServiceImpl(this))
                .build()
                .start();

        System.out.println("gRPC server started on port " + config.getPort());
    }

    private void createPeerStubs() {
        for (PeerInfo peer : config.getPeers()) {
            ManagedChannel channel = ManagedChannelBuilder
                    .forAddress(peer.getHost(), peer.getPort())
                    .usePlaintext()
                    .build();

            RaftServiceGrpc.RaftServiceBlockingStub stub =
                    RaftServiceGrpc.newBlockingStub(channel);

            peerChannels.put(peer.getNodeId(), channel);
            peerStubs.put(peer.getNodeId(), stub);

            System.out.println("Connected stub to peer: " + peer.getNodeId()
                    + " at " + peer.getHost() + ":" + peer.getPort());
        }
    }

    //Add manual election method
    public void startElection() {
        synchronized (state.getLock()) {
            state.setRole(com.raftDB.raft.model.NodeRole.CANDIDATE);
            state.setCurrentTerm(state.getCurrentTerm() + 1);
            state.setVotedFor(config.getNodeId());
        }

        int currentTerm = state.getCurrentTerm();
        int votes = 1; // vote for self

        System.out.println(config.getNodeId() + " started election for term " + currentTerm);

        for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : peerStubs.entrySet()) {
            String peerId = entry.getKey();
            RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();

            RequestVoteRequest request = RequestVoteRequest.newBuilder()
                    .setTerm(currentTerm)
                    .setCandidateId(config.getNodeId())
                    .setLastLogIndex(0)
                    .setLastLogTerm(0)
                    .build();

            try {
                RequestVoteResponse response = stub.requestVote(request);
                System.out.println("Vote reply from " + peerId + ": granted=" + response.getVoteGranted()
                        + ", term=" + response.getTerm());

                if (response.getVoteGranted()) {
                    votes++;
                } else if (response.getTerm() > currentTerm) {
                    synchronized (state.getLock()) {
                        state.setCurrentTerm(response.getTerm());
                        state.setRole(com.raftDB.raft.model.NodeRole.FOLLOWER);
                        state.setVotedFor(null);
                    }
                    return;
                }
            } catch (Exception e) {
                System.out.println("Failed to request vote from " + peerId + ": " + e.getMessage());
            }
        }

        int totalNodes = config.getPeers().size() + 1;
        int majority = (totalNodes / 2) + 1;

        synchronized (state.getLock()) {
            if (state.getRole() == com.raftDB.raft.model.NodeRole.CANDIDATE && votes >= majority) {
                state.setRole(com.raftDB.raft.model.NodeRole.LEADER);
                System.out.println(config.getNodeId() + " became LEADER for term " + state.getCurrentTerm());
            } else {
                System.out.println(config.getNodeId() + " failed to become leader. Votes=" + votes);
            }
        }
    }

    public NodeConfig getConfig() {
        return config;
    }

    public RaftNodeState getState() {
        return state;
    }

    public Map<String, RaftServiceGrpc.RaftServiceBlockingStub> getPeerStubs() {
        return peerStubs;
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
}
