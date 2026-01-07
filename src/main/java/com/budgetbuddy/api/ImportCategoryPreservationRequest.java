package com.budgetbuddy.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for preserving categories from preview during import
 * Used to avoid re-categorizing transactions when account hasn't changed
 */
public class ImportCategoryPreservationRequest {
    
    /**
     * Preview categories keyed by transaction index (0-based)
     * Each entry contains the categories that were assigned during preview
     */
    @JsonProperty("previewCategories")
    private List<PreviewCategory> previewCategories;
    
    /**
     * Account ID that was used during preview
     * If this matches the import accountId, categories will be preserved
     */
    @JsonProperty("previewAccountId")
    private String previewAccountId;
    
    public static class PreviewCategory {
        @JsonProperty("categoryPrimary")
        private String categoryPrimary;
        
        @JsonProperty("categoryDetailed")
        private String categoryDetailed;
        
        @JsonProperty("importerCategoryPrimary")
        private String importerCategoryPrimary;
        
        @JsonProperty("importerCategoryDetailed")
        private String importerCategoryDetailed;
        
        // Getters and setters
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getImporterCategoryPrimary() { return importerCategoryPrimary; }
        public void setImporterCategoryPrimary(String importerCategoryPrimary) { this.importerCategoryPrimary = importerCategoryPrimary; }
        
        public String getImporterCategoryDetailed() { return importerCategoryDetailed; }
        public void setImporterCategoryDetailed(String importerCategoryDetailed) { this.importerCategoryDetailed = importerCategoryDetailed; }
    }
    
    // Getters and setters
    public List<PreviewCategory> getPreviewCategories() { return previewCategories; }
    public void setPreviewCategories(List<PreviewCategory> previewCategories) { this.previewCategories = previewCategories; }
    
    public String getPreviewAccountId() { return previewAccountId; }
    public void setPreviewAccountId(String previewAccountId) { this.previewAccountId = previewAccountId; }
}

