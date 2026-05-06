## Contributions

Fan Zhang implemented the foundation of the Raft algorithm, such as implementing leader elections, heartbeat mechanism, and also implemented snapshot creation to handle log compaction.

Khai Yuan Liew implemented the log replication process, which includes the implementation of log conistency checks and implemented a logging and testing framework to evaluate performance metrics.

Ryan Chang implemented the state machine, intergrating RocksDB as the Key-Value database and defined the state machine logic, and also developed snapshot recovery to ensure the followers can quickly catch up after a crash or network partition.


## Set up

Maven clean and compile, then run three nodes:

```
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3.json"
```

To run without log compaction:
```
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1_NoLC.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2_NoLC.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3_NoLC.json"
```

To run the latency and throughput test:
```
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1.json LATT"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2.json LATT"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3.json LATT"
```

To run the latency and throughput test with no log compaction, replace the .json file with its _NoLC counterpart.

## Batch Set up (Requires Mac/Linux)
To run all nodes at once, call `./run_raft_cluster.sh`. Note this will require a Mac/Linux machine as the scripts calls Unix-based commands.
To run all nodes with no log compaction, run `./run_raft_cluster_NoLC.sh`
To run all nodes with the latency and throughput test run either `./run_raft_cluster_LATT.sh` or `./run_raft_cluster_LATT_NoLC.sh`, with log compaction and no log compaction, respectively.

RocksDB storage is initalized in `/tmp/rocksdb/[nodeID]` by default.

## Three RPCs are implemented:

### RequestVote

Used during elections to collect votes.

### AppendEntries

Used by leader to maintain authority and log replication to commit commands sent by client.

### InstallSnapshot

Called by Leader to send chucks of a snapshot to follower to ensure follower catch-up.

## Leader Election Flow

### Election Timeout

Each follower starts a randomized election timer (150–300 ms).
If no heartbeat is received before timeout, it becomes a candidate.

### Becoming Candidate

Increments currentTerm, votes for itself, and sends RequestVote RPC to all peers.

### Voting

A node grants vote if:

- Candidate’s term is newer than the receiver node's term.

- Hasn’t voted yet (or voted for same candidate)

If candidate node receives the majority vote, then the candidate becomes leader.

### Leader Behavior

Leader periodically sends empty AppendEntries RPCs (heartbeats).

Maintains authority and prevents new elections.

If leader receives commands from the client, it will send out AppendEntries RPCs to follower nodes to reach consensus by log replication. 

If enough nodes writes the entry in their logs, then the entry is committted by the leader and notifies the followers to update.

### Follower Behavior

On receiving valid heartbeat:

Updates term if needed, resets election timer, and stays as afollower

Check for log consistency. If not consistent with leader, then loop back in teh follower's log and retry again.

Otherwise, append any new entries to the follower's log. 

If an existing entry conflicts with the new entry (ie: same index, but different terms), delete all existing entries, starting from that index.

Set the commitIndex to match up with the leader's commitIndex and apply the log changes to the state machine

### Term Updates

If a node receives a higher term, it steps down to follower and updates its term.
