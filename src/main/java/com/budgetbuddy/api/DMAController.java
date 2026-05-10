package com.budgetbuddy.api;


import java.util.Locale;
import com.budgetbuddy.compliance.dma.DMAComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Digital Markets Act (DMA) Compliance REST Controller Provides endpoints for DMA compliance
 * requirements
 *
 * <p>DMA Requirements: - Article 6: Data Portability - Article 7: Interoperability - Article 8:
 * Fair Access - Article 9: Data Sharing
 */
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings("PMD.DataClass")
@RestController
@RequestMapping("/api/dma")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DMAController {

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final String MESSAGE = "message";

    private static final Logger LOGGER = LoggerFactory.getLogger(DMAController.class);

    private final DMAComplianceService dmaComplianceService;
    private final UserService userService;

    public DMAController(
            final DMAComplianceService dmaComplianceService, final UserService userService) {
        this.dmaComplianceService = dmaComplianceService;
        this.userService = userService;
    }

    /**
     * DMA Article 6: Data Portability Export data in standardized, machine-readable format GET
     * /api/dma/export?format=JSON|CSV|XML
     */
    @GetMapping(
            value = "/export",
            produces = {
                MediaType.APPLICATION_JSON_VALUE,
                MediaType.APPLICATION_XML_VALUE,
                "text/csv"
            })
    @Operation(
            summary = "Export Data (DMA)",
            description = "Exports user data in standardized format (JSON, CSV, or XML)")
    @ApiResponse(responseCode = "200", description = "Data exported successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<String> exportData(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "JSON") final String format) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final String data = dmaComplianceService.exportDataPortable(user.getUserId(), format);

        // Set appropriate content type
        final MediaType contentType =
                switch (format.toUpperCase(Locale.ROOT)) {
                    case "CSV" -> MediaType.parseMediaType("text/csv");
                    case "XML" -> MediaType.APPLICATION_XML;
                    default -> MediaType.APPLICATION_JSON;
                };

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=user-data." + format.toLowerCase(Locale.ROOT))
                .body(data);
    }

    /**
     * DMA Article 7: Interoperability Get interoperability endpoint for third-party access GET
     * /api/dma/interoperability/endpoint
     */
    @GetMapping("/interoperability/endpoint")
    @Operation(
            summary = "Get Interoperability Endpoint",
            description = "Returns API endpoint for third-party data interoperability")
    @ApiResponse(responseCode = "200", description = "Endpoint retrieved successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> getInteroperabilityEndpoint(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final String endpoint = dmaComplianceService.getInteroperabilityEndpoint(user.getUserId());

        final Map<String, Object> response = new HashMap<>();
        response.put("endpoint", endpoint);
        response.put("userId", user.getUserId());
        response.put(MESSAGE, "Use this endpoint with proper authentication to access your data");

        return ResponseEntity.ok(response);
    }

    /**
     * DMA Article 8: Fair Access Authorize third-party access to user data POST /api/dma/authorize
     */
    @PostMapping("/authorize")
    @Operation(
            summary = "Authorize Third-Party Access",
            description = "Authorizes third-party access to user data")
    @ApiResponse(responseCode = "200", description = "Authorization granted successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    public ResponseEntity<Map<String, Object>> authorizeThirdPartyAccess(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final AuthorizeThirdPartyRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        if (request == null || request.getThirdPartyId() == null || request.getScope() == null) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Third-party ID and scope are required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final boolean authorized =
                dmaComplianceService.authorizeThirdPartyAccess(
                        user.getUserId(), request.getThirdPartyId(), request.getScope());

        final Map<String, Object> response = new HashMap<>();
        response.put("authorized", authorized);
        response.put("thirdPartyId", request.getThirdPartyId());
        response.put("scope", request.getScope());
        response.put(
                MESSAGE,
                authorized ? "Third-party access authorized" : "Third-party access denied");

        LOGGER.info(
                "DMA: Third-party access authorized - User: {}, ThirdParty: {}, Scope: {}",
                user.getUserId(),
                request.getThirdPartyId(),
                request.getScope());

        return ResponseEntity.ok(response);
    }

    /**
     * DMA Article 9: Data Sharing Share user data with authorized third party POST /api/dma/share
     */
    @PostMapping("/share")
    @Operation(
            summary = "Share Data with Third Party",
            description = "Shares user data with authorized third party")
    @ApiResponse(responseCode = "200", description = "Data shared successfully")
    @ApiResponse(responseCode = "401", description = "User not authenticated")
    @ApiResponse(responseCode = "403", description = "Third-party access not authorized")
    public ResponseEntity<Map<String, Object>> shareDataWithThirdParty(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final ShareDataRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }
        if (request == null || request.getThirdPartyId() == null || request.getDataType() == null) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Third-party ID and data type are required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        final String data =
                dmaComplianceService.shareDataWithThirdParty(
                        user.getUserId(), request.getThirdPartyId(), request.getDataType());

        final Map<String, Object> response = new HashMap<>();
        response.put("data", data);
        response.put("thirdPartyId", request.getThirdPartyId());
        response.put("dataType", request.getDataType());
        response.put(MESSAGE, "Data shared successfully with authorized third party");

        LOGGER.info(
                "DMA: Data shared with third party - User: {}, ThirdParty: {}, DataType: {}",
                user.getUserId(),
                request.getThirdPartyId(),
                request.getDataType());

        return ResponseEntity.ok(response);
    }

    // MARK: - DTOs

    public static class AuthorizeThirdPartyRequest {
        private String thirdPartyId;
        private String scope;

        public String getThirdPartyId() {
            return thirdPartyId;
        }

        public void setThirdPartyId(final String thirdPartyId) {
            this.thirdPartyId = thirdPartyId;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(final String scope) {
            this.scope = scope;
        }
    }

    public static class ShareDataRequest {
        private String thirdPartyId;
        private String dataType;

        public String getThirdPartyId() {
            return thirdPartyId;
        }

        public void setThirdPartyId(final String thirdPartyId) {
            this.thirdPartyId = thirdPartyId;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(final String dataType) {
            this.dataType = dataType;
        }
    }
}
