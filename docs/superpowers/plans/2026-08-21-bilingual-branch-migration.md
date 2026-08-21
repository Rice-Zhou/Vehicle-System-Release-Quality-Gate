# VSRQG Bilingual Branch Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a Chinese documentation baseline on `main` and a semantically equivalent English documentation baseline on `release`, while preserving V0.1 semantics, V0.2 Draft status, repository history, and verifiable cross-branch parity.

**Architecture:** Build the Chinese branch from the approved V0.2 Draft so `main` contains V0.1 and V0.2. Add repository-level language rules and one branch-comparison script shared byte-for-byte by both branches. Create `release` from the verified Chinese commit, translate Markdown only, then validate paths, non-Markdown blobs, language, links, fences, and human semantic anchors before publishing.

**Tech Stack:** Git branches and annotated history; Markdown/Mermaid; PowerShell 7; GitHub HTTPS remote.

---

## File map

**Create on both branches:**

- `AGENTS.md` — repository rules, frozen architecture reminders, language policy, and mandatory next-step reporting.
- `docs/language-policy.md` — branch roles, terminology, synchronization, discrepancy handling, and acceptance.
- `scripts/verify-language-branches.ps1` — deterministic cross-branch structural and language checks.

**Translate/normalize on `main`:**

- `README.md`, `CHANGELOG.md`
- `docs/00-architecture-freeze.md`
- `docs/13-v0.1-to-v0.2-architecture-evolution.md`
- `docs/project-constitution.md`, `docs/core-contract.md`, `docs/system-architecture.md`
- `docs/roadmap.md`, `docs/ai-development-guide.md`
- `docs/adr/*.md`
- `docs/v0.2/*.md`, `docs/v0.2/tdr/*.md`
- `docs/superpowers/specs/*.md`, `docs/superpowers/plans/*.md`

**Translate on `release`:** the identical Markdown path set, using English only.

**Must remain byte-identical:** `.gitattributes`, `schemas/**`, `scripts/verify-language-branches.ps1`, `test`, and all future non-Markdown files.

### Task 1: Commit the approved migration plan

**Files:**

- Create: `docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md`

- [ ] **Step 1: Validate the plan document**

Run:

```powershell
$redFlags = @('T' + 'BD', 'T' + 'ODO', 'FIX' + 'ME', 'X' + 'XX')
$planText = Get-Content -Raw docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md
if ($redFlags | Where-Object { $planText -match "\b$_\b" }) { exit 1 }
git diff --check
```

Expected: `rg` finds no placeholders; `git diff --check` exits 0.

- [ ] **Step 2: Commit and push the plan**

```powershell
git add -- docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md
git commit -m "docs: plan bilingual branch migration"
git push origin HEAD:docs/v0.2-implementation-architecture
```

Expected: one documentation-only commit; remote Draft branch SHA equals local HEAD.

### Task 2: Create the Chinese migration branch and repository rules

**Files:**

- Create: `AGENTS.md`
- Create: `docs/language-policy.md`
- Create: `scripts/verify-language-branches.ps1`

- [ ] **Step 1: Create a feature branch from the approved Draft**

```powershell
git switch -c docs/main-chinese
```

Expected: branch starts at the plan commit; worktree is clean.

- [ ] **Step 2: Write repository instructions**

`AGENTS.md` must include this enforceable reporting contract:

```markdown
## 修改完成后的下一步执行计划

任何项目修改完成后，最终报告必须包含：当前结果、Git 状态、下一步动作、前置条件、验收目标。下一步计划不扩大当前授权；需要 Owner 批准时必须明确等待。
```

It must also state that V0.1 frozen concepts cannot change without ADR and that explanatory prose on `main` is Chinese while `release` is English.

- [ ] **Step 3: Write `docs/language-policy.md`**

Copy the approved behavior from `docs/superpowers/specs/2026-08-21-bilingual-branch-governance-design.md` in concise operational form. Include branch roles, protected technical tokens, `TRANSLATION_DISCREPANCY`, paired review, no force push, and V0.2 Draft preservation.

- [ ] **Step 4: Implement the verifier**

Create `scripts/verify-language-branches.ps1` with this complete implementation:

```powershell
[CmdletBinding()]
param(
    [string]$ChineseRef = 'main',
    [string]$EnglishRef = 'release',
    [ValidateSet('Pair', 'ChineseOnly', 'EnglishOnly')]
    [string]$Mode = 'Pair'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-GitLines {
    param([string[]]$Arguments)
    $output = @(& git @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Test-GitRef {
    param([string]$Ref)
    & git rev-parse --verify --quiet "$Ref^{commit}" *> $null
    return $LASTEXITCODE -eq 0
}

function Get-RefPaths {
    param([string]$Ref)
    return @(Invoke-GitLines -Arguments @('ls-tree', '-r', '--name-only', $Ref))
}

function Get-RefText {
    param([string]$Ref, [string]$Path)
    return (Invoke-GitLines -Arguments @('show', "${Ref}:$Path")) -join "`n"
}

function Resolve-RepoPath {
    param([string]$SourcePath, [string]$Target)
    $targetPath = $Target.Trim('<', '>').Split('#')[0].Replace('\', '/')
    if (-not $targetPath) { return $null }
    $parts = [System.Collections.Generic.List[string]]::new()
    if (-not $targetPath.StartsWith('/')) {
        $sourceDirectory = [System.IO.Path]::GetDirectoryName($SourcePath).Replace('\', '/')
        if ($sourceDirectory) {
            foreach ($part in $sourceDirectory.Split('/')) { $parts.Add($part) }
        }
    }
    foreach ($part in $targetPath.TrimStart('/').Split('/')) {
        if (-not $part -or $part -eq '.') { continue }
        if ($part -eq '..') {
            if ($parts.Count -eq 0) { return $null }
            $parts.RemoveAt($parts.Count - 1)
        } else {
            $parts.Add($part)
        }
    }
    return $parts -join '/'
}

function Get-InlineTokens {
    param([string]$Text)
    return @([regex]::Matches($Text, '`([^`\r\n]+)`') | ForEach-Object {
        $_.Groups[1].Value
    } | Sort-Object)
}

function Get-HeadingShape {
    param([string]$Text)
    return @([regex]::Matches($Text, '(?m)^(#{1,6})\s+') | ForEach-Object {
        $_.Groups[1].Value.Length
    })
}

function Test-MarkdownRef {
    param(
        [string]$Ref,
        [ValidateSet('Chinese', 'English')]
        [string]$Language,
        [string[]]$Paths
    )
    $pathSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$Paths)
    foreach ($path in $Paths | Where-Object { $_.EndsWith('.md') }) {
        $text = Get-RefText -Ref $Ref -Path $path
        $fenceCount = ([regex]::Matches($text, '(?m)^```')).Count
        if (($fenceCount % 2) -ne 0) { $failures.Add("$Ref unbalanced fence: $path") }
        if ($Language -eq 'English' -and [regex]::IsMatch($text, '[\u4e00-\u9fff]')) {
            $failures.Add("$Ref contains CJK: $path")
        }
        if ($Language -eq 'Chinese') {
            $prose = [regex]::Replace($text, '(?ms)^```.*?^```\s*$', '')
            if (-not [regex]::IsMatch($prose, '[\u4e00-\u9fff]')) {
                $failures.Add("$Ref lacks Chinese prose: $path")
            }
        }
        foreach ($match in [regex]::Matches($text, '\[[^\]]+\]\(([^)]+)\)')) {
            $target = $match.Groups[1].Value
            if ($target -match '^(https?://|mailto:|#)') { continue }
            $resolved = Resolve-RepoPath -SourcePath $path -Target $target
            if (-not $resolved -or -not $pathSet.Contains($resolved)) {
                $failures.Add("$Ref broken link: $path -> $target")
            }
        }
    }
}

if ($Mode -in @('Pair', 'ChineseOnly') -and -not (Test-GitRef -Ref $ChineseRef)) {
    Write-Error "missing ref: $ChineseRef"
    exit 1
}
if ($Mode -in @('Pair', 'EnglishOnly') -and -not (Test-GitRef -Ref $EnglishRef)) {
    Write-Error "missing ref: $EnglishRef"
    exit 1
}

if ($Mode -eq 'ChineseOnly') {
    $paths = Get-RefPaths -Ref $ChineseRef
    Test-MarkdownRef -Ref $ChineseRef -Language Chinese -Paths $paths
} elseif ($Mode -eq 'EnglishOnly') {
    $paths = Get-RefPaths -Ref $EnglishRef
    Test-MarkdownRef -Ref $EnglishRef -Language English -Paths $paths
} else {
    $chinesePaths = Get-RefPaths -Ref $ChineseRef
    $englishPaths = Get-RefPaths -Ref $EnglishRef
    $pathDiff = Compare-Object $chinesePaths $englishPaths
    foreach ($difference in $pathDiff) {
        $failures.Add("path mismatch $($difference.SideIndicator): $($difference.InputObject)")
    }
    Test-MarkdownRef -Ref $ChineseRef -Language Chinese -Paths $chinesePaths
    Test-MarkdownRef -Ref $EnglishRef -Language English -Paths $englishPaths
    foreach ($path in $chinesePaths | Where-Object { -not $_.EndsWith('.md') }) {
        if ($englishPaths -notcontains $path) { continue }
        $zhBlob = (Invoke-GitLines -Arguments @('rev-parse', "${ChineseRef}:$path"))[0]
        $enBlob = (Invoke-GitLines -Arguments @('rev-parse', "${EnglishRef}:$path"))[0]
        if ($zhBlob -ne $enBlob) { $failures.Add("non-Markdown mismatch: $path") }
    }
    foreach ($path in $chinesePaths | Where-Object { $_.EndsWith('.md') }) {
        if ($englishPaths -notcontains $path) { continue }
        $zhText = Get-RefText -Ref $ChineseRef -Path $path
        $enText = Get-RefText -Ref $EnglishRef -Path $path
        $zhHeadings = (Get-HeadingShape -Text $zhText) -join ','
        $enHeadings = (Get-HeadingShape -Text $enText) -join ','
        if ($zhHeadings -ne $enHeadings) { $failures.Add("heading shape mismatch: $path") }
        $zhTokens = (Get-InlineTokens -Text $zhText) -join "`n"
        $enTokens = (Get-InlineTokens -Text $enText) -join "`n"
        if ($zhTokens -ne $enTokens) { $failures.Add("inline token mismatch: $path") }
    }
}

if ($failures.Count -gt 0) {
    $failures | Sort-Object -Unique | ForEach-Object { Write-Error $_ }
    exit 1
}
Write-Output "PASS mode=$Mode chinese=$ChineseRef english=$EnglishRef"
```

No check may silently skip an unreadable file or missing ref.

- [ ] **Step 5: Verify the script fails before `release` exists**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef HEAD -EnglishRef release
```

Expected: non-zero exit with `missing ref: release`.

- [ ] **Step 6: Commit governance infrastructure**

```powershell
git add -- AGENTS.md docs/language-policy.md scripts/verify-language-branches.ps1
git commit -m "docs: establish bilingual repository governance"
```

Expected: only the three listed files are committed.

### Task 3: Translate the V0.1 baseline on `main`

**Files:**

- Modify: `README.md`, `CHANGELOG.md`
- Modify: `docs/00-architecture-freeze.md`
- Modify: `docs/13-v0.1-to-v0.2-architecture-evolution.md`
- Modify: `docs/project-constitution.md`, `docs/core-contract.md`, `docs/system-architecture.md`
- Modify: `docs/roadmap.md`, `docs/ai-development-guide.md`
- Modify: `docs/adr/ADR-000-template.md`, `docs/adr/ADR-001-core-architecture.md`

- [ ] **Step 1: Translate explanatory prose to Chinese**

Preserve file paths, heading hierarchy, numbered requirements, diagrams, identifiers, enum values, API/schema fields, dates, versions, and normative strength. Translate `must not` as a prohibition, never as a recommendation. Do not edit `schemas/release-manifest.schema.json`.

- [ ] **Step 2: Check V0.1 semantic anchors**

```powershell
rg -n "Release|Manifest|Evidence|Traceability|Quality Engine|Adapter|Plugin|ADR|Fixed|Included|Verified" README.md docs/00-architecture-freeze.md docs/core-contract.md docs/system-architecture.md docs/13-v0.1-to-v0.2-architecture-evolution.md
git diff -- schemas/release-manifest.schema.json
```

Expected: all frozen terms remain present; schema diff is empty.

- [ ] **Step 3: Commit V0.1 translation**

```powershell
git add -- README.md CHANGELOG.md docs/00-architecture-freeze.md docs/13-v0.1-to-v0.2-architecture-evolution.md docs/project-constitution.md docs/core-contract.md docs/system-architecture.md docs/roadmap.md docs/ai-development-guide.md docs/adr/ADR-000-template.md docs/adr/ADR-001-core-architecture.md
git commit -m "docs(zh): translate frozen V0.1 documentation"
```

Expected: no schema, script, or unrelated path is staged.

### Task 4: Normalize V0.2 and governance documents to Chinese

**Files:**

- Modify: `docs/v0.2/*.md`, `docs/v0.2/tdr/*.md`
- Modify: `docs/superpowers/specs/*.md`, `docs/superpowers/plans/*.md`

- [ ] **Step 1: Translate remaining natural-language English**

Translate titles, prose, table descriptions, diagram labels, and example explanations. Preserve API paths, JSON/YAML keys, table/column names, enum/status values, Rule IDs, branch names, tags, code, filenames, and product names.

- [ ] **Step 2: Run Chinese branch checks**

```powershell
$files = git ls-files '*.md'
foreach ($file in $files) {
  $text = Get-Content -Raw -LiteralPath $file
  if (-not [regex]::IsMatch($text, '[\u4e00-\u9fff]')) { throw "Chinese prose missing: $file" }
}
git diff --check
```

Expected: every Markdown file contains Chinese prose; diff check exits 0.

- [ ] **Step 3: Commit V0.2 normalization**

```powershell
git add -- docs/v0.2 docs/superpowers/specs docs/superpowers/plans
git commit -m "docs(zh): normalize V0.2 and governance documents"
```

Expected: one focused Markdown-only commit.

### Task 5: Validate and publish Chinese `main`

- [ ] **Step 1: Verify history and frozen architecture**

```powershell
git diff --check origin/main..HEAD
git diff --name-only origin/main..HEAD -- schemas
git log --oneline --decorate origin/main..HEAD
```

Expected: diff check passes; schema diff is empty; commits are plan, governance, V0.1 translation, and V0.2 normalization.

- [ ] **Step 2: Verify Chinese language, repository links, and fences**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -Mode ChineseOnly -ChineseRef HEAD
```

Expected: PASS; no missing Chinese prose, broken link, or unbalanced fence.

- [ ] **Step 3: Push feature branch, then fast-forward `main`**

```powershell
git push -u origin HEAD:docs/main-chinese
git push origin HEAD:main
git fetch origin main
git diff --exit-code HEAD origin/main
```

Expected: both pushes succeed without force; final diff exits 0; existing `test` remains.

### Task 6: Create and translate the English `release` branch

- [ ] **Step 1: Create `release` from verified Chinese `main`**

```powershell
git switch -c release origin/main
```

Expected: `release` starts from the exact verified `main` commit.

- [ ] **Step 2: Translate every Markdown file to English**

Use the same paths and section structure. Preserve all protected technical tokens and normative meaning. Translate `AGENTS.md`, language policy, V0.1, V0.2, ADR/TDR, specs, and plans. Do not modify non-Markdown files.

- [ ] **Step 3: Prove the English tree contains no CJK**

```powershell
$violations = @()
git ls-files '*.md' | ForEach-Object {
  if ([regex]::IsMatch((Get-Content -Raw -LiteralPath $_), '[\u4e00-\u9fff]')) { $violations += $_ }
}
if ($violations) { $violations; exit 1 }
```

Expected: zero violating files.

- [ ] **Step 4: Commit English mirror**

```powershell
git add -- AGENTS.md CHANGELOG.md README.md docs
git commit -m "docs(en): publish English documentation mirror"
```

Expected: only Markdown files differ from `main`.

### Task 7: Cross-branch verification and release publication

- [ ] **Step 1: Run automated branch parity checks**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -ChineseRef origin/main -EnglishRef HEAD
```

Expected: PASS for path parity, non-Markdown blob equality, language, links, fences, heading structure, and inline technical tokens.

- [ ] **Step 2: Complete human semantic review**

Review every paired file for: MUST/MUST NOT, authority, Fixed/Included/Verified, UNKNOWN/error semantics, PK/FK/cardinality, states, permissions, timeout/retry/recovery, rule thresholds/units, MVP/V0.3 boundaries, and ADR/TDR alternatives.

Expected: no `TRANSLATION_DISCREPANCY`; review result PASSED.

- [ ] **Step 3: Push `release` and verify remote refs**

```powershell
git push -u origin release
git fetch origin main release
pwsh -File scripts/verify-language-branches.ps1 -ChineseRef origin/main -EnglishRef origin/release
git status --short
```

Expected: verifier passes against remote refs and worktree is clean.

- [ ] **Step 4: Do not create Design Freeze tags**

V0.2 remains `0.2.0-draft.1`. Paired `-zh`/`-en` tags are created only after the separate V0.2 Architecture Review approves Design Freeze.

## Completion report

The final report must include:

```text
当前结果：main 中文与 release 英文的远端状态
Git 状态：两个远端 SHA、分支、提交和未提交状态
下一步动作：Owner 双语语义抽查或 V0.2 Architecture Review
前置条件：明确列出
验收目标：远端 verifier PASS 与人工评审 PASSED
```
