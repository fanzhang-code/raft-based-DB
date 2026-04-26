package com.raftDB.raft.model;

import java.io.Serializable;
import java.util.Map;

public class SnapshotData implements Serializable {
    private final int lastIncludedIndex;  //log index the snapshot covers
    private final int lastIncludedTerm;
    private final Map<String, String> data;

    public SnapshotData(int lastIncludedIndex, int lastIncludedTerm, Map<String, String> data) {
        this.lastIncludedIndex = lastIncludedIndex;
        this.lastIncludedTerm = lastIncludedTerm;
        this.data = data;
    }

    public int getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    public int getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public Map<String, String> getData() {
        return data;
    }
}