package com.raftDB.raft.core;

import java.util.ArrayList;
import java.util.List;

import com.raftDB.raft.model.RaftNode;
import com.raftDB.raft.model.RaftNodeState;
import com.raftDB.raft.rpc.AppendEntriesRequest;
import com.raftDB.raft.rpc.AppendEntriesResponse;
import com.raftDB.raft.rpc.InstallSnapshotRequest;
import com.raftDB.raft.rpc.InstallSnapshotResponse;
import com.raftDB.raft.rpc.LogEntry;
import com.raftDB.raft.rpc.RaftServiceGrpc;
import com.raftDB.raft.rpc.RequestVoteRequest;
import com.raftDB.raft.rpc.RequestVoteResponse;

import io.grpc.stub.StreamObserver;
//

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;

    public RaftServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(RequestVoteRequest request,
                            StreamObserver<RequestVoteResponse> responseObserver) {

        RaftNodeState state = raftNode.getState();
        raftNode.save(state.getCurrentTerm(), state.getVotedFor(), state.getLog());
        boolean voteGranted = false;
        int currentTerm;

        //A node grants vote if: 1. candidate’s term is at least as new 2. it hasn’t voted yet (or voted for same candidate)
        synchronized (state.getLock()) {
            System.out.println("Current Candidate Node: " + request.getCandidateId());
            System.out.println("Current Request Term: " + request.getTerm());
            System.out.println("Current State Term: " + state.getCurrentTerm());
            System.out.println("----------");
            if (request.getTerm() < state.getCurrentTerm()) {
                voteGranted = false;
            } else {
                //my term is old, should grant vote
                if (request.getTerm() > state.getCurrentTerm()) {
                    state.setCurrentTerm(request.getTerm());
                    state.setRole(com.raftDB.raft.model.NodeRole.FOLLOWER);
                    state.setVotedFor(null);
                }

                // Checks if the candidate's log is update to date with the receiver's log.
                if ((state.getVotedFor() == null || state.getVotedFor().equals(request.getCandidateId())) && raftNode.isLogUpToDate(request.getLastLogIndex(), request.getLastLogTerm())) {
                    //grant vote and record voteFor
                    state.setVotedFor(request.getCandidateId());
                    voteGranted = true;
                } else {
                    System.out.println("Not up to date");
                }
            }

            currentTerm = state.getCurrentTerm();
        }

        RequestVoteResponse response = RequestVoteResponse.newBuilder()
                .setTerm(currentTerm)
                .setVoteGranted(voteGranted)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    //TODO add KV-database and local storage connection.
    public void appendEntries(AppendEntriesRequest request,
                              StreamObserver<AppendEntriesResponse> responseObserver) {

        RaftNodeState state = raftNode.getState();
        boolean success;
        int currentTerm;

        synchronized (state.getLock()) {
            if (request.getTerm() < state.getCurrentTerm()) {
                success = false;
            } else {
                //update my term is old
                if (request.getTerm() > state.getCurrentTerm()) {
                    state.setCurrentTerm(request.getTerm());
                }
                //Successfully received heartbeat from leader
                state.setRole(com.raftDB.raft.model.NodeRole.FOLLOWER);
                success = true;
                //Also reset the vote, so we can vote in the new term. Set to null.
                raftNode.resetHeartbeatTimer(); //reset heartbeat

                //Checks log consistency between receiver and leader. 
                //Process the logs to the receiver and returns a successful response.
                //Otherwise, return a unsuccessful response due to log inconsistency.
                if(raftNode.checkLogConsistency(request.getPrevLogIndex(), request.getPrevLogTerm())){ 
                    raftNode.processLogEntries(request.getEntriesList(), request.getLeaderCommit());
                    success = true;
                    //store logs and state in local storage
                    raftNode.save(state.getCurrentTerm(), state.getVotedFor(), state.getLog());


                } else {
                    System.out.println("No success");
                    //System.out.println(request.getPrevLogIndex());
                    //System.out.println(request.getPrevLogTerm());
                    success = false;
                }

            }
            
            currentTerm = state.getCurrentTerm();
        }

        AppendEntriesResponse response = AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm)
                .setSuccess(success)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void installSnapshot(InstallSnapshotRequest request,
                                 StreamObserver<InstallSnapshotResponse> responseObserver){

        RaftNodeState state = raftNode.getState();
        boolean success = false;
        int currentTerm = state.getCurrentTerm();
        synchronized (state.getLock()) {

            // Don't take the snapshot if it's old
            if(request.getTerm() < state.getCurrentTerm()){
                success = false;
            }
            // Don't take the snapshot if a newer one already exists
            else if(request.getLastSnapshotIndex() <= state.getLastIncludedIndex()){
                success = false;
            }
            else {
                
                int lastSnapIdx = request.getLastSnapshotIndex();
                int lastSnapTerm = request.getLastSnapshotTerm();
                byte[] byteSnapshot = request.getData().toByteArray();
                
                //"Installing" the snapshot
                state.setLastIncludedIndex(lastSnapIdx);
                state.setCommitIndex(lastSnapIdx);
                state.setLastApplied(lastSnapIdx);
                state.setLastIncludedTerm(lastSnapTerm);
                state.setCurrentTerm(lastSnapTerm);
                raftNode.getStoredLogs().altSaveSnapshot(lastSnapIdx, lastSnapTerm, byteSnapshot);;
                //

                currentTerm = state.getCurrentTerm();
                String votedFor = state.getVotedFor();
                
                if(state.getLastApplied() < request.getLastSnapshotIndex()){
                    // Restart entire log if all of it is in the snapshot
                    List<LogEntry> freshLog = new ArrayList<LogEntry>();
                    state.getLog().removeAll(state.getLog());
                    raftNode.save(currentTerm, votedFor, freshLog);
                } else {
                    // Truncate whatever's in the snapshot and keep whatever's not
                    // Copied over from RaftNode without print statements
                    state.getLog().removeIf(entry -> entry.getIndex() <= request.getLastSnapshotIndex());
                    raftNode.save(currentTerm, votedFor, state.getLog());
                }
                success = true;
            }
        }
        InstallSnapshotResponse response = InstallSnapshotResponse.newBuilder()
            .setTerm(currentTerm)
            .setSuccess(success)
            .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }



}
