package com.raftDB.raft.model;

import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.core.RaftServiceImpl;
import com.raftDB.raft.rpc.*;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;

import io.grpc.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RaftNode {

    private final NodeConfig config;
    private final RaftNodeState state;

    private Server server;

    //private final Map<String, ManagedChannel> peerChannels = new HashMap<>();
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

            //peerChannels.put(peer.getNodeId(), channel);
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

    /*
    * Initalize the Leader's state after leader election.
    * Sets the next log entry to send to server to the leader's last log index + 1
    * and the index of the highest log entry known to be replicated on the server.
    */
    public void intializeLeaderState(){
        synchronized(state.getLock()){
            int lastIndex = state.getLog().size() - 1;

            // System.out.println("-------");
            // System.out.println("Initalize Leader state. Last Log Index is :" + lastIndex);
            // System.out.println("-------");

            for (PeerInfo peer : config.getPeers()){
                String peerId = peer.getNodeId();
                state.getNextIndex().put(peerId, lastIndex + 1);
                state.getMatchIndex().put(peerId, 0);
            }
        }
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
            System.out.println("Current PeerId: " + peerId);
            int lastLogIndex = state.getLog().size() - 1;
            RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();

            RequestVoteRequest request = RequestVoteRequest.newBuilder()
                    .setTerm(currentTerm)
                    .setCandidateId(config.getNodeId())
                    .setLastLogIndex(lastLogIndex) 
                    .setLastLogTerm(state.getLastLogTerm(lastLogIndex))
                    .build();

            try {
                System.out.println("Sending RequestVote to " + peerId + "...");
                // RequestVoteResponse response = stub.requestVote(request);

                //If node doesn't respond back to the request within 100ms, then return a Deadline Exceeded exception.
                //This is to ensure the follower nodes don't reach a deadlock when trying to request votes onto each when the inital leader node goes down.
                RequestVoteResponse response = stub.withDeadlineAfter(100, TimeUnit.MILLISECONDS).requestVote(request); 
                System.out.println("Received response from " + peerId);

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
            } catch (StatusRuntimeException e){
                if(e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED){
                    System.err.println("Deadline Exceeded: Vote Request timed out for " + peerId);
                }
            } catch (Exception e) {
                System.out.println("Failed to request vote from " + peerId + "with reason given: " + e.getMessage());
            }
        }

        int totalNodes = config.getPeers().size() + 1;
        int majority = (totalNodes / 2) + 1;

        // System.out.println("Majority Needed: " + majority + "/" + totalNodes);

        synchronized (state.getLock()) {
            //if get majority vote
            if (state.getRole() == com.raftDB.raft.model.NodeRole.CANDIDATE && votes >= majority) {
                state.setRole(com.raftDB.raft.model.NodeRole.LEADER);

                System.out.println("-------");
                System.out.println("Initalizing the nextIndex and matchIndex maps as leader was elected!");
                System.out.println("-------");

                intializeLeaderState();

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
                        int nextIdx;
                        int prevLogIndex;
                        int prevLogTerm;
                        List<LogEntry> entriesToSend = new ArrayList<>();

                        RaftServiceGrpc.RaftServiceBlockingStub stub = entry.getValue();

                        synchronized(state.getLock()) {
                            List<LogEntry> log = state.getLog();
                            nextIdx = state.getNextIndex().getOrDefault(peerId, log.size());
                            prevLogIndex = nextIdx - 1;
                            prevLogTerm = 0;

                            if (prevLogIndex >= 0){
                                prevLogTerm = state.getTermAt(prevLogIndex);
                            }

                            //Add log size > nextIdx/peerId logic. Include entriesToSend logic to have new data.
                            if (log.size() > nextIdx){
                                entriesToSend = new ArrayList<>(log.subList(nextIdx, log.size()));
                            }
                        }

                        AppendEntriesRequest request = AppendEntriesRequest.newBuilder()
                                .setTerm(currentTerm)
                                .setLeaderId(config.getNodeId())
                                .setPrevLogIndex(prevLogIndex) // Set to nextIdx - 1 or similar.
                                .setPrevLogTerm(prevLogTerm) //Set to getTermAt(nextIdx - 1) or similar.
                                .addAllEntries(entriesToSend) // Insert Add all entries to perform log replication. 
                                .setLeaderCommit(state.getCommitIndex()) //Set to this.commitIndex or similiar
                                .build();

                        try {
                            // AppendEntriesResponse response = stub.appendEntries(request);

                            //If node doesn't respond back to the heartbeat within 50ms, then return a Deadline Exceeded exception.                         
                            AppendEntriesResponse response = stub.withDeadlineAfter(50, TimeUnit.MILLISECONDS).appendEntries(request);
                            
                            //my term is old, change from leader to follower
                            if (response.getTerm() > currentTerm) {
                                synchronized (state.getLock()) {
                                    state.setCurrentTerm(response.getTerm());
                                    state.setRole(NodeRole.FOLLOWER);
                                    state.setVotedFor(null);

                                    state.getPendingCommits().values().forEach(f -> f.complete(false));
                                    state.getPendingCommits().clear();
                                }
                                System.out.println(config.getNodeId() + " stepped down after higher term from " + peerId);
                                break;
                            }

                            synchronized(state.getLock()){
                                // If successful response, update the nextIndex, matchIndex, commitIndex for the follower.
                                // Provided that the lastAppendedIndex is greater than the follower node's matchIndex.
                                if(response.getSuccess()){
                                    int lastAppendedIndex = request.getPrevLogIndex() + request.getEntriesCount();

                                    if(lastAppendedIndex > state.getMatchIndex().getOrDefault(peerId, -1)) {
                                        System.out.println("------");
                                        System.out.println("Peer " + peerId + " successfully appended up to " + lastAppendedIndex);
                                        System.out.println("------");
                                        state.getNextIndex().put(peerId, lastAppendedIndex + 1);
                                        state.getMatchIndex().put(peerId, lastAppendedIndex);

                                        updateCommitIndex();
                                    }

                                } else {
                                    // Otherwise, if there's a log inconsistency, then keep looping back to follower's log history until successful.
                                    int newNextIdx = Math.max(0, nextIdx - 1);
                                    state.getNextIndex().put(peerId, newNextIdx);
                                    System.out.println("Log mismatch for " + peerId + ". Retrying with next log entry index: " + newNextIdx);
                                }
                            }
                        } catch (StatusRuntimeException e){
                            if(e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED){
                                System.err.println("Deadline Exceeded: Heartbeat timed out for " + peerId);
                            }
                        } catch (Exception e) {
                            System.out.println("Failed heartbeat to " + peerId + "with reason given: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /*
    * Determines if the candidate node's log is up to date.
    * @param - lastLogIndex - candidate node's last log index.
    * @param - lastLogTerm - candidate node's last log term.
    * @return true if the candidate node's last log index is greater or equal to the voter node's last log index
    * Or if the candidate node's last log term is greater than the voter node's last log term, provided the terms between the two nodes are not equal.
    * Otherwise, return false.
    */
    public boolean isLogUpToDate(int lastLogIndex, int lastLogTerm){
        synchronized(state.getLock()){
            int myLastLogIndex = state.getLog().size() - 1;
            int myLastLogTerm = state.getTermAt(myLastLogIndex);

            if (lastLogTerm != myLastLogTerm) {
                return lastLogTerm > myLastLogTerm;
            }

            return lastLogIndex >= myLastLogIndex;   
        }     
    }

    /*
    * Determine if a node's log is consistent with the leader's log.
    * @param - prevLogIndex
    * @param - prevLogTerm
    * @return true if the node's log is consistent with the leader's log either the log terms are equal or the node's prev log is empty.
    * false if the node doesn't contain an entry at prevLogIndex whose term matches the prevLogTerm of the leader. Or the prevLogIndex is greater the leader's log itself.
    */    
    public boolean checkLogConsistency(int prevLogIndex, int prevLogTerm){
        synchronized(state.getLock()){
            List<LogEntry> log = state.getLog();
            
            if (prevLogIndex == -1){
                return true;
            }

            if (prevLogIndex >= log.size()){
                return false;
            }

            return state.getLastLogTerm(prevLogIndex) == prevLogTerm;
        }
    }

    /*
    * Processes and updates the node's log to match up with leader node's log.
    * Set commit index to the min of the leader's commit index and the index of the last new entry.
    * TODO: Add logic to truncate local file if they are existing entries after the first new index.
    * TODO: Add logic to persist new log entries to local storage.
    * @param - newEntries - List of all the new entries to append to the node's log
    * @param - leaderCommit - Commit index of leader node.
    */
    public void processLogEntries(List<LogEntry> newEntries, int leaderCommit){
        synchronized(state.getLock()){
            List<LogEntry> log = state.getLog();
            if(!newEntries.isEmpty()){
                // Get first new entry index.
                int firstNewIndex = newEntries.get(0).getIndex();

                int nextIdxCompare = firstNewIndex;
                int newEntriesIdx = 0;

                //If there are existing entries at or after the index of the first new entry and the terms are different, 
                //we must truncate the entry list starting from the index of the first new entry first.
                while(nextIdxCompare < log.size() && newEntriesIdx < newEntries.size()){
                    if(log.get(nextIdxCompare).getTerm() != newEntries.get(newEntriesIdx).getTerm()){
                        System.out.println("Log conflict at Index " + nextIdxCompare + ". Truncating log.");
                        log.subList(nextIdxCompare, log.size()).clear();
                        //TODO: Insert logic to truncate the local log and file.
                        break;
                    }

                    nextIdxCompare++;
                    newEntriesIdx++;
                }

                //Append any new entries that are not in the node's log.
                if(newEntriesIdx < newEntries.size()){
                    log.addAll(newEntries.subList(newEntriesIdx, newEntries.size()));
                    //TODO: Insert logic to persist new entries to disk.
                }    
            }
            //Updates the commitIndex of the node to match the min of the leader's commit index and the index of the last new entry.
            if(leaderCommit > state.getCommitIndex()){
                state.setCommitIndex(Math.min(leaderCommit, log.size() - 1)); 

                applyToStateMachine(); 
            }
        }
    }

    /*
    *  
    * Method to simulate the log changes being applied to the state machine. 
    * TODO: Will need to be modified to connect and apply log changes to an actual KV-store database.
    * TODO: Connect to state machine to actual KV-store database.
    * 
    */
    public void applyToStateMachine(){
        synchronized(state.getLock()){
            List<LogEntry> log = state.getLog();
            int commitIndex = state.getCommitIndex();
            int lastApplied = state.getLastApplied();

            //Keep incrementing last applied index as long as the commitIndex is greater than the last applied index. 
            //Apply the logs of the last applied index to the state matchine.
            while(commitIndex > lastApplied){
                lastApplied++;

                if (lastApplied >= log.size()) {
                    System.err.println("ERROR: Attempted to apply index " + lastApplied + " but log size is " + log.size());
                    break; 
                }

                state.setLastApplied(lastApplied);

                LogEntry entry = log.get(lastApplied);
                String command = entry.getCommand();

                if(command == null || command.isEmpty()){
                    continue;
                }

                //TODO: Remove these statements as these are just only meant for testing log replication. 
                //TODO: Eventually, we will need to call the KV-store database to execute those commands.
                String[] parts = command.split(" ");
                String action = parts[0].toUpperCase();

                if (action.equals("SET") && parts.length == 3) {
                    state.getStateMachineData().put(parts[1], parts[2]);
                    System.out.println(String.format("STATE MACHINE: Applied SET %s = %s", parts[1], parts[2]));
                } 
                // else if (action.equals("DELETE") && parts.length == 2) {
                //     state.getStateMachineData().remove(parts[1]);
                //     System.out.println(String.format("STATE MACHINE: Applied DELETE %s", parts[1]));
                // }
                
                state.setLastApplied(lastApplied);
            }
        }
    }

    /*
    * Updates the commit index of the node.
    * If the majority commit index is greater than the commit index of the node
    * and the terms of the majority and the node match, update the commit index to match the majority.
    * And apply the logs to the state machine and remove any pending commits.
    */
    public void updateCommitIndex() {
        synchronized(state.getLock()){
            List<Integer> indices = new ArrayList<>();
            indices.add(state.getLog().size() - 1); 

            for (PeerInfo peer : config.getPeers()){
                String peerId = peer.getNodeId();
                indices.add(state.getMatchIndex().getOrDefault(peerId, -1));
            }
            
            Collections.sort(indices);
            
            int n = indices.size();
            int majorityIndex = indices.get(n - (n / 2 + 1));

            if (majorityIndex < 0 || majorityIndex >= state.getLog().size()) {
                    return; 
            }

            int previousCommitIndex = state.getCommitIndex();

            if (majorityIndex > previousCommitIndex && state.getLog().get(majorityIndex).getTerm() == state.getCurrentTerm()) {
                    System.out.println("------");
                    System.out.println("Current MatchIndices: " + state.getMatchIndex());
                    System.out.println("Calculated MajorityIndex: " + majorityIndex);
                    System.out.println("Log Term at MajorityIndex: " + state.getLog().get(majorityIndex).getTerm());
                    System.out.println("------");
                    System.out.println(String.format("Majority votes obtained! Committing up to index %s", majorityIndex));
                    System.out.println("------");
                    
                    state.setCommitIndex(majorityIndex);

                    applyToStateMachine(); 

                    for (int i = previousCommitIndex + 1; i <= majorityIndex; i++) {
                        CompletableFuture<Boolean> future = state.getPendingCommits().remove(i);
                        if (future != null) {
                            future.complete(true);
                        }
                    }
                }
        }
    }    

    /*
    * Method to simulate the leader's response to a client's request.
    * @param - command - The client's command sent to the node leader.
    * @param - responseObserver - Response handler to receive and send streaming messages from the client.
    */
    public void simulateResponseClientRequest(String command, StreamObserver<ClientResponse> responseObserver) {
            synchronized(state.getLock()){
                System.out.println("Current Role for " + state.getNodeId() + " is " + state.getRole());
                if (state.getRole() != NodeRole.LEADER) { //Only leader gets to respond to the client.
                    responseObserver.onNext(ClientResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Node " + state.getNodeId() + " is not the leader.")
                            .build());
                    responseObserver.onCompleted();
                    return;
                }

                //Create a new log entry of the client's command.
                int entryIndex = state.getLog().size();
                LogEntry entry = LogEntry.newBuilder()
                        .setTerm(state.getCurrentTerm())
                        .setIndex(entryIndex)
                        .setCommand(command)
                        .build();
                
                state.getLog().add(entry);
                System.out.println(String.format("Leader received command: %s. Log size now %s", command, entryIndex));

                //Invokes heartbeat to perform log replication of the new log entry.
                waitForCommit(entryIndex).thenAccept(committed -> {
                    if (committed) {
                        responseObserver.onNext(ClientResponse.newBuilder()
                                .setSuccess(true)
                                .setMessage("Command committed at index " + entryIndex)
                                .build());
                    } else {
                        responseObserver.onNext(ClientResponse.newBuilder()
                                .setSuccess(false)
                                .setMessage("Timed out waiting for consensus")
                                .build());
                    }
                    responseObserver.onCompleted();
                });
            }
        }        

    /*
    * Method for waiting for the follower nodes to replicate the log entry and return back a response.
    * If not enough nodes are able to reach consensus within 5 seconds, then the commit fails aand sends out an unsuccessful response.
    * @param index - the new entry log index.
    */
    public CompletableFuture<Boolean> waitForCommit(int index) {
        // CompletableFuture<Boolean> future = new CompletableFuture<>();
        synchronized(state.getLock()){
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            state.getPendingCommits().put(index, future);

            return future
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    state.getPendingCommits().remove(index);
                    return false;
                });
        }
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
