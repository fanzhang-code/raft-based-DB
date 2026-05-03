package com.raftDB.raft.core;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.codahale.metrics.ConsoleReporter;
import com.raftDB.raft.config.ConfigLoader;
import com.raftDB.raft.config.MetricsManager;
import com.raftDB.raft.config.NodeConfig;
import com.raftDB.raft.model.NodeRole;
import com.raftDB.raft.model.RaftNode;
import com.raftDB.raft.rpc.ClientResponse;
import com.codahale.metrics.MetricFilter;


import com.codahale.metrics.Timer;

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

        final ConsoleReporter reporter = ConsoleReporter.forRegistry(MetricsManager.metricRegistry)
                                                        .convertRatesTo(TimeUnit.SECONDS)
                                                        .convertDurationsTo(TimeUnit.MILLISECONDS)
                                                        .build();        
        reporter.start(60, TimeUnit.SECONDS);

        node.start();
        Thread.sleep(5000);

        System.out.println("Warm-up Phase for 60 seconds.");
        simulateClient(node, 60);

        MetricsManager.metricRegistry.removeMatching(MetricFilter.ALL);
        System.out.println("Warm-up completed! Cleared Warmup Metrics!");
        node.reRegisterNodeMetrics();

        System.out.println("Reregistered Metrics! Starting Latency and Throughput Test for 5 minutes.");
        simulateClient(node, 300);

        System.out.println("Benchmark complete. Generating the final report.");
        reporter.report();
        reporter.stop();

        node.blockUntilShutdown();
    }

    /*
    * Method to simulate client request to test and record latency and throughput of the client, database, and replication (Followers only)
    * It will send a set amount of PUT requests. After reaching the threshold, then it will randomly send GET and PUT requests.
    * To stop the latency and throughput test, just simply press Ctrl + C.
    */
    private static void simulateClient(RaftNode node, int seconds){
        long endTime = System.currentTimeMillis() + (seconds * 1000L);

        final int NUM_KEYS = 1000;
        final AtomicInteger count = new AtomicInteger(0);
        final Random random = new Random();
            while (System.currentTimeMillis() < endTime) {
                if (node.getState().getRole() == NodeRole.LEADER) {

                    Timer getClientTimer = MetricsManager.metricRegistry.timer("raft.client.get");        
                    Timer putClientTimer = MetricsManager.metricRegistry.timer("raft.client.put");

                    int operation = random.nextInt(2);
                    int currentCount = count.get();
                    String command;
                    Timer.Context context;
                    int keyId;

                    if(currentCount < NUM_KEYS){
                        keyId = currentCount;
                        command = "PUT color_" + keyId + " green"; 
                        context = putClientTimer.time();
                    } else {
                        keyId = random.nextInt(currentCount);
                        if (operation == 0){                            
                            command = "PUT color_" + keyId + " new_green"; 
                            context = putClientTimer.time();
                        } else {
                            command = "GET color_" + keyId; 
                            context = getClientTimer.time();
                        }
                    } 

                    try {
                        CountDownLatch latch = new CountDownLatch(1);
                        
                        node.simulateResponseClientRequest(command, new StreamObserver<ClientResponse>() {
                            @Override
                            public void onNext(ClientResponse response) {
                                if (response.getSuccess()) {
                                    if (command.startsWith("PUT") && currentCount < NUM_KEYS){
                                        count.incrementAndGet();
                                        System.out.println("Key added! Total number of keys is now" + currentCount);
                                    }
                                    System.out.println("SUCCESS: green_" + keyId + " committed to the cluster.");
                                } else {
                                    System.out.println("RETRYING to " + command + " to leader node: " + response.getMessage());
                                }
                                latch.countDown();
                            }
                            @Override public void onError(Throwable t) {
                                System.err.println("RPC Error: " + t.getMessage());
                                latch.countDown();
                            }
                            @Override public void onCompleted() {}
                        });

                        latch.await();
                        Thread.sleep(100); 

                    } catch (InterruptedException e) {
                        break;
                    } finally {
                        if (context != null){
                            context.stop();
                        }
                    }
                } else {
                    try {
                        Thread.sleep(1000); 
                    } catch (InterruptedException e) {
                        break;
                    }                
                }       
            }
    }

    public boolean simulateRequest(RaftNode node, String command){
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        node.simulateResponseClientRequest(command, new StreamObserver<ClientResponse>() {
            @Override
            public void onNext(ClientResponse response) {
                result.set(response.getSuccess());
                latch.countDown();
                }
            @Override 
            public void onError(Throwable t) {
                System.err.println("RPC Error: " + t.getMessage());
                latch.countDown();
                }
            @Override public void onCompleted() {}
        });

        try {
            if(!latch.await(2, TimeUnit.SECONDS)){
                return false;
            }
        } catch (InterruptedException e){
            return false;
        }

        return result.get();
    }


    public void simulateLeaderRecovery(RaftNode node){
        long prevSuccessTime = System.currentTimeMillis();
        boolean isLeaderDown = false;

        while(true){
            try {
                boolean result = simulateRequest(node, "PUT color_test purple");

                if(result){
                    if(isLeaderDown){
                        long recoveryTime = System.currentTimeMillis() - prevSuccessTime;
                        System.out.println("Recovery Time: " + recoveryTime);
                        isLeaderDown = false;
                    }
                    prevSuccessTime = System.currentTimeMillis();
                }
            } catch (Exception e){
                if(!isLeaderDown){
                    System.out.println("Leader down. Recording Recovery Time...");
                    isLeaderDown = true;
                }
            }

            try {
                Thread.sleep(100); 
            } catch (InterruptedException e) {
                break;
            }     
        }
    }

}