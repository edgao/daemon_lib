package com.liveramp.warlock.executors;

import java.io.IOException;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.liveramp.warlock.DaemonLibTestCase;
import com.liveramp.warlock.JobletConfig;
import com.liveramp.warlock.JobletFactory;
import com.liveramp.warlock.executors.forking.ProcessJobletRunner;
import com.liveramp.warlock.executors.processes.ProcessController;
import com.liveramp.warlock.executors.processes.ProcessControllerException;
import com.liveramp.warlock.executors.processes.ProcessDefinition;
import com.liveramp.warlock.utils.DaemonException;
import com.liveramp.warlock.utils.JobletConfigMetadata;
import com.liveramp.warlock.utils.JobletConfigStorage;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;

public class TestForkedJobletExecutor extends DaemonLibTestCase {
  private static final String MOCK_IDENTIFIER = "mock_id";
  private static final int PID = 1;
  private static final int MAX_PROCESSES = 1;
  // TODO(asarkar): add generic test root functionality to DaemonLibTestCase
  private static final String TEST_ROOT = "/tmp/tests/TestForkedJobletExecutor";

  private JobletConfigStorage configStorage;
  private ProcessController processController;
  private ProcessJobletRunner jobletRunner;
  private JobletConfig config;
  private ForkedJobletExecutor<JobletConfig> executor;

  private static final ProcessDefinition<JobletConfigMetadata> DUMMY_PROCESS = new ProcessDefinition<>(1, new JobletConfigMetadata("a"));

  @Before
  public void setup() {
    this.configStorage = Mockito.mock(JobletConfigStorage.class);
    this.processController = Mockito.mock(ProcessController.class);
    this.jobletRunner = Mockito.mock(ProcessJobletRunner.class);
    this.executor = new ForkedJobletExecutor<>(MAX_PROCESSES, MockJobletFactory.class, configStorage, processController, jobletRunner, Maps.<String, String>newHashMap(), TEST_ROOT);

    this.config = Mockito.mock(JobletConfig.class);
  }

  @Test
  public void execute() throws IOException, ProcessControllerException, DaemonException, ClassNotFoundException {
    Mockito.when(configStorage.storeConfig(config)).thenReturn(MOCK_IDENTIFIER);
    Mockito.when(jobletRunner.run(MockJobletFactory.class, configStorage, MOCK_IDENTIFIER, Maps.<String, String>newHashMap(), TEST_ROOT)).thenReturn(PID);

    executor.execute(config);

    Mockito.verify(processController, times(1)).registerProcess(eq(PID), any(JobletConfigMetadata.class)); // TODO(asarkar): shouldn't actually be testing this - its an implementation detail
  }

  @Test
  public void canExecuteAnother() throws ProcessControllerException {
    Mockito.when(processController.getProcesses()).thenReturn(Lists.<ProcessDefinition<JobletConfigMetadata>>newArrayList());
    Assert.assertEquals(true, executor.canExecuteAnother());

    Mockito.when(processController.getProcesses()).thenReturn(Lists.newArrayList(DUMMY_PROCESS));
    Assert.assertEquals(false, executor.canExecuteAnother());
  }

  private interface MockJobletFactory extends JobletFactory<JobletConfig> {
  }
}
