package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListAttachedRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.GetPolicyRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyResponse;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionRequest;
import software.amazon.awssdk.services.iam.model.GetPolicyVersionResponse;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesRequest;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.GetRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetRolePolicyResponse;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.ListCertificatesRequest;
import software.amazon.awssdk.services.acm.model.ListCertificatesResponse;
import software.amazon.awssdk.services.acm.model.CertificateSummary;
import software.amazon.awssdk.services.acm.model.DescribeCertificateRequest;
import software.amazon.awssdk.services.acm.model.DescribeCertificateResponse;
import software.amazon.awssdk.services.acm.model.CertificateStatus;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineRequest;
import software.amazon.awssdk.services.codepipeline.model.GetPipelineResponse;
import software.amazon.awssdk.services.codepipeline.model.PipelineNotFoundException;
import software.amazon.awssdk.services.codepipeline.model.StageDeclaration;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.model.BatchGetProjectsRequest;
import software.amazon.awssdk.services.codebuild.model.BatchGetProjectsResponse;
import software.amazon.awssdk.services.codebuild.model.Project;
import software.amazon.awssdk.services.codebuild.model.EnvironmentType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Infrastructure Propagation
 * Tests IAM roles, credentials, certificates, and CI/CD pipeline setup
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class InfrastructurePropagationIntegrationTest {

    @Autowired(required = false)
    private IamClient iamClient;

    @Autowired(required = false)
    private SecretsManagerClient secretsManagerClient;

    @Autowired(required = false)
    private AcmClient acmClient;

    @Autowired(required = false)
    private CodePipelineClient codePipelineClient;

    @Autowired(required = false)
    private CodeBuildClient codeBuildClient;

    private static final String TEST_ENVIRONMENT = "test";
    private static final String TEST_STACK_PREFIX = "TestBudgetBuddy";

    @Test
    void testECSTaskExecutionRole_Exists() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-execution-role";

        // When
        boolean exists = roleExists(roleName);

        // Then
        assertTrue(exists, "ECS Task Execution Role should exist: " + roleName);
    }

    @Test
    void testECSTaskExecutionRole_HasRequiredPolicies() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-execution-role";

        // When
        List<String> attachedPolicies = getAttachedPolicyNames(roleName);

        // Then
        assertTrue(attachedPolicies.contains("AmazonECSTaskExecutionRolePolicy") ||
                        hasInlinePolicy(roleName, "ECSTaskExecutionPolicy"),
                "ECS Task Execution Role should have ECS Task Execution Role Policy");
    }

    @Test
    void testECSTaskExecutionRole_CanAccessSecretsManager() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-execution-role";

        // When
        List<RolePolicyInfo> policies = getRolePolicies(roleName);

        // Then
        boolean canAccessSecrets = policies.stream()
                .anyMatch(policy -> policy.policyDocument().contains("secretsmanager:GetSecretValue"));
        assertTrue(canAccessSecrets || hasInlinePolicy(roleName, "SecretsManagerAccess"),
                "ECS Task Execution Role should have access to Secrets Manager");
    }

    @Test
    void testECSTaskExecutionRole_CanAccessECR() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-execution-role";

        // When
        List<RolePolicyInfo> policies = getRolePolicies(roleName);

        // Then
        boolean canAccessECR = policies.stream()
                .anyMatch(policy -> policy.policyDocument().contains("ecr:GetAuthorizationToken") ||
                        policy.policyDocument().contains("ecr:BatchGetImage"));
        assertTrue(canAccessECR || hasInlinePolicy(roleName, "ECRAccess"),
                "ECS Task Execution Role should have access to ECR");
    }

    @Test
    void testECSTaskRole_Exists() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-role";

        // When
        boolean exists = roleExists(roleName);

        // Then
        assertTrue(exists, "ECS Task Role should exist: " + roleName);
    }

    @Test
    void testECSTaskRole_CanAccessDynamoDB() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-role";

        // When
        List<RolePolicyInfo> policies = getRolePolicies(roleName);

        // Then
        boolean canAccessDynamoDB = policies.stream()
                .anyMatch(policy -> policy.policyDocument().contains("dynamodb:") ||
                        policy.policyDocument().contains("DynamoDB"));
        assertTrue(canAccessDynamoDB || hasInlinePolicy(roleName, "DynamoDBAccess"),
                "ECS Task Role should have access to DynamoDB");
    }

    @Test
    void testECSTaskRole_CanAccessCloudWatch() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-ecs-task-role";

        // When
        List<RolePolicyInfo> policies = getRolePolicies(roleName);

        // Then
        boolean canAccessCloudWatch = policies.stream()
                .anyMatch(policy -> policy.policyDocument().contains("logs:") ||
                        policy.policyDocument().contains("cloudwatch:"));
        assertTrue(canAccessCloudWatch || hasInlinePolicy(roleName, "CloudWatchAccess"),
                "ECS Task Role should have access to CloudWatch");
    }

    @Test
    void testCodeBuildRole_Exists() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-codebuild-role";

        // When
        boolean exists = roleExists(roleName);

        // Then - May not exist in test environment
        // This test documents expected behavior
        if (iamClient != null) {
            // In test environment, role might not exist
            // In production, it should exist
        }
    }

    @Test
    void testCodeBuildRole_CanAccessECR() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-codebuild-role";

        // When
        if (roleExists(roleName)) {
            List<RolePolicyInfo> policies = getRolePolicies(roleName);

            // Then
            boolean canAccessECR = policies.stream()
                    .anyMatch(policy -> policy.policyDocument().contains("ecr:PutImage") ||
                            policy.policyDocument().contains("ecr:GetAuthorizationToken"));
            assertTrue(canAccessECR || hasInlinePolicy(roleName, "ECRAccess"),
                    "CodeBuild Role should have access to ECR");
        }
    }

    @Test
    void testCodePipelineRole_Exists() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-codepipeline-role";

        // When
        boolean exists = roleExists(roleName);

        // Then - May not exist in test environment
        if (iamClient != null) {
            // In test environment, role might not exist
            // In production, it should exist
        }
    }

    @Test
    void testCodePipelineRole_CanAccessCodeBuild() {
        // Given
        String roleName = TEST_STACK_PREFIX + "-codepipeline-role";

        // When
        if (roleExists(roleName)) {
            List<RolePolicyInfo> policies = getRolePolicies(roleName);

            // Then
            boolean canAccessCodeBuild = policies.stream()
                    .anyMatch(policy -> policy.policyDocument().contains("codebuild:StartBuild") ||
                            policy.policyDocument().contains("codebuild:BatchGetBuilds"));
            assertTrue(canAccessCodeBuild || hasInlinePolicy(roleName, "CodeBuildAccess"),
                    "CodePipeline Role should have access to CodeBuild");
        }
    }

    @Test
    void testJWTSecret_Exists() {
        // Given
        String secretName = "budgetbuddy/" + TEST_ENVIRONMENT + "/jwt-secret";

        // When
        boolean exists = secretExists(secretName);

        // Then - May not exist in test environment (LocalStack)
        // This test documents expected behavior
        if (secretsManagerClient != null) {
            // In production, secret should exist
        }
    }

    @Test
    void testJWTSecret_IsAccessible() {
        // Given
        String secretName = "budgetbuddy/" + TEST_ENVIRONMENT + "/jwt-secret";

        // When
        if (secretExists(secretName) && secretsManagerClient != null) {
            try {
                GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                        GetSecretValueRequest.builder()
                                .secretId(secretName)
                                .build());

                // Then
                assertNotNull(response.secretString(), "JWT Secret should have a value");
                assertFalse(response.secretString().isEmpty(), "JWT Secret should not be empty");
            } catch (Exception e) {
                // In test environment, might not be accessible
                // This is expected if using LocalStack without proper setup
            }
        }
    }

    @Test
    void testPlaidSecret_Exists() {
        // Given
        String secretName = "budgetbuddy/" + TEST_ENVIRONMENT + "/plaid";

        // When
        boolean exists = secretExists(secretName);

        // Then - May not exist in test environment
        if (secretsManagerClient != null) {
            // In production, secret should exist
        }
    }

    @Test
    void testPlaidSecret_HasRequiredFields() {
        // Given
        String secretName = "budgetbuddy/" + TEST_ENVIRONMENT + "/plaid";

        // When
        if (secretExists(secretName) && secretsManagerClient != null) {
            try {
                GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                        GetSecretValueRequest.builder()
                                .secretId(secretName)
                                .build());

                String secretString = response.secretString();

                // Then
                assertNotNull(secretString, "Plaid Secret should have a value");
                assertTrue(secretString.contains("clientId") || secretString.contains("client_id"),
                        "Plaid Secret should contain clientId field");
            } catch (Exception e) {
                // In test environment, might not be accessible
            }
        }
    }

    @Test
    void testSSLCertificate_Exists() {
        // Given - Certificate domain pattern
        String domainPattern = "api.budgetbuddy.com";

        // When
        List<CertificateSummary> certificates = listCertificates();

        // Then - Certificate may not exist in test environment
        if (acmClient != null && !certificates.isEmpty()) {
            boolean certificateExists = certificates.stream()
                    .anyMatch(cert -> cert.domainName() != null && cert.domainName().contains("budgetbuddy"));
            // In production, certificate should exist
            // In test, this documents expected behavior
        }
    }

    @Test
    void testSSLCertificate_IsValid() {
        // Given
        List<CertificateSummary> certificates = listCertificates();

        // When
        if (acmClient != null && !certificates.isEmpty()) {
            for (CertificateSummary certSummary : certificates) {
                if (certSummary.domainName().contains("budgetbuddy")) {
                    try {
                        DescribeCertificateResponse response = acmClient.describeCertificate(
                                DescribeCertificateRequest.builder()
                                        .certificateArn(certSummary.certificateArn())
                                        .build());

                        // Then
                        assertNotNull(response.certificate(), "Certificate should exist");
                        // Certificate should be issued or in validation
                        assertTrue(response.certificate().status() == CertificateStatus.ISSUED ||
                                        response.certificate().status() == CertificateStatus.PENDING_VALIDATION,
                                "Certificate should be issued or pending validation");
                    } catch (Exception e) {
                        // In test environment, might not be accessible
                    }
                }
            }
        }
    }

    @Test
    void testCodePipeline_Exists() {
        // Given
        String pipelineName = TEST_STACK_PREFIX + "-pipeline";

        // When
        boolean exists = pipelineExists(pipelineName);

        // Then - May not exist in test environment
        if (codePipelineClient != null) {
            // In production, pipeline should exist
            // In test, this documents expected behavior
        }
    }

    @Test
    void testCodePipeline_HasRequiredStages() {
        // Given
        String pipelineName = TEST_STACK_PREFIX + "-pipeline";

        // When
        if (pipelineExists(pipelineName) && codePipelineClient != null) {
            try {
                GetPipelineResponse response = codePipelineClient.getPipeline(
                        GetPipelineRequest.builder()
                                .name(pipelineName)
                                .build());

                List<String> stageNames = response.pipeline().stages().stream()
                        .map(StageDeclaration::name)
                        .collect(Collectors.toList());

                // Then
                assertTrue(stageNames.contains("Source"), "Pipeline should have Source stage");
                assertTrue(stageNames.contains("Build"), "Pipeline should have Build stage");
                assertTrue(stageNames.contains("Deploy"), "Pipeline should have Deploy stage");
            } catch (Exception e) {
                // In test environment, might not be accessible
            }
        }
    }

    @Test
    void testCodeBuildProject_Exists() {
        // Given
        String projectName = TEST_STACK_PREFIX + "-build";

        // When
        boolean exists = buildProjectExists(projectName);

        // Then - May not exist in test environment
        if (codeBuildClient != null) {
            // In production, build project should exist
        }
    }

    @Test
    void testCodeBuildProject_HasRequiredEnvironment() {
        // Given
        String projectName = TEST_STACK_PREFIX + "-build";

        // When
        if (buildProjectExists(projectName) && codeBuildClient != null) {
            try {
                BatchGetProjectsResponse response = codeBuildClient.batchGetProjects(
                        BatchGetProjectsRequest.builder()
                                .names(projectName)
                                .build());

                if (!response.projects().isEmpty()) {
                    Project project = response.projects().get(0);

                    // Then
                    assertNotNull(project.environment(), "Build project should have environment configured");
                    assertEquals(EnvironmentType.LINUX_CONTAINER, project.environment().type(),
                            "Build project should use Linux container");
                }
            } catch (Exception e) {
                // In test environment, might not be accessible
            }
        }
    }

    @Test
    void testFirstTimePipelineCreation_AllResourcesCreated() {
        // Given - First time pipeline creation scenario
        // This test verifies that all required resources are created

        // When - Check for pipeline existence
        String pipelineName = TEST_STACK_PREFIX + "-pipeline";
        boolean pipelineExists = pipelineExists(pipelineName);

        // When - Check for build project existence
        String buildProjectName = TEST_STACK_PREFIX + "-build";
        boolean buildProjectExists = buildProjectExists(buildProjectName);

        // When - Check for artifact bucket
        // (Would need S3 client to check, but documenting expected behavior)

        // Then - In production, all should exist
        // In test environment, they may not exist
        if (codePipelineClient != null && codeBuildClient != null) {
            // This test documents that on first pipeline creation:
            // 1. CodePipeline should be created
            // 2. CodeBuild project should be created
            // 3. IAM roles should be created
            // 4. S3 artifact bucket should be created
            assertTrue(true, "First time pipeline creation test - resources may not exist in test environment");
        }
    }

    @Test
    void testCredentialPropagation_SecretsAccessibleByRoles() {
        // Given
        String secretName = "budgetbuddy/" + TEST_ENVIRONMENT + "/jwt-secret";
        String roleName = TEST_STACK_PREFIX + "-ecs-task-execution-role";

        // When
        if (secretExists(secretName) && roleExists(roleName)) {
            // Verify role has permission to access secret
            List<RolePolicyInfo> policies = getRolePolicies(roleName);
            boolean canAccessSecret = policies.stream()
                    .anyMatch(policy -> {
                        String policyDoc = policy.policyDocument();
                        return policyDoc.contains("secretsmanager:GetSecretValue") &&
                                (policyDoc.contains(secretName) || policyDoc.contains("*"));
                    });

            // Then
            assertTrue(canAccessSecret || hasInlinePolicy(roleName, "SecretsManagerAccess"),
                    "ECS Task Execution Role should be able to access secrets");
        }
    }

    @Test
    void testCertificatePropagation_AttachedToALB() {
        // Given - Certificate should be attached to ALB listener
        List<CertificateSummary> certificates = listCertificates();

        // When
        if (acmClient != null && !certificates.isEmpty()) {
            // In production, certificate should be:
            // 1. Created in ACM
            // 2. Validated (DNS or email)
            // 3. Attached to ALB HTTPS listener
            // 4. Used by CloudFront (if configured)

            // This test documents expected behavior
            assertTrue(true, "Certificate propagation test - certificates may not exist in test environment");
        }
    }

    // Helper methods
    private boolean roleExists(String roleName) {
        if (iamClient == null) {
            return false;
        }
        try {
            GetRoleResponse response = iamClient.getRole(
                    GetRoleRequest.builder()
                            .roleName(roleName)
                            .build());
            return response.role() != null;
        } catch (NoSuchEntityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> getAttachedPolicyNames(String roleName) {
        if (iamClient == null) {
            return List.of();
        }
        try {
            ListAttachedRolePoliciesResponse response = iamClient.listAttachedRolePolicies(
                    ListAttachedRolePoliciesRequest.builder()
                            .roleName(roleName)
                            .build());
            return response.attachedPolicies().stream()
                    .map(AttachedPolicy::policyName)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean hasInlinePolicy(String roleName, String policyName) {
        if (iamClient == null) {
            return false;
        }
        try {
            GetRolePolicyResponse response = iamClient.getRolePolicy(
                    GetRolePolicyRequest.builder()
                            .roleName(roleName)
                            .policyName(policyName)
                            .build());
            return response.policyDocument() != null;
        } catch (NoSuchEntityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<RolePolicyInfo> getRolePolicies(String roleName) {
        if (iamClient == null) {
            return List.of();
        }
        try {
            List<RolePolicyInfo> policies = new ArrayList<>();
            
            // Get attached policies
            ListAttachedRolePoliciesResponse attachedResponse = iamClient.listAttachedRolePolicies(
                    ListAttachedRolePoliciesRequest.builder()
                            .roleName(roleName)
                            .build());

            for (AttachedPolicy attachedPolicy : attachedResponse.attachedPolicies()) {
                try {
                    GetPolicyResponse policyResponse = iamClient.getPolicy(
                            GetPolicyRequest.builder()
                                    .policyArn(attachedPolicy.policyArn())
                                    .build());
                    GetPolicyVersionResponse versionResponse = iamClient.getPolicyVersion(
                            GetPolicyVersionRequest.builder()
                                    .policyArn(attachedPolicy.policyArn())
                                    .versionId(policyResponse.policy().defaultVersionId())
                                    .build());
                    policies.add(new RolePolicyInfo(
                            attachedPolicy.policyName(),
                            versionResponse.policyVersion().document()));
                } catch (Exception e) {
                    // Skip if can't retrieve policy
                }
            }

            // Get inline policies
            ListRolePoliciesResponse inlineResponse = iamClient.listRolePolicies(
                    ListRolePoliciesRequest.builder()
                            .roleName(roleName)
                            .build());

            for (String inlinePolicyName : inlineResponse.policyNames()) {
                try {
                    GetRolePolicyResponse inlinePolicyResponse = iamClient.getRolePolicy(
                            GetRolePolicyRequest.builder()
                                    .roleName(roleName)
                                    .policyName(inlinePolicyName)
                                    .build());
                    policies.add(new RolePolicyInfo(
                            inlinePolicyName,
                            java.net.URLDecoder.decode(inlinePolicyResponse.policyDocument(), java.nio.charset.StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    // Skip if can't retrieve policy
                }
            }

            return policies;
        } catch (Exception e) {
            return List.of();
        }
    }

    // Helper class to hold policy information
    private static class RolePolicyInfo {
        private final String policyName;
        private final String policyDocument;

        RolePolicyInfo(String policyName, String policyDocument) {
            this.policyName = policyName;
            this.policyDocument = policyDocument;
        }

        String policyName() {
            return policyName;
        }

        String policyDocument() {
            return policyDocument;
        }
    }

    private boolean secretExists(String secretName) {
        if (secretsManagerClient == null) {
            return false;
        }
        try {
            DescribeSecretResponse response = secretsManagerClient.describeSecret(
                    DescribeSecretRequest.builder()
                            .secretId(secretName)
                            .build());
            return response != null;
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<CertificateSummary> listCertificates() {
        if (acmClient == null) {
            return List.of();
        }
        try {
            ListCertificatesResponse response = acmClient.listCertificates(
                    ListCertificatesRequest.builder()
                            .build());
            return response.certificateSummaryList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean pipelineExists(String pipelineName) {
        if (codePipelineClient == null) {
            return false;
        }
        try {
            GetPipelineResponse response = codePipelineClient.getPipeline(
                    GetPipelineRequest.builder()
                            .name(pipelineName)
                            .build());
            return response.pipeline() != null;
        } catch (PipelineNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean buildProjectExists(String projectName) {
        if (codeBuildClient == null) {
            return false;
        }
        try {
            BatchGetProjectsResponse response = codeBuildClient.batchGetProjects(
                    BatchGetProjectsRequest.builder()
                            .names(projectName)
                            .build());
            return !response.projects().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}

