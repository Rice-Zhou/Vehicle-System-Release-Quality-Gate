# VSRQG 双语分支迁移实施计划

> **面向执行 Agent：** 必须使用 `superpowers:executing-plans`，逐项实施本计划。步骤使用 checkbox（`- [ ]`）跟踪。

**目标：** 在 `main` 发布中文文档基线，在 `release` 发布语义等价的英文文档基线，同时保留 V0.1 语义、V0.2 Draft 状态、仓库历史及可验证的跨分支一致性。

**架构：** 从已批准的 V0.2 Draft 构建中文分支，使 `main` 同时包含 V0.1 和 V0.2。增加仓库级语言规则，以及一份由两分支逐字节共享的分支比较脚本。从验证后的中文提交创建 `release`，仅翻译 Markdown；发布前验证路径、非 Markdown blob、语言、链接、code fence 和人工语义锚点。

**技术栈：** Git Branch 与 annotated history；Markdown/Mermaid；PowerShell 7；GitHub HTTPS Remote。

---

## 文件映射

**在两个分支上创建：**

- `AGENTS.md`——仓库规则、冻结架构提醒、语言策略和强制下一步报告。
- `docs/language-policy.md`——分支职责、术语、同步、差异处理和验收。
- `scripts/verify-language-branches.ps1`——确定性的跨分支结构与语言检查。

**在 `main` 上翻译/规范化：**

- `README.md`, `CHANGELOG.md`
- `docs/00-architecture-freeze.md`
- `docs/13-v0.1-to-v0.2-architecture-evolution.md`
- `docs/project-constitution.md`, `docs/core-contract.md`, `docs/system-architecture.md`
- `docs/roadmap.md`, `docs/ai-development-guide.md`
- `docs/adr/*.md`
- `docs/v0.2/*.md`, `docs/v0.2/tdr/*.md`
- `docs/superpowers/specs/*.md`, `docs/superpowers/plans/*.md`

**在 `release` 上翻译：** 使用完全相同的 Markdown 路径集合，且只使用英文。

**必须逐字节一致：** `.gitattributes`、`schemas/**`、`scripts/verify-language-branches.ps1`、`test` 以及未来所有非 Markdown 文件。

### Task 1：提交已批准的迁移计划

**文件：**

- 创建：`docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md`

- [ ] **Step 1：验证计划文档**

运行：

```powershell
$redFlags = @('T' + 'BD', 'T' + 'ODO', 'FIX' + 'ME', 'X' + 'XX')
$planText = Get-Content -Raw docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md
if ($redFlags | Where-Object { $planText -match "\b$_\b" }) { exit 1 }
git diff --check
```

预期：`rg` 找不到 placeholder；`git diff --check` 以 0 退出。

- [ ] **Step 2：提交并推送计划**

```powershell
git add -- docs/superpowers/plans/2026-08-21-bilingual-branch-migration.md
git commit -m "docs: plan bilingual branch migration"
git push origin HEAD:docs/v0.2-implementation-architecture
```

预期：只有一个文档提交；远端 Draft 分支 SHA 等于本地 HEAD。

### Task 2：创建中文迁移分支和仓库规则

**文件：**

- 创建：`AGENTS.md`
- 创建：`docs/language-policy.md`
- 创建：`scripts/verify-language-branches.ps1`

- [ ] **Step 1：从已批准的 Draft 创建 Feature Branch**

```powershell
git switch -c docs/main-chinese
```

预期：分支从计划提交开始；Worktree 干净。

- [ ] **Step 2：编写仓库指令**

`AGENTS.md` 必须包含以下可执行的报告 Contract：

```markdown
## 修改完成后的下一步执行计划

任何项目修改完成后，最终报告必须包含：当前结果、Git 状态、下一步动作、前置条件、验收目标。下一步计划不扩大当前授权；需要 Owner 批准时必须明确等待。
```

还必须声明：未经 ADR 不得修改 V0.1 冻结概念；`main` 的说明性正文使用中文，`release` 使用英文。

- [ ] **Step 3：编写 `docs/language-policy.md`**

以简洁、可操作的形式落实 `docs/superpowers/specs/2026-08-21-bilingual-branch-governance-design.md` 中已批准的行为。包括分支职责、受保护技术 Token、`TRANSLATION_DISCREPANCY`、配对评审、禁止 force push 和保持 V0.2 Draft。

- [ ] **Step 4：实现校验器**

使用以下完整实现创建 `scripts/verify-language-branches.ps1`：

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

任何检查都不得静默跳过无法读取的文件或缺失的 Ref。

- [ ] **Step 5：验证脚本在 `release` 存在前明确失败**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -Mode Pair -ChineseRef HEAD -EnglishRef release
```

预期：非零退出，并报告 `missing ref: release`。

- [ ] **Step 6：提交治理基础设施**

```powershell
git add -- AGENTS.md docs/language-policy.md scripts/verify-language-branches.ps1
git commit -m "docs: establish bilingual repository governance"
```

预期：仅提交列出的三个文件。

### Task 3：翻译 `main` 上的 V0.1 基线

**文件：**

- 修改：`README.md`、`CHANGELOG.md`
- 修改：`docs/00-architecture-freeze.md`
- 修改：`docs/13-v0.1-to-v0.2-architecture-evolution.md`
- 修改：`docs/project-constitution.md`、`docs/core-contract.md`、`docs/system-architecture.md`
- 修改：`docs/roadmap.md`、`docs/ai-development-guide.md`
- 修改：`docs/adr/ADR-000-template.md`、`docs/adr/ADR-001-core-architecture.md`

- [ ] **Step 1：将说明性正文翻译为中文**

保留文件路径、标题层级、编号要求、Diagram、Identifier、Enum Value、API/Schema Field、日期、版本和规范强度。`must not` 必须译为禁止，绝不能译为建议。不得编辑 `schemas/release-manifest.schema.json`。

- [ ] **Step 2：检查 V0.1 语义锚点**

```powershell
rg -n "Release|Manifest|Evidence|Traceability|Quality Engine|Adapter|Plugin|ADR|Fixed|Included|Verified" README.md docs/00-architecture-freeze.md docs/core-contract.md docs/system-architecture.md docs/13-v0.1-to-v0.2-architecture-evolution.md
git diff -- schemas/release-manifest.schema.json
```

预期：所有冻结术语仍然存在；Schema Diff 为空。

- [ ] **Step 3：提交 V0.1 翻译**

```powershell
git add -- README.md CHANGELOG.md docs/00-architecture-freeze.md docs/13-v0.1-to-v0.2-architecture-evolution.md docs/project-constitution.md docs/core-contract.md docs/system-architecture.md docs/roadmap.md docs/ai-development-guide.md docs/adr/ADR-000-template.md docs/adr/ADR-001-core-architecture.md
git commit -m "docs(zh): translate frozen V0.1 documentation"
```

预期：没有 Schema、Script 或无关路径被暂存。

### Task 4：将 V0.2 和治理文档规范化为中文

**文件：**

- 修改：`docs/v0.2/*.md`、`docs/v0.2/tdr/*.md`
- 修改：`docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`

- [ ] **Step 1：翻译剩余的自然语言英文**

翻译标题、正文、Table Description、Diagram Label 和示例说明。保留 API Path、JSON/YAML Key、Table/Column Name、Enum/Status Value、Rule ID、Branch Name、Tag、Code、Filename 和 Product Name。

- [ ] **Step 2：运行中文分支检查**

```powershell
$files = git ls-files '*.md'
foreach ($file in $files) {
  $text = Get-Content -Raw -LiteralPath $file
  if (-not [regex]::IsMatch($text, '[\u4e00-\u9fff]')) { throw "Chinese prose missing: $file" }
}
git diff --check
```

预期：每个 Markdown 文件都包含中文正文；Diff Check 以 0 退出。

- [ ] **Step 3：提交 V0.2 规范化**

```powershell
git add -- docs/v0.2 docs/superpowers/specs docs/superpowers/plans
git commit -m "docs(zh): normalize V0.2 and governance documents"
```

预期：形成一个范围明确且只包含 Markdown 的提交。

### Task 5：验证并发布中文 `main`

- [ ] **Step 1：验证历史与冻结架构**

```powershell
git diff --check origin/main..HEAD
git diff --name-only origin/main..HEAD -- schemas
git log --oneline --decorate origin/main..HEAD
```

预期：Diff Check 通过；Schema Diff 为空；提交依次为计划、治理、V0.1 翻译与 V0.2 规范化。

- [ ] **Step 2：验证中文语言、仓库链接和 Code Fence**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -Mode ChineseOnly -ChineseRef HEAD
```

预期：PASS；不存在中文正文缺失、Broken Link 或 Unbalanced Fence。

- [ ] **Step 3：推送 Feature Branch，然后 Fast-forward `main`**

```powershell
git push -u origin HEAD:docs/main-chinese
git push origin HEAD:main
git fetch origin main
git diff --exit-code HEAD origin/main
```

预期：两次推送都在不 force 的情况下成功；最终 Diff 以 0 退出；现有 `test` 保留。

### Task 6：创建并翻译英文 `release` 分支

- [ ] **Step 1：从验证后的中文 `main` 创建 `release`**

```powershell
git switch -c release origin/main
```

预期：`release` 从已验证的 `main` 确切提交开始。

- [ ] **Step 2：将每个 Markdown 文件翻译为英文**

使用相同路径与章节结构。保留所有受保护技术 Token 和规范性含义。翻译 `AGENTS.md`、Language Policy、V0.1、V0.2、ADR/TDR、Spec 和 Plan。不得修改非 Markdown 文件。

- [ ] **Step 3：证明英文 Tree 不含 CJK**

```powershell
$violations = @()
git ls-files '*.md' | ForEach-Object {
  if ([regex]::IsMatch((Get-Content -Raw -LiteralPath $_), '[\u4e00-\u9fff]')) { $violations += $_ }
}
if ($violations) { $violations; exit 1 }
```

预期：违规文件为零。

- [ ] **Step 4：提交英文镜像**

```powershell
git add -- AGENTS.md CHANGELOG.md README.md docs
git commit -m "docs(en): publish English documentation mirror"
```

预期：与 `main` 相比只有 Markdown 文件存在差异。

### Task 7：跨分支验证并发布 release

- [ ] **Step 1：运行自动化分支一致性检查**

```powershell
pwsh -File scripts/verify-language-branches.ps1 -ChineseRef origin/main -EnglishRef HEAD
```

预期：路径一致性、非 Markdown blob 相等、语言、链接、Fence、标题结构和 Inline Technical Token 全部 PASS。

- [ ] **Step 2：完成人工语义评审**

逐对评审文件中的 MUST/MUST NOT、权威关系、Fixed/Included/Verified、UNKNOWN/Error Semantics、PK/FK/Cardinality、State、Permission、Timeout/Retry/Recovery、Rule Threshold/Unit、MVP/V0.3 Boundary 和 ADR/TDR Alternative。

预期：不存在 `TRANSLATION_DISCREPANCY`；评审结果为 PASSED。

- [ ] **Step 3：推送 `release` 并验证远端 Ref**

```powershell
git push -u origin release
git fetch origin main release
pwsh -File scripts/verify-language-branches.ps1 -ChineseRef origin/main -EnglishRef origin/release
git status --short
```

预期：校验器针对远端 Ref 通过，且 Worktree 干净。

- [ ] **Step 4：不得创建 Design Freeze Tag**

V0.2 保持 `0.2.0-draft.1`。仅在独立的 V0.2 Architecture Review 批准 Design Freeze 后，才创建配对的 `-zh`/`-en` Tag。

## 完成报告

最终报告必须包含：

```text
当前结果：main 中文与 release 英文的远端状态
Git 状态：两个远端 SHA、分支、提交和未提交状态
下一步动作：Owner 双语语义抽查或 V0.2 Architecture Review
前置条件：明确列出
验收目标：远端 verifier PASS 与人工评审 PASSED
```
