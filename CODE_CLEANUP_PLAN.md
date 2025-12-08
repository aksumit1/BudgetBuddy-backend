# Code Cleanup Plan

## Issues Found

### 1. Deprecated Code to Remove
- ✅ SecurityService: 3 deprecated client salt methods
- ✅ PlaidSyncService: @Deprecated syncTransactionsForAccount method
- ✅ LogConfig.swift: Legacy shim file (empty)
- ✅ SaltIssueTests: Deprecated tests
- ✅ AppViewModel: Deprecated loadPersistedData() method
- ✅ AuthService: Deprecated PIN methods (3 methods)
- ✅ AuthService: Deprecated deleteMyData() method
- ✅ ComplianceController: Deprecated endpoint

### 2. Backward Compatibility Code
- Mixed-case ID handling in repositories (normalizedId fallback)
- TransactionTable.getCategory() for backward compatibility

### 3. Placeholders
- DataArchivingService.archiveOldTransactions() - placeholder (just logs)
- HIPAAComplianceService.checkPHIAccessPolicy() - returns true placeholder
- UnifiedFinancialDataService - placeholder investment holdings

### 4. Badly Structured Code
- Review large classes for modularity issues

