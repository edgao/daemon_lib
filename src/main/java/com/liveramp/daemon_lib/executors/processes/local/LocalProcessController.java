package com.liveramp.daemon_lib.executors.processes.local;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liveramp.daemon_lib.DaemonNotifier;
import com.liveramp.daemon_lib.executors.processes.ProcessController;
import com.liveramp.daemon_lib.executors.processes.ProcessControllerException;
import com.liveramp.daemon_lib.executors.processes.ProcessDefinition;
import com.liveramp.daemon_lib.executors.processes.ProcessMetadata;
import com.liveramp.daemon_lib.utils.DaemonException;


public class LocalProcessController<T extends ProcessMetadata> implements ProcessController<T> {
  private static Logger LOG = LoggerFactory.getLogger(LocalProcessController.class);

  private final DaemonNotifier notifier;
  private final FsHelper fsHelper;
  private final ProcessHandler<T> processHandler;
  private final PidGetter pidGetter;
  private final ProcessMetadata.Serializer<T> metadataSerializer;

  private volatile List<ProcessDefinition<T>> currentProcesses;

  public LocalProcessController(DaemonNotifier notifier, FsHelper fsHelper, ProcessHandler<T> processHandler, PidGetter pidGetter, int pollDelay, ProcessMetadata.Serializer<T> metadataSerializer) {
    this.notifier = notifier;
    this.fsHelper = fsHelper;
    this.processHandler = processHandler;
    this.pidGetter = pidGetter;
    this.metadataSerializer = metadataSerializer;
    this.currentProcesses = null;

    Executors.newScheduledThreadPool(
        1,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("process watcher").build()
    ).scheduleAtFixedRate(new ProcessesWatcher(), 0, pollDelay, TimeUnit.MILLISECONDS);
  }

  @Override
  public void registerProcess(int pid, T metadata) throws ProcessControllerException {
    LOG.info("Registering process {}.", pid);
    ProcessDefinition<T> process = new ProcessDefinition<>(pid, metadata);
    File tmpFile = fsHelper.getPidTmpPath(process.getPid());
    if (!tmpFile.getParentFile().exists() && !tmpFile.getParentFile().mkdirs()) {
      throw new ProcessControllerException(String.format("Unable to create parent directory '%s' for %d pid", tmpFile.getParent(), process.getPid()));
    }
    try {
      fsHelper.writeMetadata(tmpFile, metadataSerializer.toBytes(process.getMetadata()));
    } catch (IOException e) {
      throw new ProcessControllerException(String.format("Unable to create control file '%s' for %d pid.", tmpFile.toString(), process.getPid()), e);
    }
    File pidFile = fsHelper.getPidPath(process.getPid());
    if (!tmpFile.renameTo(pidFile)) {
      throw new ProcessControllerException(String.format("Unable to commit control file '%s' for %d pid.", pidFile.toString(), process.getPid()));
    }
  }

  @Override
  public List<ProcessDefinition<T>> getProcesses() throws ProcessControllerException {
    try {
      return getWatchedProcesses(fsHelper);
    } catch (IOException e) {
      throw new ProcessControllerException(e);
    }
  }

  private class ProcessesWatcher implements Runnable {
    @Override
    public void run() {
      try {
        List<ProcessDefinition<T>> watchedProcesses = getWatchedProcesses(fsHelper);
        Map<Integer, PidGetter.PidData> runningPids = pidGetter.getPids();
        Iterator<ProcessDefinition<T>> iterator = watchedProcesses.iterator();
        while (iterator.hasNext()) {
          ProcessDefinition<T> watchedProcess = iterator.next();
          if (!runningPids.containsKey(watchedProcess.getPid())) {
            LOG.info("Deregister process {}.", watchedProcess.getPid());
            File watchedFile = fsHelper.getPidPath(watchedProcess.getPid());
            try {
              processHandler.onRemove(watchedProcess);
            } catch (DaemonException e) {
              LOG.error("Exception while handling process termination.", e);
              notifier.notify(
                  "Error handling joblet termination in daemon for joblet with pid " + watchedProcess.getPid(),
                  Optional.of(String.format("Configuration: %s. Exception:%s", watchedProcess.getMetadata(), ExceptionUtils.getStackTrace(e))),
                  Optional.<Throwable>absent()
              );
            }
            watchedFile.delete();
            iterator.remove();
          }
        }
      } catch (Exception e) {
        LOG.warn("Exception while watching processes.", e);
      }
    }
  }

  private synchronized List<ProcessDefinition<T>> getWatchedProcesses(FsHelper fsHelper) throws IOException {
    List<ProcessDefinition<T>> pids = Lists.newLinkedList();
    String[] fileList = fsHelper.getBasePath().list();
    if (fileList != null) {
      for (String s : fileList) {
        if (s.matches("\\d+")) {
          int pid = Integer.parseInt(s);
          File pidPath = fsHelper.getPidPath(pid);
          ProcessDefinition<T> process = new ProcessDefinition<>(pid, metadataSerializer.fromBytes(fsHelper.readMetadata(pidPath)));
          pids.add(process);
        }
      }
    }
    currentProcesses = pids;
    return currentProcesses;
  }
}