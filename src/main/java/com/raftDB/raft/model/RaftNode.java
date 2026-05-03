package com.raftDB.raft.model;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;
import com.raftDB.raft.config.MetricsManager;
import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.core.RaftServiceImpl;
import com.raftDB.raft.rpc.AppendEntriesRequest;
import com.raftDB.raft.rpc.AppendEntriesResponse;
import com.raftDB.raft.rpc.ClientResponse;
import com.raftDB.raft.rpc.InstallSnapshotRequest;
import com.raftDB.raft.rpc.InstallSnapshotResponse;
import com.raftDB.raft.rpc.LogEntry;
import com.raftDB.raft.rpc.RaftServiceGrpc;
import com.raftDB.raft.rpc.RequestVoteRequest;
import com.raftDB.raft.rpc.RequestVoteResponse;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import com.codahale.metrics.Timer;


public class RaftNode implements Serializable{

    private final NodeConfig config;
    private final RaftNodeState state;
    private final KVStorage store;
    private final LogStorage logStore;

    private Server server;

    //private final Map<String, ManagedChannel> peerChannels = new HashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceBlockingStub> peerStubs = new HashMap<>();

    private volatile long lastHeartbeatTime = System.currentTimeMillis();
    private final int electionTimeoutMs = 150 + (int)(Math.random() * 150);
    private static final int SNAPSHOT_THRESHOLD = 10;
    private static final boolean LOG_COMPACTION_ENABLED = true;
    private static Timer replicationTimer = MetricsManager.metricRegistry.timer("raft.replication");

    
    public RaftNode(NodeConfig config) {
        this.config = config;
        this.state = new RaftNodeState(config.getNodeId());

        this.store = new KVStorage(config.getNodeId());
        this.logStore = new LogStorage(config.getNodeId());

        //Load the previous persisted state from disk if exists.
        List<LogEntry> oldLog = logStore.getLog();
        String prevVote = logStore.getVotedFor();
        int prevCurTerm = logStore.getCurrentTerm();
        int commitIdx = 0;

        if(logStore.getSnapshot() != null){
            System.out.println(logStore.getSnapshot().getData());
            commitIdx = logStore.getSnapshot().getLastIncludedIndex();
            state.setLastIncludedIndex(commitIdx);
            state.setLastIncludedTerm(logStore.getSnapshot().getLastIncludedTerm()); 
            //System.out.println("LastInclIdx: " + commitIdx);
            System.out.println("Snapshot found");
        }

        System.out.println(oldLog);
        if (!oldLog.isEmpty()) {
            state.getLog().addAll(oldLog);
            state.setVotedFor(prevVote);
            state.setCurrentTerm(prevCurTerm);
            state.setCommitIndex(commitIdx);
            state.setLastApplied(commitIdx);
            System.out.println("Previous state loaded from disk:");
            
        } else if (oldLog.isEmpty() && logStore.getSnapshot() == null){
            System.out.println("No previous state found");
        }
        
        System.out.println("---------------");
        
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
            int lastIndex = state.getLastIncludedIndex() + state.getLog().size();

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

        //persist state to local storage
        save(currentTerm, state.getVotedFor(), state.getLog());

        System.out.println(config.getNodeId() + " started election for term " + currentTerm);

        //send vote requests to peers (with )
        for (Map.Entry<String, RaftServiceGrpc.RaftServiceBlockingStub> entry : peerStubs.entrySet()) {
            String peerId = entry.getKey();
            System.out.println("Current PeerId: " + peerId);
            int lastLogIndex = state.getLog().size() - 1;

            // workaround to stop infinite vote request loop, feel free to change if you have a better solution
            if(state.getLastIncludedIndex() >= 0){
                lastLogIndex += state.getLastIncludedIndex() + 1;
            }
            
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
                    System.err.println("RequestVote Deadline Exceeded: Vote Request timed out for " + peerId);
                }
            } catch (Exception e) {
                System.out.println("Failed to request vote from " + peerId + "with reason given: " + e.getMessage());
            }
        }

        int totalNodes = config.getPeers().size() + 1;
        int majority = (totalNodes / 2) + 1;

        //System.out.println("Majority Needed: " + majority + "/" + totalNodes);

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
    // added snapshot creation logic here, exclusive to leader. Followers create snapshots in processLogEntries
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
                            //If follower’s nextIndex points to a log that the leader already compacted, the leader cannot send normal AppendEntries
                            
                            if(nextIdx <= state.getLastIncludedIndex()){

                                // *** Debugging print statements ***
                                //System.out.println("Cannot find prevLogIndex " + prevLogIndex + " for peer " + peerId + ". Need snapshot install.");
                                //System.out.println("Req: ");
                                //System.out.println("Current Term: " + state.getCurrentTerm());
                                //System.out.println("Leader ID: " + state.getNodeId());
                                //System.out.println("Last Snapshot Index: " + logStore.getSnapshot().getLastIncludedIndex());
                                //System.out.println("Last Snapshot Term: " + logStore.getSnapshot().getLastIncludedTerm());
                                //System.out.println("Snapshot Data: " + logStore.getSnapshot().getData());
                                // ***********************************


                                //Had issues calling serializeSnapshot from RaftServiceImpl, so serialized it here instead
                                byte[] snapShotBytes = logStore.pubSerializeSnapshot(logStore.getSnapshot());
                                ByteString bs = ByteString.copyFrom(snapShotBytes);
                                
                                //InstallSnapshotRPC
                                InstallSnapshotRequest installReq = InstallSnapshotRequest.newBuilder()
                                    .setTerm(state.getCurrentTerm())
                                    .setLeaderId(config.getNodeId())
                                    .setLastSnapshotIndex(logStore.getSnapshot().getLastIncludedIndex())
                                    .setLastSnapshotTerm(logStore.getSnapshot().getLastIncludedTerm())
                                    .setData(bs)
                                    .build();

                                // try/catch mostly copied from AppendEntriesRPC
                                try {
                                    InstallSnapshotResponse response = stub.withDeadlineAfter(50, TimeUnit.MILLISECONDS).installSnapshot(installReq);
                                    
                                } catch (StatusRuntimeException e) {
                                     if(e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED){
                                        System.err.println("InstallSnapshot Deadline Exceeded: Heartbeat timed out for " + peerId);
                                    }
                                } catch (Exception e) {
                                    System.out.println("InstallSnapshot Failed heartbeat to " + peerId + "with reason given: " + e.getMessage());
                                }
                                //InstallSnapshotRequest installReq = InstallSnapshotRequest.newBuilder()
                                    
                                continue;
                            }

                            int startPos = state.toListPosition(nextIdx);

                            if (startPos >= 0 && startPos < log.size()) {
                                entriesToSend = new ArrayList<>(log.subList(startPos, log.size()));
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
                                System.err.println("AppendEntries Deadline Exceeded: Heartbeat timed out for " + peerId);
                            }
                        } catch (Exception e) {
                            System.out.println("Failed heartbeat to " + peerId + "with reason given: " + e.getMessage());
                        }
                    }
                    // Separate snapshot for leader after followers are done to avoid case where one node shuts down after 1st snapshot
                    if (state.getLastApplied() - state.getLastIncludedIndex() >= SNAPSHOT_THRESHOLD && state.getRole() == NodeRole.LEADER) {
                        maybeCreateSnapshot();

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
            int myLastLogIndex = state.getLastIncludedIndex() + state.getLog().size();
            int myLastLogTerm = state.getTermAt(myLastLogIndex);
            /*
            //More debugging Print Statements
            System.out.println("+++++++++++++++++++++++++");
            System.out.println("Checking if up to date");
            System.out.println("Prev Log Idx" + myLastLogIndex);
            System.out.println("Phys Log Idx" + lastLogIndex);
            System.out.println("+++++++++++++++++++++++++");
            */

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
    public boolean checkLogConsistency(int prevLogIndex, int prevLogTerm) {
        synchronized (state.getLock()) {
            
            // Beginning of log is always valid
            if (prevLogIndex == -1) {
                return true;
            }

            // If prevLogIndex is exactly the snapshot boundary, compare with snapshot metadata instead of log list.
            if (prevLogIndex == state.getLastIncludedIndex()) {
                return state.getLastIncludedTerm() == prevLogTerm;
            }

            // If prevLogIndex is older than snapshot, it has already been compacted.
            if (prevLogIndex < state.getLastIncludedIndex()) {
                return true;
            }

            int pos = state.toListPosition(prevLogIndex);

            if (pos < 0 || pos >= state.getLog().size()) {
                return false;
            }
            //System.out.println(state.getLog().get(pos).getTerm());
            //System.out.println(prevLogTerm);
            return state.getLog().get(pos).getTerm() == prevLogTerm;
        }
    }

    /*
    * Processes and updates the node's log to match up with leader node's log.
    * Set commit index to the min of the leader's commit index and the index of the last new entry.
    * TODO: Add logic to truncate local file if they are existing entries after the first new index.
    * TODO: Add logic to persist new log entries to local storage.
    * 
    * Moved snapshot creation from updateCommitIndex to here
    * @param - newEntries - List of all the new entries to append to the node's log
    * @param - leaderCommit - Commit index of leader node.
    */
    public void processLogEntries(List<LogEntry> newEntries, int leaderCommit){
        boolean shouldSnapshot = false;
        synchronized(state.getLock()){
        try (Timer.Context context = replicationTimer.time()){
            List<LogEntry> log = state.getLog();
            if(!newEntries.isEmpty()){
                // Get first new entry index.
                int firstNewIndex = newEntries.get(0).getIndex();
                int nextIdxCompare = firstNewIndex;
                int newEntriesIdx = 0;


                while (newEntriesIdx < newEntries.size()) {
                    LogEntry newEntry = newEntries.get(newEntriesIdx);
                    int raftIndex = newEntry.getIndex();

                    // If this entry is already included in snapshot, skip it
                    if (raftIndex <= state.getLastIncludedIndex()) {
                        System.out.println("entry already in the snapshot");
                        newEntriesIdx++;
                        continue;
                    }

                    int pos = state.toListPosition(raftIndex);

                    // If local log does not have this index yet, stop checking
                    if (pos < 0 || pos >= log.size()) {
                        break;
                    }

                    // If same index but different term, truncate from this position
                    if (log.get(pos).getTerm() != newEntry.getTerm()) {
                        System.out.println("Log conflict at Index " + raftIndex + ". Truncating log.");
                        log.subList(pos, log.size()).clear();
                        break;
                    }

                    newEntriesIdx++;
                }

                //Append any new entries that are not in the node's log.
                if(newEntriesIdx < newEntries.size()){
                    log.addAll(newEntries.subList(newEntriesIdx, newEntries.size()));

                    //persist logs and current state to local storage
                    save(state.getCurrentTerm(), state.getVotedFor(), log);

                }    
            }
                //Updates the commitIndex of the node to match the min of the leader's commit index and the index of the last new entry.
                if(leaderCommit > state.getCommitIndex()){
                    int lastLogIndex = state.getLastIncludedIndex() + log.size();
                    state.setCommitIndex(Math.min(leaderCommit, lastLogIndex));
                    applyToStateMachine(); 
                    if (state.getLastApplied() - state.getLastIncludedIndex() >= SNAPSHOT_THRESHOLD) {
                        shouldSnapshot = true;
                    }
                }

                //Create Snapshot for follower nodes
                if (shouldSnapshot) {
                    maybeCreateSnapshot();
                }
            }
        }
    }

    /*
    *  
    * Method to simulate the log changes being applied to the state machine. 
    * TODO: Will need to be modified to connect and apply log changes to an actual KV-store database.
    * TODO: Connect the state machine to actual KV-store database.
    * 
    */
    public void applyToStateMachine(){
        //putting maybeCreateSnapshot here was causing one of the nodes to freeze up after snapshot creation.
        //boolean shouldSnapshot = false;
        synchronized(state.getLock()){
            List<LogEntry> log = state.getLog();
            int commitIndex = state.getCommitIndex();
            int lastApplied = state.getLastApplied();

            //persist logs and current state to local storage
            save(state.getCurrentTerm(), state.getVotedFor(), log); 

            //Keep incrementing last applied index as long as the commitIndex is greater than the last applied index. 
            //Apply the logs of the last applied index to the state matchine.
            while(commitIndex > lastApplied){
                lastApplied++;

                state.setLastApplied(lastApplied);
                int pos = state.toListPosition(lastApplied);

                if (pos < 0 || pos >= log.size()) {
                    System.err.println("ERROR: Cannot apply raft index " + lastApplied +
                            ", list position = " + pos +
                            ", log size = " + log.size());
                    break;
                }

                LogEntry entry = log.get(pos);
                String command = entry.getCommand();

                if(command == null || command.isEmpty()){
                    continue;
                }

                //TODO: Remove these statements as these are just only meant for testing log replication. 
                //TODO: Eventually, we will need to call the KV-store database to execute those commands.
                String[] parts = command.split(" ");    
                
                // Call KV-store Database to execute command
                store.apply(command);
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
        synchronized (state.getLock()) {
            List<Integer> indices = new ArrayList<>();

            int leaderLastIndex = state.getLastIncludedIndex() + state.getLog().size();
            indices.add(leaderLastIndex);

            for (PeerInfo peer : config.getPeers()) {
                String peerId = peer.getNodeId();
                indices.add(state.getMatchIndex().getOrDefault(peerId, state.getLastIncludedIndex()));
            }

            Collections.sort(indices);

            System.out.println("Leader last index: " + leaderLastIndex);
            System.out.println("Match indices: " + state.getMatchIndex());
            System.out.println("All indices for majority: " + indices);

            int n = indices.size();
            int majorityIndex = indices.get(n / 2);

            int previousCommitIndex = state.getCommitIndex();

            if (majorityIndex > previousCommitIndex
                    && state.getTermAt(majorityIndex) == state.getCurrentTerm()) {

                System.out.println("------");
                System.out.println("Calculated MajorityIndex: " + majorityIndex);
                System.out.println("Log Term at MajorityIndex: " + state.getTermAt(majorityIndex));
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
                int entryIndex = state.getLastIncludedIndex() + state.getLog().size() + 1;
                LogEntry entry = LogEntry.newBuilder()
                        .setTerm(state.getCurrentTerm())
                        .setIndex(entryIndex)
                        .setCommand(command)
                        .build();
                
                state.getLog().add(entry);
                save(state.getCurrentTerm(), state.getVotedFor(), state.getLog());
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
    * If not enough nodes are able to reach consensus within 5 seconds, then the commit fails and sends out an unsuccessful response.
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
    
    /*
    * Method to persist server state to stable storage
    * @param currentTerm - latest term the server has seen.
    * @param votedFor - candidateId that received vote in current term, or null if none.
    * @param log - log entries containing commands, terms, and index.
    */
    public void save(int currentTerm, String votedFor, List<LogEntry> log){
        synchronized(state.getLock()){
            logStore.saveState(currentTerm, votedFor, log);
        }
    }

    public void saveSnap(int lastApplied, int latestTerm, Map<String, String> snapData){
        synchronized(state.getLock()){
            logStore.saveSnapshot(lastApplied, latestTerm, snapData);
        }
    }
    /*
     Creates a snapshot of the current state machine (KV store) if enough new logs have been applied since the last snapshot.
     */

    private void maybeCreateSnapshot() {
        if (!LOG_COMPACTION_ENABLED) {
            // System.out.println("No log compaction");
            return;
        }
        synchronized (state.getLock()) {
            int lastApplied = state.getLastApplied();
            System.out.println(
                    "Snapshot check: lastApplied = " + lastApplied +
                            ", lastIncludedIndex = " + state.getLastIncludedIndex() +
                            ", threshold = " + SNAPSHOT_THRESHOLD
            );

            //Only create snapshot when enough new logs have been applied
            if (lastApplied - state.getLastIncludedIndex() < SNAPSHOT_THRESHOLD) {
                //System.out.println("No need");
                return;
            }

            //Snapshot includes everything already applied to the state machine
            int lastIncludedIndex = lastApplied;
            int lastIncludedTerm = state.getTermAt(lastIncludedIndex);

            Map<String, String> snapshotData = store.exportAll();

            logStore.saveSnapshot(lastIncludedIndex, lastIncludedTerm, snapshotData);
            List<LogEntry> truncLog = truncateLogUpTo(lastIncludedIndex); //Safely remove old log entries already covered by snapshot

            state.setLastIncludedIndex(lastIncludedIndex);
            state.setLastIncludedTerm(lastIncludedTerm);

            save(state.getCurrentTerm(), state.getVotedFor(), truncLog); //save the truncated log

            System.out.println("Snapshot created up to index " + lastIncludedIndex);
        }
    }

    /*
     * Safely truncates log entries already included in the snapshot.
     *
     * @param lastIncludedIndex highest Raft log index included in snapshot
     */
    private List<LogEntry> truncateLogUpTo(int lastIncludedIndex) {
        synchronized (state.getLock()) {
            List<LogEntry> log = state.getLog();

            int oldSize = log.size();

            log.removeIf(entry -> entry.getIndex() <= lastIncludedIndex);

            int newSize = log.size();

            System.out.println(
                    "Log truncated up to index " + lastIncludedIndex +
                            ". Old size = " + oldSize +
                            ", new size = " + newSize
            );
            return log;
            
        }
    }

    public LogStorage getStoredLogs(){
        return this.logStore;
    }

  public void reRegisterNodeMetrics(){
        if(!MetricsManager.metricRegistry.getMetrics().containsKey("raft.replication")){
            replicationTimer = MetricsManager.metricRegistry.timer("raft.replication");
        }

        store.reRegisterStorageMetrics();
    }

}
