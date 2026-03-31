package com.raftDB.raft.core;

import com.raftDB.raft.config.ConfigLoader;
import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.model.RaftNode;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new RuntimeException("Please provide config file name");
        }

        String configFile = args[0];
        NodeConfig config = ConfigLoader.load(configFile);

        RaftNode node = new RaftNode(config);
        node.start();
        node.blockUntilShutdown();
    }


}