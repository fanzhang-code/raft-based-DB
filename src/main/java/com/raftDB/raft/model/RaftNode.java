package com.raftDB.raft.model;

import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.core.RaftServiceImpl;
import com.raftDB.raft.rpc.*;
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

    private volatile long lastHeartbeatTime = System.currentTimeMillis();
    private final int electionTimeoutMs = 150 + (int)(Math.random() * 150);

    public RaftNode(NodeConfig config) {
        this.config = config;
        this.state = new RaftNodeState(config.getNodeId());
    }

    public void start() throws IOException {
        startServer();
        //Set up connections with peers
        createPeerStubs();

        startElectionTimer();
        startHeartbeatLoop();

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

    private void startElectionTimer() {
        new Thread(() -> {
            while (true) { //keep checking heartbeat to see if leader is still alive
                try {
                    Thread.sleep(50);

                    boolean shouldStartElection = false;
                    long now = System.currentTimeMillis();

                    synchronized (state.getLock()) {
                        //if a node hasn't received heartbeat from leader for a while, consider re-election
                        if (state.getRole() != NodeRole.LEADER
                                && now - lastHeartbeatTime > electionTimeoutMs) {
                            shouldStartElection = true;
                            lastHeartbeatTime = now;
                        }
                    }

                    if (shouldStartElection) {
                        System.out.println(config.getNodeId() + " election timeout -> start election");
                        startElection();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void startElection() {
        int currentTerm;

        //increase CurrentTerm and vote for itself
        synchronized (state.getLock()) {
            state.setRole(NodeRole.CANDIDATE);
            state.setCurrentTerm(state.getCurrentTerm() + 1);
            state.setVotedFor(config.getNodeId());
            currentTerm = state.getCurrentTerm();
        }
        int votes = 1; // count the vote for self

        System.out.println(config.getNodeId() + " started election for term " + currentTerm);

        //send vote requests to peers (with )
        for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : peerStubs.entrySet()) {
            String peerId = entry.getKey();
            RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();

            RequestVoteRequest request = RequestVoteRequest.newBuilder()
                    .setTerm(currentTerm)
                    .setCandidateId(config.getNodeId())
                    .setLastLogIndex(0)//TODO log replication
                    .setLastLogTerm(0)
                    .build();

            try {
                RequestVoteResponse response = stub.requestVote(request);
                System.out.println("Vote reply from " + peerId + ": granted=" + response.getVoteGranted()
                        + ", term=" + response.getTerm());

                if (response.getVoteGranted()) {
                    votes++; //other node votes for it
                } else if (response.getTerm() > currentTerm) {  // candidate term is not new enough
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
            //if get majority vote
            if (state.getRole() == com.raftDB.raft.model.NodeRole.CANDIDATE && votes >= majority) {
                state.setRole(com.raftDB.raft.model.NodeRole.LEADER);
                System.out.println(config.getNodeId() + " became LEADER for term " + state.getCurrentTerm());
            } else {
                System.out.println(config.getNodeId() + " failed to become leader. Votes=" + votes);
            }
        }
    }

    private void startHeartbeatLoop() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(80);

                    int currentTerm;
                    synchronized (state.getLock()) {
                        if (state.getRole() != NodeRole.LEADER) {
                            continue;
                        }
                        currentTerm = state.getCurrentTerm();
                    }
                    //Leader periodically sends empty AppendEntries RPCs (heartbeats)
                    for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : peerStubs.entrySet()) {
                        String peerId = entry.getKey();
                        RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();

                        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                                .setTerm(currentTerm)
                                .setLeaderId(config.getNodeId())
                                .setPrevLogIndex(0)
                                .setPrevLogTerm(0)
                                .setLeaderCommit(0)
                                .build();

                        try {
                            AppendEntriesResponse response = stub.appendEntries(request);

                            //my term is old, change from leader to follower
                            if (response.getTerm() > currentTerm) {
                                synchronized (state.getLock()) {
                                    state.setCurrentTerm(response.getTerm());
                                    state.setRole(NodeRole.FOLLOWER);
                                    state.setVotedFor(null);
                                }
                                System.out.println(config.getNodeId() + " stepped down after higher term from " + peerId);
                                break;
                            }
                        } catch (Exception e) {
                            System.out.println("Failed heartbeat to " + peerId + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void resetHeartbeatTimer() {
        lastHeartbeatTime = System.currentTimeMillis();
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
