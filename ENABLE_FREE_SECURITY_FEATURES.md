# Enable Free Security Features

This guide helps you enable free GitHub Advanced Security features and Snyk (free tier).

## 🔐 GitHub Advanced Security (Free Tier)

### For Public Repositories (100% Free)

Public repositories get **free access** to GitHub Advanced Security features:

1. **Dependency graph** - ✅ Free for public repos
2. **Dependabot alerts** - ✅ Free for public repos
3. **Dependabot security updates** - ✅ Free for public repos
4. **Code scanning (CodeQL)** - ✅ Free for public repos
5. **Secret scanning** - ✅ Free for public repos

### How to Enable (Step-by-Step)

#### Option 1: Via GitHub Web UI (Recommended)

1. **Go to Repository Settings**
   - Navigate to your repository on GitHub
   - Click **Settings** tab
   - Click **Security** in the left sidebar
   - Click **Code security and analysis**

2. **Enable Dependency Graph**
   - Find "Dependency graph" section
   - Click **Enable** (if not already enabled)
   - This enables dependency tracking and Dependabot

3. **Enable Dependabot Alerts**
   - Find "Dependabot alerts" section
   - Click **Enable** (if not already enabled)
   - This will alert you to vulnerable dependencies

4. **Enable Dependabot Security Updates**
   - Find "Dependabot security updates" section
   - Click **Enable** (if not already enabled)
   - This automatically creates PRs for security updates

5. **Enable Code Scanning (CodeQL)**
   - Find "Code scanning" section
   - Click **Set up** or **Enable**
   - Select "Set up with CodeQL" or "Set up this workflow"
   - The workflow file (`.github/workflows/codeql-analysis.yml`) is already created
   - GitHub will automatically detect and use it

6. **Enable Secret Scanning**
   - Find "Secret scanning" section
   - Click **Enable** (if not already enabled)
   - This scans for accidentally committed secrets

#### Option 2: Via GitHub CLI (Automated)

Run these commands in your terminal:

```bash
# Install GitHub CLI if not already installed
# macOS: brew install gh
# Linux: See https://cli.github.com/manual/installation

# Authenticate (if not already)
gh auth login

# Enable Dependency Graph
gh api repos/:owner/:repo/vulnerability-alerts -X PUT

# Enable Dependabot Security Updates
gh api repos/:owner/:repo/automated-security-fixes -X PUT

# Enable Secret Scanning
gh api repos/:owner/:repo/secret-scanning -X PUT

# Enable Code Scanning (CodeQL)
gh api repos/:owner/:repo/code-scanning -X PUT
```

**Note**: Replace `:owner/:repo` with your actual repository (e.g., `yourusername/BudgetBuddy-Backend`)

### Verification

After enabling, you should see:
- ✅ Green checkmarks in Settings > Security > Code security and analysis
- ✅ "Security" tab appears in repository navigation
- ✅ Dependabot alerts appear in Security tab
- ✅ CodeQL analysis runs automatically

---

## 🛡️ Snyk (Free Tier)

### Free Tier Features

Snyk offers a **free tier** with:
- ✅ Unlimited tests per month
- ✅ Unlimited projects
- ✅ Vulnerability scanning
- ✅ License compliance scanning
- ✅ Container scanning
- ✅ Open source dependency scanning

### How to Enable (Step-by-Step)

#### Step 1: Sign Up for Snyk (Free)

1. Go to [https://snyk.io/signup](https://snyk.io/signup)
2. Sign up with your GitHub account (recommended) or email
3. Verify your email if required
4. Complete the onboarding process

#### Step 2: Get Your Snyk API Token

1. Log in to [Snyk Dashboard](https://app.snyk.io)
2. Click on your profile icon (top right)
3. Select **Account Settings**
4. Go to **General** tab
5. Find **API Token** section
6. Click **Show** or **Copy** to get your token
7. **Save this token** - you'll need it for GitHub

#### Step 3: Add Token to GitHub Secrets

1. Go to your GitHub repository
2. Click **Settings** > **Secrets and variables** > **Actions**
3. Click **New repository secret**
4. Name: `SNYK_TOKEN`
5. Value: Paste your Snyk API token
6. Click **Add secret**

#### Step 4: Verify Snyk Integration

After adding the token:

1. **Trigger a workflow run** (push a commit or manually trigger)
2. Check the workflow logs for Snyk steps
3. You should see Snyk scanning your dependencies
4. Results will appear in workflow artifacts

### Alternative: Snyk GitHub Integration

You can also integrate Snyk directly with GitHub:

1. In Snyk Dashboard, go to **Integrations**
2. Click **GitHub** integration
3. Authorize Snyk to access your repositories
4. Select repositories to scan
5. Snyk will automatically create PRs for vulnerable dependencies

**Note**: The GitHub Actions integration (using `SNYK_TOKEN`) is already configured in your workflows and works independently.

---

## ✅ Verification Checklist

After completing the setup, verify:

### GitHub Advanced Security
- [ ] Dependency graph enabled
- [ ] Dependabot alerts enabled
- [ ] Dependabot security updates enabled
- [ ] Code scanning (CodeQL) enabled
- [ ] Secret scanning enabled
- [ ] Security tab visible in repository

### Snyk
- [ ] Snyk account created
- [ ] API token obtained
- [ ] `SNYK_TOKEN` secret added to GitHub
- [ ] Snyk workflow runs successfully
- [ ] Snyk results visible in workflow artifacts

---

## 📊 What You Get

### GitHub Advanced Security (Free for Public Repos)
- **Dependency Graph**: Visual representation of all dependencies
- **Dependabot Alerts**: Automatic alerts for vulnerable dependencies
- **Dependabot Security Updates**: Auto-PRs for security patches
- **CodeQL**: Advanced code analysis for security vulnerabilities
- **Secret Scanning**: Detects accidentally committed secrets

### Snyk (Free Tier)
- **Vulnerability Scanning**: Comprehensive dependency vulnerability detection
- **License Compliance**: License scanning and compliance reports
- **Container Scanning**: Docker image security scanning
- **Priority Scoring**: CVSS-based vulnerability prioritization
- **Fix Suggestions**: Automated fix recommendations

---

## 🔗 Useful Links

- [GitHub Advanced Security Documentation](https://docs.github.com/en/code-security)
- [Snyk Free Tier](https://snyk.io/pricing/)
- [Snyk Documentation](https://docs.snyk.io/)
- [CodeQL Documentation](https://codeql.github.com/docs/)

---

## ❓ Troubleshooting

### GitHub Advanced Security Not Available?

**For Private Repositories:**
- GitHub Advanced Security requires GitHub Enterprise
- Consider making the repository public (if appropriate)
- Or upgrade to GitHub Enterprise Cloud

**For Public Repositories:**
- All features should be available for free
- If not visible, check repository settings
- Ensure you have admin access

### Snyk Token Not Working?

1. Verify token is correct (no extra spaces)
2. Check token hasn't expired
3. Ensure token has correct permissions
4. Check workflow logs for specific error messages
5. Re-generate token if needed

### CodeQL Not Running?

1. Check `.github/workflows/codeql-analysis.yml` exists
2. Verify workflow is enabled in repository settings
3. Check Actions tab for workflow runs
4. Ensure CodeQL is enabled in Security settings

---

## 📝 Notes

- **Public Repos**: All GitHub Advanced Security features are free
- **Private Repos**: Requires GitHub Enterprise (paid)
- **Snyk**: Free tier is generous and sufficient for most projects
- **Both Tools**: Work together to provide comprehensive security coverage

