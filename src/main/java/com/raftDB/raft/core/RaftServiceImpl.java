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

        synchronized (state.getLock()) {
            if (request.getTerm() < state.getCurrentTerm()) {
                voteGranted = false;
            } else {
                if (request.getTerm() > state.getCurrentTerm()) {
                    state.setCurrentTerm(request.getTerm());
                    state.setRole(com.raftDB.raft.model.NodeRole.FOLLOWER);
                    state.setVotedFor(null);
                }

                if (state.getVotedFor() == null || state.getVotedFor().equals(request.getCandidateId())) {
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
    public void appendEntries(AppendEntriesRequest request,
                              StreamObserver<AppendEntriesResponse> responseObserver) {

        RaftNodeState state = raftNode.getState();
        boolean success;
        int currentTerm;

        synchronized (state.getLock()) {
            if (request.getTerm() < state.getCurrentTerm()) {
                success = false;
            } else {
                if (request.getTerm() > state.getCurrentTerm()) {
                    state.setCurrentTerm(request.getTerm());
                }
                state.setRole(com.raftDB.raft.model.NodeRole.FOLLOWER);
                success = true;
                raftNode.resetHeartbeatTimer();
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
