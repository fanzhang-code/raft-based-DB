## Set up

Maven clean and compile, then run three nodes:

```
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3.json"
```

To run all nodes at once, call `./run_raft_cluster.sh`.

## Two RPCs are implemented:

### RequestVote

Used during elections to collect votes

### AppendEntries

Used by leader to maintain authority and log replication to commit commands sent by client.

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

Set the commitIndex to match up with the leader's commitIndex and apply the log changes to the state machine (Currently in main memory. TODO: Connect to KV-store database)

### Term Updates

If a node receives a higher term, it steps down to follower and updates its term.
