#!/bin/bash

# Compile the project first
mvn clean compile -U


echo "Starting Node 1"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node1.json" > node1.log 2>&1 &
P1=$!

echo "Starting Node 2"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node2.json" > node2.log 2>&1 &
P2=$!

echo "Starting Node 3"
mvn exec:java -Dexec.mainClass="com.raftDB.raft.core.Main" -Dexec.args="node3.json" > node3.log 2>&1 &
P3=$!

echo "Raft Cluster started with PIDs: $P1, $P2, $P3"
echo "Press [CTRL+C] to stop all nodes..."

# Trap CTRL+C to kill all background processes
trap "kill $P1 $P2 $P3; lsof -ti:50051,50052,50053 | xargs kill -9; exit" SIGINT

wait