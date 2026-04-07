package com.raftDB.raft.model;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;


public class KVStorage{
    
    RocksDB db;
    File dir;
    

    // RocksDB storage for key-value pairs for each node
    // Storage is created in /tmp/rocksdb/[nodeID]
    public KVStorage(String nodeId){

        RocksDB.loadLibrary();
        Options options = new Options();
        options.setCreateIfMissing(true);
        dir = new File("/tmp/rocksdb", nodeId);

        try {
            Files.createDirectories(dir.getParentFile().toPath());
            Files.createDirectories(dir.getAbsoluteFile().toPath()); 
            db = RocksDB.open(options, dir.getAbsolutePath()); 

        } catch (IOException | RocksDBException e) {
            e.printStackTrace();
        }
        System.out.println("Storage initialized in /tmp/rocksdb");
    }
    
    public void put(String key, String value) {
        try {
            db.put(key.getBytes(), value.getBytes());
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
    }

    public String get(String key)  {
        try {
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

}