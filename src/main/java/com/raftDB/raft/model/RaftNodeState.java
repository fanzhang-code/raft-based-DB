package com.raftDB.raft.model;

//main shared state object
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.raftDB.raft.rpc.LogEntry;

/**
placeholders for log replication phase:
log
commitIndex
lastApplied
nextIndex
matchIndex
 **/
public class RaftNodeState {
    private final String nodeId;

    private final Object lock = new Object();
    private volatile NodeRole role = NodeRole.FOLLOWER;  //node starts as follower

    //logical clock for ordering events
    private volatile int currentTerm = 0;

    private volatile String votedFor = null;

    private final List<LogEntry> log = new ArrayList<>();

    // TODO Use these later for log replication
    private volatile int commitIndex = 0;
    private volatile int lastApplied = 0;
    private final ConcurrentMap<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> matchIndex = new ConcurrentHashMap<>();
    // private final ConcurrentMap<String, AtomicInteger> nextIndex = new ConcurrentHashMap<>();
    // private final ConcurrentMap<String, AtomicInteger> matchIndex = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> stateMachineData = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CompletableFuture<Boolean>> pendingCommits = new ConcurrentHashMap<>();

    public RaftNodeState(String nodeId) {
        this.nodeId = nodeId;
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

    public int getLastLogTerm(int lastLogIndex){
        if (lastLogIndex <= 0 || log.isEmpty()){
            return 0;
        }

        if(lastLogIndex >= log.size()){
            return -1;
        }

        return this.getLog().get(lastLogIndex).getTerm();
    }

    public int getTermAt(int index){
        if (index < 0){
            return 0;
        }

        return this.getLog().get(index).getTerm();
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
    // public ConcurrentMap<String, AtomicInteger> getNextIndex() {
        return nextIndex;
    }

    public ConcurrentMap<String, Integer> getMatchIndex() {
    // public ConcurrentMap<String, AtomicInteger> getMatchIndex() {
        return matchIndex;
    }

    public ConcurrentMap<String, String> getStateMachineData() {
        return stateMachineData;
    }

    public ConcurrentMap<Integer, CompletableFuture<Boolean>> getPendingCommits() {
        return pendingCommits;
    }    
}