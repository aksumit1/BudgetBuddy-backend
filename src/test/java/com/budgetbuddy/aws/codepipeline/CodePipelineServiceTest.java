package com.budgetbuddy.aws.codepipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateRequest;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineStateResponse;
import software.amazon.awssdk.services.codepipeline.model.ListPipelineExecutionsRequest;
import software.amazon.awssdk.services.codepipeline.model.ListPipelineExecutionsResponse;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesRequest;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesResponse;
import software.amazon.awssdk.services.codepipeline.model.PipelineExecutionStatus;
import software.amazon.awssdk.services.codepipeline.model.PipelineExecutionSummary;
import software.amazon.awssdk.services.codepipeline.model.PipelineSummary;
import software.amazon.awssdk.services.codepipeline.model.StageExecution;
import software.amazon.awssdk.services.codepipeline.model.StageExecutionStatus;
import software.amazon.awssdk.services.codepipeline.model.StageState;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionRequest;
import software.amazon.awssdk.services.codepipeline.model.StartPipelineExecutionResponse;

/** Unit Tests for CodePipeline Service */
@ExtendWith(MockitoExtension.class)
class CodePipelineServiceTest {

    @Mock private CodePipelineClient codePipelineClient;

    private CodePipelineService service;

    @BeforeEach
    void setUp() {
        service = new CodePipelineService(codePipelineClient);
    }

    @Test
    void testGetPipelineStatusWithSucceededStagesReturnsSucceeded() {
        // Given
        final StageExecution stageExecution =
                StageExecution.builder().status(StageExecutionStatus.SUCCEEDED).build();

        final StageState stageState = StageState.builder().latestExecution(stageExecution).build();

        final GetPipelineStateResponse response =
                GetPipelineStateResponse.builder().stageStates(List.of(stageState)).build();

        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);

        // When
        final String status = service.getPipelineStatus("test-pipeline");

        // Then
        assertEquals("Succeeded", status);
        verify(codePipelineClient).getPipelineState(any(GetPipelineStateRequest.class));
    }

    @Test
    void testGetPipelineStatusWithFailedStageReturnsFailed() {
        // Given
        final StageExecution stageExecution =
                StageExecution.builder().status(StageExecutionStatus.FAILED).build();

        final StageState stageState = StageState.builder().latestExecution(stageExecution).build();

        final GetPipelineStateResponse response =
                GetPipelineStateResponse.builder().stageStates(List.of(stageState)).build();

        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);

        // When
        final String status = service.getPipelineStatus("test-pipeline");

        // Then
        assertEquals("Failed", status);
    }

    @Test
    void testGetPipelineStatusWithExceptionReturnsUnknown() {
        // Given
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final String status = service.getPipelineStatus("test-pipeline");

        // Then
        assertEquals("Unknown", status);
    }

    @Test
    void testListPipelinesWithValidResponseReturnsPipelines() {
        // Given
        final PipelineSummary pipeline = PipelineSummary.builder().name("test-pipeline").build();

        final ListPipelinesResponse response =
                ListPipelinesResponse.builder().pipelines(List.of(pipeline)).build();

        when(codePipelineClient.listPipelines(any(ListPipelinesRequest.class)))
                .thenReturn(response);

        // When
        final List<PipelineSummary> pipelines = service.listPipelines();

        // Then
        assertNotNull(pipelines);
        assertEquals(1, pipelines.size());
        assertEquals("test-pipeline", pipelines.get(0).name());
        verify(codePipelineClient).listPipelines(any(ListPipelinesRequest.class));
    }

    @Test
    void testListPipelinesWithExceptionReturnsEmptyList() {
        // Given
        when(codePipelineClient.listPipelines(any(ListPipelinesRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final List<PipelineSummary> pipelines = service.listPipelines();

        // Then
        assertNotNull(pipelines);
        assertTrue(pipelines.isEmpty());
    }

    @Test
    void testGetPipelineExecutionHistoryWithValidResponseReturnsExecutions() {
        // Given
        final PipelineExecutionSummary execution =
                PipelineExecutionSummary.builder()
                        .pipelineExecutionId("execution-123")
                        .status(PipelineExecutionStatus.SUCCEEDED)
                        .build();

        final ListPipelineExecutionsResponse response =
                ListPipelineExecutionsResponse.builder()
                        .pipelineExecutionSummaries(List.of(execution))
                        .build();

        when(codePipelineClient.listPipelineExecutions(any(ListPipelineExecutionsRequest.class)))
                .thenReturn(response);

        // When
        final List<PipelineExecutionSummary> executions =
                service.getPipelineExecutionHistory("test-pipeline", 10);

        // Then
        assertNotNull(executions);
        assertEquals(1, executions.size());
        assertEquals("execution-123", executions.get(0).pipelineExecutionId());
        verify(codePipelineClient).listPipelineExecutions(any(ListPipelineExecutionsRequest.class));
    }

    @Test
    void testGetPipelineExecutionHistoryWithExceptionReturnsEmptyList() {
        // Given
        when(codePipelineClient.listPipelineExecutions(any(ListPipelineExecutionsRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final List<PipelineExecutionSummary> executions =
                service.getPipelineExecutionHistory("test-pipeline", 10);

        // Then
        assertNotNull(executions);
        assertTrue(executions.isEmpty());
    }

    @Test
    void testStartPipelineExecutionWithValidResponseReturnsExecutionId() {
        // Given
        final StartPipelineExecutionResponse response =
                StartPipelineExecutionResponse.builder()
                        .pipelineExecutionId("execution-123")
                        .build();

        when(codePipelineClient.startPipelineExecution(any(StartPipelineExecutionRequest.class)))
                .thenReturn(response);

        // When
        final String executionId = service.startPipelineExecution("test-pipeline");

        // Then
        assertNotNull(executionId);
        assertEquals("execution-123", executionId);
        verify(codePipelineClient).startPipelineExecution(any(StartPipelineExecutionRequest.class));
    }

    @Test
    void testStartPipelineExecutionWithExceptionReturnsNull() {
        // Given
        when(codePipelineClient.startPipelineExecution(any(StartPipelineExecutionRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));

        // When
        final String executionId = service.startPipelineExecution("test-pipeline");

        // Then
        assertNull(executionId);
    }

    @Test
    void testGetPipelineStatusWithInProgressStageReturnsInProgress() {
        // Given
        final StageExecution stageExecution =
                StageExecution.builder().status(StageExecutionStatus.IN_PROGRESS).build();

        final StageState stageState = StageState.builder().latestExecution(stageExecution).build();

        final GetPipelineStateResponse response =
                GetPipelineStateResponse.builder().stageStates(List.of(stageState)).build();

        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);

        // When
        final String status = service.getPipelineStatus("test-pipeline");

        // Then
        assertEquals("InProgress", status);
    }

    @Test
    void testGetPipelineStatusWithMultipleStagesReturnsFirstNonSucceeded() {
        // Given
        final StageExecution succeededExecution =
                StageExecution.builder().status(StageExecutionStatus.SUCCEEDED).build();

        final StageExecution failedExecution =
                StageExecution.builder().status(StageExecutionStatus.FAILED).build();

        final StageState succeededStage =
                StageState.builder().latestExecution(succeededExecution).build();

        final StageState failedStage = StageState.builder().latestExecution(failedExecution).build();

        final GetPipelineStateResponse response =
                GetPipelineStateResponse.builder()
                        .stageStates(List.of(succeededStage, failedStage))
                        .build();

        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);

        // When
        final String status = service.getPipelineStatus("test-pipeline");

        // Then
        assertEquals("Failed", status);
    }
}
