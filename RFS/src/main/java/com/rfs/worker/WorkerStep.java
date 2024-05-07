package com.rfs.worker;

public interface WorkerStep {
    public void run();
    public WorkerStep nextStep();
}
