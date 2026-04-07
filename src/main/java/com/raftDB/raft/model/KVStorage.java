package com.raftDB.raft.model;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;


public class KVStorage{
    
    RocksDB db;
    File dir;
    
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
    
    public synchronized void put(String key, String value) {
        try {
            db.put(key.getBytes(), value.getBytes());
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
    }

    public synchronized String get(String key)  {
        try {
            byte[] value = db.get(key.getBytes());
            return value != null ? new String(value) : null;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return null;
    }

    public synchronized void delete(String key) {
        try {
            db.delete(key.getBytes());
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
    }

    

}