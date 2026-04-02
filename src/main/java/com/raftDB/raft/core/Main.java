package com.raftDB.raft.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import com.raftDB.raft.rpc.*;
import com.raftDB.raft.config.ConfigLoader;
import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.model.NodeRole;
import com.raftDB.raft.model.RaftNode;

import io.grpc.stub.StreamObserver;

public class Main {

    //To start the node N - run with arg: nodeN.json
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new RuntimeException("Please provide config file name");
        }

        String configFile = args[0];
        NodeConfig config = ConfigLoader.load(configFile);

        RaftNode node = new RaftNode(config);
        node.start();
        /***
        if ("node1".equals(config.getNodeId())) {
            Thread.sleep(3000);
            node.startElection();
        }
         ***/


        Thread.sleep(3000);
        simulateClient(node);


        node.blockUntilShutdown();
    }

    /*
    * Method to simulate client request for log replication testing. Subject to change or removal.
    * All it does is just infinitely sends requests to the node leader to SET the color attribute to green.
    * To stop the log replication test, just simply press Ctrl + C.
    */
    private static void simulateClient(RaftNode node){
        final AtomicInteger count = new AtomicInteger(0); 

        while (true) {
            if (node.getState().getRole() == NodeRole.LEADER) {
                int currentCount = count.get();
                String command = "SET color green_" + currentCount;
                CountDownLatch latch = new CountDownLatch(1);

            node.simulateResponseClientRequest(command, new StreamObserver<ClientResponse>() {
                @Override
                public void onNext(ClientResponse response) {
                    if (response.getSuccess()) {
                        System.out.println("SUCCESS: green_" + currentCount + " committed to the cluster.");
                        count.incrementAndGet();
                    } else {
                        System.out.println("RETRYING to SET color green_" + currentCount + " to leader node: " + response.getMessage());
                    }
                    latch.countDown();
                }
                @Override public void onError(Throwable t) {
                    System.err.println("RPC Error: " + t.getMessage());
                    latch.countDown();
                }
                @Override public void onCompleted() {}
            });

                try {
                    latch.await();
                    Thread.sleep(1000); 
                } catch (InterruptedException e) {
                    break;
                }

            } else {
                try {
                    Thread.sleep(2000); 
                } catch (InterruptedException e) {
                    break;
                }                
            }       
        }
    }


}