package com.raftDB.raft.core;

import com.raftDB.raft.model.RaftNodeState;
import com.raftDB.raft.rpc.*;
import io.grpc.stub.StreamObserver;
import com.raftDB.raft.model.RaftNode;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private final RaftNode raftNode;

    public RaftServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(RequestVoteRequest request,
                            StreamObserver<RequestVoteResponse> responseObserver) {

        RaftNodeState state = raftNode.getState();

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
                //TODO: We will need to store logs in local storage.
                if(raftNode.checkLogConsistency(request.getPrevLogIndex(), request.getPrevLogTerm())){ 
                    raftNode.processLogEntries(request.getEntriesList(), request.getLeaderCommit());
                    success = true;
                } else {
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
}
