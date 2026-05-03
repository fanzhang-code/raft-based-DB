package com.raftDB.raft.model;

//main shared state object
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.raftDB.raft.rpc.LogEntry;


public class RaftNodeState {
    private final String nodeId;

    private final Object lock = new Object();
    private volatile NodeRole role = NodeRole.FOLLOWER;  //node starts as follower

    //logical clock for ordering events
    private volatile int currentTerm = 0;

    private volatile String votedFor = null;
    

    private final List<LogEntry> log = new ArrayList<>();

    private volatile int commitIndex = 0;
    private volatile int lastApplied = 0;
    private volatile int lastIncludedIndex = -1;
    private volatile int lastIncludedTerm = 0;
    private final ConcurrentMap<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> matchIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> stateMachineData = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompletableFuture<Boolean>> pendingCommits = new ConcurrentHashMap<>();
    

    public RaftNodeState(String nodeId) {
        this.nodeId = nodeId;
    }

    /*
     * Converts a Raft log index to the ArrayList position after snapshot truncation.
     *
     * @param raftIndex the original Raft log index
     * @return the position inside the current in-memory log list
     */
    public int toListPosition(int raftIndex) {
        return raftIndex - lastIncludedIndex - 1;
    }

    /*
     * Checks whether the given Raft index is already included in the snapshot.
     *
     * @param raftIndex the original Raft log index
     * @return true if this index is covered by the snapshot
     */
    public boolean isInSnapshot(int raftIndex) {
        return raftIndex <= lastIncludedIndex;
    }

    public Object getLock() {
        return lock;
    }

    public String getNodeId() {
        return nodeId;
    }

    public NodeRole getRole() {
        return role;
    }

    public void setRole(NodeRole role) {
        this.role = role;
    }

    public int getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(int currentTerm) {
        this.currentTerm = currentTerm;
    }

    public String getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(String votedFor) {
        this.votedFor = votedFor;
    }

    public List<LogEntry> getLog() {
        return log;
    }

    /*
     * Returns the term for a given Raft log index.
     *
     * @param raftIndex the original Raft log index, not the ArrayList position
     * @return the term at that Raft index, or -1 if not found
     */
    public int getLastLogTerm(int raftIndex) {
        return getTermAt(raftIndex);
    }

    //update to handle snapshot index
    public int getTermAt(int raftIndex) {
        if (raftIndex < 0) {
            return 0;
        }

        if (raftIndex == lastIncludedIndex) {
            return lastIncludedTerm;
        }
        
        if (raftIndex < lastIncludedIndex) {
            return 0;
        }

        int pos = toListPosition(raftIndex);

        if (pos < 0 || pos >= log.size()) {
            return -1;
        }

        return log.get(pos).getTerm();
    }

    public int getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    public int getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public void setLastIncludedIndex(int lastIncludedIndex) {
        this.lastIncludedIndex = lastIncludedIndex;
    }

    public void setLastIncludedTerm(int lastIncludedTerm) {
        this.lastIncludedTerm = lastIncludedTerm;
    }

    public int getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(int commitIndex) {
        this.commitIndex = commitIndex;
    }

    public int getLastApplied() {
        return lastApplied;
    }

    public void setLastApplied(int lastApplied) {
        this.lastApplied = lastApplied;
    }

    public ConcurrentMap<String, Integer> getNextIndex() {
        return nextIndex;
    }

    public ConcurrentMap<String, Integer> getMatchIndex() {
        return matchIndex;
    }

    public ConcurrentMap<String, String> getStateMachineData() {
        return stateMachineData;
    }

    public ConcurrentMap<Integer, CompletableFuture<Boolean>> getPendingCommits() {
        return pendingCommits;
    }


}