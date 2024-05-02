package com.rfs.worker;

public interface WorkerState {
    public void run();
    public WorkerState nextState();
}
