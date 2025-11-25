package com.budgetbuddy.aws.codepipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.*;

import java.util.List;

/**
 * AWS CodePipeline Integration Service
 * Provides CI/CD pipeline monitoring and management
 */
@Service
public class CodePipelineService {

    private static final Logger logger = LoggerFactory.getLogger(CodePipelineService.class);

    private final CodePipelineClient codePipelineClient;

    public CodePipelineService(final CodePipelineClient codePipelineClient) {
        this.codePipelineClient = codePipelineClient;
    }

    /**
     * Get pipeline status
     */
    public String getPipelineStatus(final String pipelineName) {
        try {
            GetPipelineStateResponse response = codePipelineClient.getPipelineState(
                    GetPipelineStateRequest.builder()
                            .name(pipelineName)
                            .build());

            return response.stageStates().stream()
                    .filter((stage) -> !stage.latestExecution().statusAsString().equals("Succeeded"))
                    .findFirst()
                    .map((stage) -> stage.latestExecution().statusAsString())
                    .orElse("Succeeded");
        } catch (Exception e) {
            logger.error("Failed to get pipeline status: {}", e.getMessage());
            return "Unknown";
        }
    }

    /**
     * List all pipelines
     */
    public List<PipelineSummary> listPipelines() {
        try {
            ListPipelinesResponse response = codePipelineClient.listPipelines(
                    ListPipelinesRequest.builder()
                            .build());

            return response.pipelines();
        } catch (Exception e) {
            logger.error("Failed to list pipelines: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get pipeline execution history
     */
    public List<PipelineExecutionSummary> getPipelineExecutionHistory(String pipelineName, int maxResults) {
        try {
            ListPipelineExecutionsResponse response = codePipelineClient.listPipelineExecutions(
                    ListPipelineExecutionsRequest.builder()
                            .pipelineName(pipelineName)
                            .maxResults(maxResults)
                            .build());

            return response.pipelineExecutionSummaries();
        } catch (Exception e) {
            logger.error("Failed to get pipeline execution history: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Start pipeline execution
     */
    public String startPipelineExecution(final String pipelineName) {
        try {
            StartPipelineExecutionResponse response = codePipelineClient.startPipelineExecution(
                    StartPipelineExecutionRequest.builder()
                            .name(pipelineName)
                            .build());

            logger.info("Pipeline execution started: {} - Execution ID: {}", pipelineName, response.pipelineExecutionId());
            return response.pipelineExecutionId();
        } catch (Exception e) {
            logger.error("Failed to start pipeline execution: {}", e.getMessage());
            return null;
        }
    }
}

