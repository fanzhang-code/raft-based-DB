package com.raftDB.raft.model;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;


import com.raftDB.raft.config.MetricsManager;
import com.codahale.metrics.Timer;

public class KVStorage{
    
    RocksDB db;
    File dir;
    private final Map<String, String> memoryData = new HashMap<>();
    private static Timer getTimer = MetricsManager.metricRegistry.timer("db.rocksdb.get.latency");
    private static Timer putTimer = MetricsManager.metricRegistry.timer("db.rocksdb.put.latency");

    // RocksDB storage for key-value pairs for each node
    // Storage is created in /tmp/rocksdb/[nodeID] for Mac and Linux and in %USERPROFILE%\AppData\Local\Temp\rocksdb\[nodeID] for Windows
    public KVStorage(String nodeId){

        RocksDB.loadLibrary();
        Options options = new Options();
        options.setCreateIfMissing(true);
        String tempRoot = System.getProperty("java.io.tmpdir");
        Path path = Paths.get(tempRoot, "rocksdb", nodeId);
        dir = path.toFile();        
        // dir = new File("/tmp/rocksdb", nodeId);

        try {
            Files.createDirectories(dir.getParentFile().toPath());
            Files.createDirectories(dir.getAbsoluteFile().toPath()); 
            db = RocksDB.open(options, dir.getAbsolutePath()); 

        } catch (IOException | RocksDBException e) {
            e.printStackTrace();
        }
        System.out.println("Storage initialized in " + path.toString());
        // System.out.println("Storage initialized in /tmp/rocksdb");
    }

    //reads all KV pairs from DB and returns as a Map, put into SnapshotData
    public Map<String, String> exportAll() {
        return new HashMap<>(memoryData);
    }

    public void put(String key, String value) {
        try (Timer.Context context = putTimer.time()){
            db.put(key.getBytes(), value.getBytes());
            memoryData.put(key, value);
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
    }

    public String get(String key)  {
        try (Timer.Context context = getTimer.time()){
            byte[] value = db.get(key.getBytes());
            return value != null ? new String(value) : null;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void delete(String key) {
        try {
            db.delete(key.getBytes());
            memoryData.remove(key);
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
    }

    public void getAll(){
        RocksIterator it = db.newIterator();
        for (it.seekToFirst(); it.isValid(); it.next()) {
            System.out.println(new String(it.key()) + " => " + new String(it.value()));
        }
        it.close();
    }

    /*
    * Method to apply commands to local RocksDB storage.
    * Currently just prints GET results to log/terminal
    * @param command - command written in the form "PUT key value", "GET key", or "DELETE key".
    */
    public void apply(String command){
        String[] parts = command.split(" ");    
        String action = parts[0].toUpperCase();
        if (action.equals("PUT") && parts.length == 3) {
            put(parts[1], parts[2]);
            System.out.println(String.format("DB: Applied PUT %s = %s", parts[1], parts[2]));
        }
        else if (action.equals("GET") && parts.length == 2) {
            System.out.println(String.format("DB: Applied GET %s = %s", parts[1], get(parts[1])));
        }
        else if (action.equals("DELETE") && parts.length == 2) {
            delete(parts[1]);
            System.out.println(String.format("DB: Applied DELETE %s", parts[1]));
        }
        else {
            System.out.println("???");
        }
    }

    public void reRegisterStorageMetrics(){

        if(!MetricsManager.metricRegistry.getMetrics().containsKey("db.rocksdb.put.latency")){
            putTimer = MetricsManager.metricRegistry.timer("db.rocksdb.put.latency");
        }

        if(!MetricsManager.metricRegistry.getMetrics().containsKey("db.rocksdb.get.latency")){
            getTimer = MetricsManager.metricRegistry.timer("db.rocksdb.get.latency");
        }        
    }


}