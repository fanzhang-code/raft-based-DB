package com.raftDB.raft.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import com.raftDB.raft.rpc.LogEntry;

public class LogStorage implements Serializable{
    RocksDB db;
    File dir;
    
    //separate RocksDB storage for each node's persistent state
    //Stores current term, candidate that received vote, and the log
    //Storage created in /tmp/rocksdb/[nodeID]/state
    public LogStorage(String nodeId) {
        RocksDB.loadLibrary();
        Options options = new Options();
        options.setCreateIfMissing(true);
        dir = new File("/tmp/rocksdb/" + nodeId + "/state");

        try {
            Files.createDirectories(dir.getParentFile().toPath());
            Files.createDirectories(dir.getAbsoluteFile().toPath()); 
            db = RocksDB.open(options, dir.getAbsolutePath());        

        } catch (IOException | RocksDBException e) {
            e.printStackTrace();
        }
        System.out.println("Persistent State storage initialized in /tmp/rocksdb/state");
    }

    //should be called everytime currentTerm, votedFor, or log is changed
    //persists current state to disk
    public void saveState(int currentTerm, String votedFor, List<LogEntry> log){
        try {
            db.put("currentTerm".getBytes(), String.valueOf(currentTerm).getBytes());
            db.put("votedFor".getBytes(), votedFor != null ? votedFor.getBytes() : "null".getBytes());    
            db.put("log".getBytes(), serializeLog(log));

            //System.out.println("State saved to disk");
        } catch (IOException | RocksDBException e) {
            e.printStackTrace();
        }
    }
    
    public List<LogEntry> getLog(){
        List<LogEntry> log = new ArrayList<>();
        try{
            byte[] logBytes = db.get("log".getBytes());
            if (logBytes != null) {
                log = deserializeLog(logBytes);
            }
        } catch(IOException | RocksDBException | ClassNotFoundException e){
            e.printStackTrace();
        }
        return log;
    }
    public String getVotedFor(){
        try {
            byte[] votedFor = db.get("votedFor".getBytes());
            return votedFor != null ? new String(votedFor) : null;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return null;
    }

    public int getCurrentTerm(){
        try {
            byte[] currentTerm = db.get("currentTerm".getBytes());
            return currentTerm != null ? Integer.parseInt(new String(currentTerm)) : 0;
        } catch (RocksDBException e) {
            e.printStackTrace();
        }
        return 0;
    }
    private static byte[] serializeLog(List<LogEntry> log) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(log);
        return bos.toByteArray();
    }

    private static List<LogEntry> deserializeLog(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bis);
        return (List<LogEntry>) ois.readObject();
    }
}