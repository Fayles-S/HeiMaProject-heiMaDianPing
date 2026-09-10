package com.hmdp.utils;

public interface ILock
{
    boolean tryLock(Long timeoutSeconds);

    void unlock();
}
