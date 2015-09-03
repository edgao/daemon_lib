package com.liveramp.daemon_lib.built_in;

import com.liveramp.daemon_lib.DaemonLock;
import com.liveramp.daemon_lib.utils.DaemonException;

public class NoOpDaemonLock implements DaemonLock {
  @Override
  public void lock() throws DaemonException {

  }

  @Override
  public void unlock() throws DaemonException {

  }
}
