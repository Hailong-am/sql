/* Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.spark.client;

import static java.util.Collections.emptyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.opensearch.sql.spark.constants.TestConstants.DEFAULT_RESULT_INDEX;
import static org.opensearch.sql.spark.constants.TestConstants.EMRS_APPLICATION_ID;
import static org.opensearch.sql.spark.constants.TestConstants.EMRS_EXECUTION_ROLE;
import static org.opensearch.sql.spark.constants.TestConstants.EMRS_JOB_NAME;
import static org.opensearch.sql.spark.constants.TestConstants.EMR_JOB_ID;
import static org.opensearch.sql.spark.constants.TestConstants.ENTRY_POINT_START_JAR;
import static org.opensearch.sql.spark.constants.TestConstants.QUERY;
import static org.opensearch.sql.spark.constants.TestConstants.SPARK_SUBMIT_PARAMETERS;

import com.amazonaws.services.emrserverless.AWSEMRServerless;
import com.amazonaws.services.emrserverless.model.CancelJobRunResult;
import com.amazonaws.services.emrserverless.model.GetJobRunResult;
import com.amazonaws.services.emrserverless.model.JobRun;
import com.amazonaws.services.emrserverless.model.StartJobRunRequest;
import com.amazonaws.services.emrserverless.model.StartJobRunResult;
import com.amazonaws.services.emrserverless.model.ValidationException;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.sql.common.setting.Settings;
import org.opensearch.sql.legacy.esdomain.LocalClusterState;
import org.opensearch.sql.legacy.metrics.Metrics;
import org.opensearch.sql.opensearch.setting.OpenSearchSettings;

@ExtendWith(MockitoExtension.class)
public class EmrServerlessClientImplTest {
  @Mock private AWSEMRServerless emrServerless;

  @Mock private OpenSearchSettings settings;

  @Captor private ArgumentCaptor<StartJobRunRequest> startJobRunRequestArgumentCaptor;

  @BeforeEach
  public void setUp() {
    doReturn(emptyList()).when(settings).getSettings();
    when(settings.getSettingValue(Settings.Key.METRICS_ROLLING_INTERVAL)).thenReturn(3600L);
    when(settings.getSettingValue(Settings.Key.METRICS_ROLLING_WINDOW)).thenReturn(600L);
    LocalClusterState.state().setPluginSettings(settings);
    Metrics.getInstance().registerDefaultMetrics();
  }

  @Test
  void testStartJobRun() {
    StartJobRunResult response = new StartJobRunResult();
    when(emrServerless.startJobRun(any())).thenReturn(response);

    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    emrServerlessClient.startJobRun(
        new StartJobRequest(
            QUERY,
            EMRS_JOB_NAME,
            EMRS_APPLICATION_ID,
            EMRS_EXECUTION_ROLE,
            SPARK_SUBMIT_PARAMETERS,
            new HashMap<>(),
            false,
            DEFAULT_RESULT_INDEX));
    verify(emrServerless, times(1)).startJobRun(startJobRunRequestArgumentCaptor.capture());
    StartJobRunRequest startJobRunRequest = startJobRunRequestArgumentCaptor.getValue();
    Assertions.assertEquals(EMRS_APPLICATION_ID, startJobRunRequest.getApplicationId());
    Assertions.assertEquals(EMRS_EXECUTION_ROLE, startJobRunRequest.getExecutionRoleArn());
    Assertions.assertEquals(
        ENTRY_POINT_START_JAR, startJobRunRequest.getJobDriver().getSparkSubmit().getEntryPoint());
    Assertions.assertEquals(
        List.of(QUERY, DEFAULT_RESULT_INDEX),
        startJobRunRequest.getJobDriver().getSparkSubmit().getEntryPointArguments());
  }

  @Test
  void testStartJobRunWithErrorMetric() {
    doThrow(new ValidationException("Couldn't start job")).when(emrServerless).startJobRun(any());
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    RuntimeException runtimeException =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                emrServerlessClient.startJobRun(
                    new StartJobRequest(
                        QUERY,
                        EMRS_JOB_NAME,
                        EMRS_APPLICATION_ID,
                        EMRS_EXECUTION_ROLE,
                        SPARK_SUBMIT_PARAMETERS,
                        new HashMap<>(),
                        false,
                        null)));
    Assertions.assertEquals("Internal Server Error.", runtimeException.getMessage());
  }

  @Test
  void testStartJobRunResultIndex() {
    StartJobRunResult response = new StartJobRunResult();
    when(emrServerless.startJobRun(any())).thenReturn(response);

    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    emrServerlessClient.startJobRun(
        new StartJobRequest(
            QUERY,
            EMRS_JOB_NAME,
            EMRS_APPLICATION_ID,
            EMRS_EXECUTION_ROLE,
            SPARK_SUBMIT_PARAMETERS,
            new HashMap<>(),
            false,
            "foo"));
  }

  @Test
  void testGetJobRunState() {
    JobRun jobRun = new JobRun();
    jobRun.setState("Running");
    GetJobRunResult response = new GetJobRunResult();
    response.setJobRun(jobRun);
    when(emrServerless.getJobRun(any())).thenReturn(response);
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    emrServerlessClient.getJobRunResult(EMRS_APPLICATION_ID, "123");
  }

  @Test
  void testGetJobRunStateWithErrorMetric() {
    doThrow(new ValidationException("Not a good job")).when(emrServerless).getJobRun(any());
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    RuntimeException runtimeException =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> emrServerlessClient.getJobRunResult(EMRS_APPLICATION_ID, "123"));
    Assertions.assertEquals("Internal Server Error.", runtimeException.getMessage());
  }

  @Test
  void testCancelJobRun() {
    when(emrServerless.cancelJobRun(any()))
        .thenReturn(new CancelJobRunResult().withJobRunId(EMR_JOB_ID));
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    CancelJobRunResult cancelJobRunResult =
        emrServerlessClient.cancelJobRun(EMRS_APPLICATION_ID, EMR_JOB_ID);
    Assertions.assertEquals(EMR_JOB_ID, cancelJobRunResult.getJobRunId());
  }

  @Test
  void testCancelJobRunWithErrorMetric() {
    doThrow(new RuntimeException()).when(emrServerless).cancelJobRun(any());
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    Assertions.assertThrows(
        RuntimeException.class, () -> emrServerlessClient.cancelJobRun(EMRS_APPLICATION_ID, "123"));
  }

  @Test
  void testCancelJobRunWithValidationException() {
    doThrow(new ValidationException("Error")).when(emrServerless).cancelJobRun(any());
    EmrServerlessClientImpl emrServerlessClient = new EmrServerlessClientImpl(emrServerless);
    RuntimeException runtimeException =
        Assertions.assertThrows(
            RuntimeException.class,
            () -> emrServerlessClient.cancelJobRun(EMRS_APPLICATION_ID, EMR_JOB_ID));
    Assertions.assertEquals("Internal Server Error.", runtimeException.getMessage());
  }
}
