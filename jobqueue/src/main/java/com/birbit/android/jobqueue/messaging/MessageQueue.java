package com.birbit.android.jobqueue.messaging;

public interface MessageQueue {
    void post(Message message);
    void postAt(Message message, long readyNs);
}