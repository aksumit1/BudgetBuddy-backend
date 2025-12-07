package com.budgetbuddy.aws.codepipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for CodePipeline Service
 */
@ExtendWith(MockitoExtension.class)
class CodePipelineServiceTest {

    @Mock
    private CodePipelineClient codePipelineClient;

    private CodePipelineService service;

    @BeforeEach
    void setUp() {
        service = new CodePipelineService(codePipelineClient);
    }

    @Test
    void testGetPipelineStatus_WithSucceededStages_ReturnsSucceeded() {
        // Given
        StageExecution stageExecution = StageExecution.builder()
                .status(StageExecutionStatus.SUCCEEDED)
                .build();
        
        StageState stageState = StageState.builder()
                .latestExecution(stageExecution)
                .build();
        
        GetPipelineStateResponse response = GetPipelineStateResponse.builder()
                .stageStates(List.of(stageState))
                .build();
        
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getPipelineStatus("test-pipeline");
        
        // Then
        assertEquals("Succeeded", status);
        verify(codePipelineClient).getPipelineState(any(GetPipelineStateRequest.class));
    }

    @Test
    void testGetPipelineStatus_WithFailedStage_ReturnsFailed() {
        // Given
        StageExecution stageExecution = StageExecution.builder()
                .status(StageExecutionStatus.FAILED)
                .build();
        
        StageState stageState = StageState.builder()
                .latestExecution(stageExecution)
                .build();
        
        GetPipelineStateResponse response = GetPipelineStateResponse.builder()
                .stageStates(List.of(stageState))
                .build();
        
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getPipelineStatus("test-pipeline");
        
        // Then
        assertEquals("Failed", status);
    }

    @Test
    void testGetPipelineStatus_WithException_ReturnsUnknown() {
        // Given
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        String status = service.getPipelineStatus("test-pipeline");
        
        // Then
        assertEquals("Unknown", status);
    }

    @Test
    void testListPipelines_WithValidResponse_ReturnsPipelines() {
        // Given
        PipelineSummary pipeline = PipelineSummary.builder()
                .name("test-pipeline")
                .build();
        
        ListPipelinesResponse response = ListPipelinesResponse.builder()
                .pipelines(List.of(pipeline))
                .build();
        
        when(codePipelineClient.listPipelines(any(ListPipelinesRequest.class)))
                .thenReturn(response);
        
        // When
        List<PipelineSummary> pipelines = service.listPipelines();
        
        // Then
        assertNotNull(pipelines);
        assertEquals(1, pipelines.size());
        assertEquals("test-pipeline", pipelines.get(0).name());
        verify(codePipelineClient).listPipelines(any(ListPipelinesRequest.class));
    }

    @Test
    void testListPipelines_WithException_ReturnsEmptyList() {
        // Given
        when(codePipelineClient.listPipelines(any(ListPipelinesRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        List<PipelineSummary> pipelines = service.listPipelines();
        
        // Then
        assertNotNull(pipelines);
        assertTrue(pipelines.isEmpty());
    }

    @Test
    void testGetPipelineExecutionHistory_WithValidResponse_ReturnsExecutions() {
        // Given
        PipelineExecutionSummary execution = PipelineExecutionSummary.builder()
                .pipelineExecutionId("execution-123")
                .status(PipelineExecutionStatus.SUCCEEDED)
                .build();
        
        ListPipelineExecutionsResponse response = ListPipelineExecutionsResponse.builder()
                .pipelineExecutionSummaries(List.of(execution))
                .build();
        
        when(codePipelineClient.listPipelineExecutions(any(ListPipelineExecutionsRequest.class)))
                .thenReturn(response);
        
        // When
        List<PipelineExecutionSummary> executions = service.getPipelineExecutionHistory("test-pipeline", 10);
        
        // Then
        assertNotNull(executions);
        assertEquals(1, executions.size());
        assertEquals("execution-123", executions.get(0).pipelineExecutionId());
        verify(codePipelineClient).listPipelineExecutions(any(ListPipelineExecutionsRequest.class));
    }

    @Test
    void testGetPipelineExecutionHistory_WithException_ReturnsEmptyList() {
        // Given
        when(codePipelineClient.listPipelineExecutions(any(ListPipelineExecutionsRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        List<PipelineExecutionSummary> executions = service.getPipelineExecutionHistory("test-pipeline", 10);
        
        // Then
        assertNotNull(executions);
        assertTrue(executions.isEmpty());
    }

    @Test
    void testStartPipelineExecution_WithValidResponse_ReturnsExecutionId() {
        // Given
        StartPipelineExecutionResponse response = StartPipelineExecutionResponse.builder()
                .pipelineExecutionId("execution-123")
                .build();
        
        when(codePipelineClient.startPipelineExecution(any(StartPipelineExecutionRequest.class)))
                .thenReturn(response);
        
        // When
        String executionId = service.startPipelineExecution("test-pipeline");
        
        // Then
        assertNotNull(executionId);
        assertEquals("execution-123", executionId);
        verify(codePipelineClient).startPipelineExecution(any(StartPipelineExecutionRequest.class));
    }

    @Test
    void testStartPipelineExecution_WithException_ReturnsNull() {
        // Given
        when(codePipelineClient.startPipelineExecution(any(StartPipelineExecutionRequest.class)))
                .thenThrow(new RuntimeException("Test exception"));
        
        // When
        String executionId = service.startPipelineExecution("test-pipeline");
        
        // Then
        assertNull(executionId);
    }

    @Test
    void testGetPipelineStatus_WithInProgressStage_ReturnsInProgress() {
        // Given
        StageExecution stageExecution = StageExecution.builder()
                .status(StageExecutionStatus.IN_PROGRESS)
                .build();
        
        StageState stageState = StageState.builder()
                .latestExecution(stageExecution)
                .build();
        
        GetPipelineStateResponse response = GetPipelineStateResponse.builder()
                .stageStates(List.of(stageState))
                .build();
        
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getPipelineStatus("test-pipeline");
        
        // Then
        assertEquals("InProgress", status);
    }

    @Test
    void testGetPipelineStatus_WithMultipleStages_ReturnsFirstNonSucceeded() {
        // Given
        StageExecution succeededExecution = StageExecution.builder()
                .status(StageExecutionStatus.SUCCEEDED)
                .build();
        
        StageExecution failedExecution = StageExecution.builder()
                .status(StageExecutionStatus.FAILED)
                .build();
        
        StageState succeededStage = StageState.builder()
                .latestExecution(succeededExecution)
                .build();
        
        StageState failedStage = StageState.builder()
                .latestExecution(failedExecution)
                .build();
        
        GetPipelineStateResponse response = GetPipelineStateResponse.builder()
                .stageStates(List.of(succeededStage, failedStage))
                .build();
        
        when(codePipelineClient.getPipelineState(any(GetPipelineStateRequest.class)))
                .thenReturn(response);
        
        // When
        String status = service.getPipelineStatus("test-pipeline");
        
        // Then
        assertEquals("Failed", status);
    }
}

