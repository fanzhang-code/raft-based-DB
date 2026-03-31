## Set up

Maven clean and compile, then run three nodes:

```
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2.json"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3.json"
```


## Two RPCs are implemented:

### RequestVote

Used during elections to collect votes

### AppendEntries (heartbeat-only)

Used by leader to maintain authority (no log replication yet) (Add TODOs)

## Leader Election Flow

### Election Timeout

Each follower starts a randomized election timer (150–300 ms).
If no heartbeat is received before timeout, it becomes a candidate.

### Becoming Candidate

Increments currentTerm

Votes for itself

Sends RequestVote RPC to all peers

### Voting

A node grants vote if:

candidate’s term is at least as new

it hasn’t voted yet (or voted for same candidate)

Majority vote → candidate becomes leader

### Leader Behavior

Leader periodically sends empty AppendEntries RPCs (heartbeats)

Maintains authority and prevents new elections

### Follower Behavior

On receiving valid heartbeat:

updates term if needed

resets election timer

stays follower

### Term Updates

If a node receives a higher term:

it steps down to follower and
updates its term
