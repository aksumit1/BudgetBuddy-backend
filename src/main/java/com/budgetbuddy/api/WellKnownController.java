package com.budgetbuddy.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for serving well-known files required by external services
 * 
 * Handles:
 * - Apple App Site Association file for Universal Links
 * - Other well-known endpoints as needed
 */
@RestController
@RequestMapping("/.well-known")
public class WellKnownController {

    private static final Logger logger = LoggerFactory.getLogger(WellKnownController.class);

    @Value("${app.apple.team-id:TEAM_ID}")
    private String appleTeamId;

    @Value("${app.apple.bundle-id:com.budgetbuddy.app}")
    private String bundleId;

    /**
     * Serve Apple App Site Association file for Universal Links
     * 
     * This file is required for iOS Universal Links to work with Plaid OAuth redirects.
     * Must be served with Content-Type: application/json
     * Must be accessible at: https://domain/.well-known/apple-app-site-association
     * 
     * @return Apple App Site Association JSON
     */
    @GetMapping(value = "/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAppleAppSiteAssociation() {
        logger.debug("Serving Apple App Site Association file for Team ID: {}, Bundle ID: {}", appleTeamId, bundleId);

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> applinks = new HashMap<>();
        Map<String, Object> component = new HashMap<>();

        // Configure path matching for Plaid OAuth redirects
        component.put("/", "/plaid/*");
        component.put("comment", "Matches Plaid OAuth redirect paths starting with /plaid/");

        Map<String, Object> detail = new HashMap<>();
        detail.put("appIDs", new String[]{appleTeamId + "." + bundleId});
        detail.put("components", new Object[]{component});

        applinks.put("details", new Object[]{detail});
        response.put("applinks", applinks);

        // Set correct Content-Type header (required by Apple)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        logger.info("Apple App Site Association file served successfully");
        return ResponseEntity.ok()
                .headers(headers)
                .body(response);
    }
}

