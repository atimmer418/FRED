### Task 15: archive-2025-attempt

Recover the 2025 paycheck detection attempt (two docs and one service class) from git history into `FREDdocs/archive/2025-paycheck-detection/` with a README that says they are reference only. Docs only: no Java is compiled, no route, property or bean changes. Spec anchors: `FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md:25` (section 1.4, last sentence: commit 90a8cc1 deleted the 2025 `PaycheckDetectionService` with no stated reason; `git show 90a8cc1^:<path>` recovers it), `:678` (section 14, Phase 0: "recover the 2025 docs and service from `90a8cc1^`"), `:722` (section 16, Sources: names the three files and notes the commit says only "removed unused files").

Facts you need (all verified on this checkout on 2026-10-20):
- Commit `90a8cc1ae87fa3a2abc692dd7aec229218a28e06` ("removed unused files", 2025-09-17) has parent `b9b37db7d419986fe6a84bc1b87b991057ef13db`; `git show --stat` reports 19 files changed, 2 insertions, 3733 deletions (18 deletions plus one modification of `PlaidService.java`). The three to recover: `PAYCHECK_DETECTION.md` (187 lines, 6599 bytes), `TESTING_PAYCHECK_DETECTION.md` (213 lines, 5851 bytes), `backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java` (282 lines, 12095 bytes). None of the three contains an em dash.
- `FREDdocs/archive/` does not exist yet. `.gitignore` has no rule touching `archive/`, `*.java` or `*.md`, so the new files are trackable. `FREDdocs/` has no index or README file that lists its documents, so nothing else needs an entry.
- `backend/build.gradle` declares no `sourceSets`, `srcDir` or `FREDdocs` path, so a `.java` file under `FREDdocs/` is never compiled by Gradle. The recovered class imports `PaycheckSourceDto`, `UserPaycheckConfig` and `UserPaycheckConfigRepository` (recovered file lines 3, 5 and 6) and autowires `PlaidWebhookService` (lines 37 to 38); all four were deleted in the same commit, and the `PlaidService.getRecurringIncome(String)` it calls at line 113 was removed from `PlaidService.java` in that commit too (the diff also removes `getImmediateIncomeSourcesFromTransactions`, `getIncomeSourceKey`, `getDisplayName`, `estimateFrequency` and `isLikelyNonIncomeTransaction`). It compiles nowhere and must stay outside `backend/src`.
- Spec section 4.4 (`:163`) lists `PaycheckDetectionService.factorFor` as a fourth frequency-factor copy for T03 to replace. The recovered class has no `factorFor` method (its only frequency logic is `frequenciesMatch`, lines 239 to 260), and the archive is never compiled, so T03 has nothing to change here. The README records this so nobody goes looking.
- Shared dirty tree: at review time `git status --short FREDdocs/` showed ` M FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md` and `?? FREDdocs/metrics.md` from other work, and nothing was staged. Those lines may differ when you run; never stage anything outside `FREDdocs/archive/2025-paycheck-detection/`. Never run `git checkout`, `git restore`, `git stash` or `git reset` (not even `git restore --staged`); Step 10 commits by explicit pathspec so other sessions' staged content, if any, is left alone.
- The shell is zsh. Quote every revision argument (`'90a8cc1^:PATH'`) so `^` is never globbed. An em dash is matched with `$'\xe2\x80\x94'` (verified: `printf 'a\xe2\x80\x94b\n' | grep -c $'\xe2\x80\x94'` prints 1).
- `.vscode/settings.json:25-31` (`java.import.exclusions`) does not list `FREDdocs`; VS Code's Java extension may underline the archived class if it is opened in the editor. That is cosmetic. Do not edit `.vscode/settings.json`; it is not part of this task.

**Files:**
- Create: `FREDdocs/archive/2025-paycheck-detection/README.md`
- Create: `FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md` (byte-exact copy of `90a8cc1^:PAYCHECK_DETECTION.md`)
- Create: `FREDdocs/archive/2025-paycheck-detection/TESTING_PAYCHECK_DETECTION.md` (byte-exact copy of `90a8cc1^:TESTING_PAYCHECK_DETECTION.md`)
- Create: `FREDdocs/archive/2025-paycheck-detection/PaycheckDetectionService.java` (byte-exact copy of `90a8cc1^:backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java`)
- Modify: none
- Test: none on disk. This task has no Java or TypeScript test; the verification is the two shell checks below (Steps 2 and 4 for the recovered files, Steps 5 and 7 for the README) plus the no-wiring gate in Step 8, all run from the repo root `/Users/andrewtimmer/PersonalTypeshit/FRED`.

**Interfaces:**
- Consumes: nothing from any other task. Reads git history only: `git show '90a8cc1^:<path>'` for the three paths above.
- Produces: the four files at the paths above. No Java symbol, no property key, no endpoint. No other Phase 0 task reads anything from `FREDdocs/archive/`; `PaycheckDetectionService.java` in the archive is never imported, compiled or referenced from `backend/` or `frontend/`.

- [ ] **Step 1: Confirm the source commit and its parent**

Run:
```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git log -1 --format='%H %P %ad %s' --date=short 90a8cc1
git diff-tree --no-commit-id --name-status -r 90a8cc1 | grep -E 'PAYCHECK_DETECTION|PaycheckDetectionService'
```
Expected (exactly these three deleted paths, the first line showing the full hash, the parent and the date):
```
90a8cc1ae87fa3a2abc692dd7aec229218a28e06 b9b37db7d419986fe6a84bc1b87b991057ef13db 2025-09-17 removed unused files
D	PAYCHECK_DETECTION.md
D	TESTING_PAYCHECK_DETECTION.md
D	backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java
```

- [ ] **Step 2: Write the recovery check and run it to see it fail**

This is the test for the three recovered files: each must exist, be byte-identical to the git object, have the known line count, and contain no em dash. Run it as written (it is a subshell, so the `exit 1` lines never close your terminal):

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
( set -e
  D=FREDdocs/archive/2025-paycheck-detection
  for f in PAYCHECK_DETECTION.md TESTING_PAYCHECK_DETECTION.md PaycheckDetectionService.java; do
    test -f "$D/$f" || { echo "FAIL: $D/$f is missing"; exit 1; }
  done
  cmp <(git show '90a8cc1^:PAYCHECK_DETECTION.md') "$D/PAYCHECK_DETECTION.md" \
    || { echo "FAIL: PAYCHECK_DETECTION.md is not byte-exact"; exit 1; }
  cmp <(git show '90a8cc1^:TESTING_PAYCHECK_DETECTION.md') "$D/TESTING_PAYCHECK_DETECTION.md" \
    || { echo "FAIL: TESTING_PAYCHECK_DETECTION.md is not byte-exact"; exit 1; }
  cmp <(git show '90a8cc1^:backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java') "$D/PaycheckDetectionService.java" \
    || { echo "FAIL: PaycheckDetectionService.java is not byte-exact"; exit 1; }
  test "$(wc -l < "$D/PAYCHECK_DETECTION.md" | tr -d ' ')" -eq 187 || { echo "FAIL: PAYCHECK_DETECTION.md must have 187 lines"; exit 1; }
  test "$(wc -l < "$D/TESTING_PAYCHECK_DETECTION.md" | tr -d ' ')" -eq 213 || { echo "FAIL: TESTING_PAYCHECK_DETECTION.md must have 213 lines"; exit 1; }
  test "$(wc -l < "$D/PaycheckDetectionService.java" | tr -d ' ')" -eq 282 || { echo "FAIL: PaycheckDetectionService.java must have 282 lines"; exit 1; }
  if grep -rq $'\xe2\x80\x94' "$D"; then echo "FAIL: em dash found under $D"; exit 1; fi
  shasum -a 256 "$D/PAYCHECK_DETECTION.md" "$D/TESTING_PAYCHECK_DETECTION.md" "$D/PaycheckDetectionService.java"
  echo "RECOVERY CHECK PASS"
)
```
Expected: the first line printed is `FAIL: FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md is missing` and nothing after it (the directory does not exist yet).

- [ ] **Step 3: Recover the three files byte-for-byte**

Run:
```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
mkdir -p FREDdocs/archive/2025-paycheck-detection
git show '90a8cc1^:PAYCHECK_DETECTION.md' > FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md
git show '90a8cc1^:TESTING_PAYCHECK_DETECTION.md' > FREDdocs/archive/2025-paycheck-detection/TESTING_PAYCHECK_DETECTION.md
git show '90a8cc1^:backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java' > FREDdocs/archive/2025-paycheck-detection/PaycheckDetectionService.java
ls -la FREDdocs/archive/2025-paycheck-detection
```
Expected: three files listed with sizes 6599 (`PAYCHECK_DETECTION.md`), 5851 (`TESTING_PAYCHECK_DETECTION.md`) and 12095 (`PaycheckDetectionService.java`) bytes. Do not edit any of them, ever; corrections go in the README.

- [ ] **Step 4: Run the recovery check to verify it passes**

Run the exact block from Step 2 again.
Expected: three `shasum` lines followed by `RECOVERY CHECK PASS`. The hashes must be:
```
396a3c9b7e5209f15c9486c62c1897189412cde7c4adda9f9f46fbf18a42b01f  FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md
2385e77c19b8832483719d60bd5264b45e0e31bc2161ec86857217d4a871daa7  FREDdocs/archive/2025-paycheck-detection/TESTING_PAYCHECK_DETECTION.md
16810b9809e5e55d55f30c661605ec07eaaf9e1b502e74bc812a0c41bcd9bf16  FREDdocs/archive/2025-paycheck-detection/PaycheckDetectionService.java
```
If any hash differs, delete that one file with `rm` and redo its `git show` line from Step 3 (never `git checkout` or `git restore` anything).

- [ ] **Step 5: Write the README check and run it to see it fail**

This is the test for the README: it must exist, name the commit and parent hashes, name all three recovered files, carry the three SHA-256 values, state that the folder is reference only and that the class must never move under `backend/src`, record the missing `factorFor`, and contain no em dash.

```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
( set -e
  R=FREDdocs/archive/2025-paycheck-detection/README.md
  test -f "$R" || { echo "FAIL: $R is missing"; exit 1; }
  for needle in \
    90a8cc1ae87fa3a2abc692dd7aec229218a28e06 \
    b9b37db7d419986fe6a84bc1b87b991057ef13db \
    PAYCHECK_DETECTION.md \
    TESTING_PAYCHECK_DETECTION.md \
    PaycheckDetectionService.java \
    'reference only' \
    'Never copy it under' \
    'no factorFor method' \
    396a3c9b7e5209f15c9486c62c1897189412cde7c4adda9f9f46fbf18a42b01f \
    2385e77c19b8832483719d60bd5264b45e0e31bc2161ec86857217d4a871daa7 \
    16810b9809e5e55d55f30c661605ec07eaaf9e1b502e74bc812a0c41bcd9bf16; do
    grep -qF -- "$needle" "$R" || { echo "FAIL: README lacks: $needle"; exit 1; }
  done
  if grep -q $'\xe2\x80\x94' "$R"; then echo "FAIL: em dash in README"; exit 1; fi
  echo "README CHECK PASS"
)
```
Expected: `FAIL: FREDdocs/archive/2025-paycheck-detection/README.md is missing`.

- [ ] **Step 6: Write the README**

Create `FREDdocs/archive/2025-paycheck-detection/README.md` with exactly this content (plain language, no em dashes; every line number below was read from the recovered files on this checkout):

```markdown
# 2025 paycheck detection attempt (archived, reference only)

The three files next to this README are byte-for-byte copies recovered from git history. They are kept so the 2026 Paycheck Autopilot work (`FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md`, sections 1.4 and 14, Phase 0) can refer to what was tried before. Nothing in this folder is wired into the build, the app or any docs index. Treat it as reference only.

## Where the files come from

Commit `90a8cc1ae87fa3a2abc692dd7aec229218a28e06` (2025-09-17) deleted 18 files and trimmed `PlaidService.java`; its message, "removed unused files", gives no further reason. Its parent is `b9b37db7d419986fe6a84bc1b87b991057ef13db`. Each file here was recovered with `git show '90a8cc1^:<original path>' > FREDdocs/archive/2025-paycheck-detection/<file>` (quote the revision; the shell is zsh).

| File here | Original path | Lines | Bytes | SHA-256 |
|---|---|---|---|---|
| `PAYCHECK_DETECTION.md` | `PAYCHECK_DETECTION.md` | 187 | 6599 | `396a3c9b7e5209f15c9486c62c1897189412cde7c4adda9f9f46fbf18a42b01f` |
| `TESTING_PAYCHECK_DETECTION.md` | `TESTING_PAYCHECK_DETECTION.md` | 213 | 5851 | `2385e77c19b8832483719d60bd5264b45e0e31bc2161ec86857217d4a871daa7` |
| `PaycheckDetectionService.java` | `backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java` | 282 | 12095 | `16810b9809e5e55d55f30c661605ec07eaaf9e1b502e74bc812a0c41bcd9bf16` |

Do not edit the three recovered files. If something in them needs a correction or a note, write it in this README. They predate the house rule against em dashes but contain none, so they can stay verbatim.

## Why the Java file does not compile, and why it must stay here

`PaycheckDetectionService.java` imports `PaycheckSourceDto`, `UserPaycheckConfig` and `UserPaycheckConfigRepository` (lines 3, 5 and 6) and autowires `PlaidWebhookService` (lines 37 to 38). All four classes were deleted in the same commit. It also calls `PlaidService.getRecurringIncome(String)` (line 113), which that commit removed from `PlaidService` together with `getImmediateIncomeSourcesFromTransactions` and four private helpers. The class therefore compiles nowhere.

It lives under `FREDdocs/`, outside every Gradle source set (`backend/build.gradle` declares no extra source directories), so `./gradlew compileJava` never sees it. Keep it here. Never copy it under `backend/src`. VS Code's Java extension may underline it if you open it in the editor; that is cosmetic and expected.

One correction to the 2026 spec: section 4.4 names `PaycheckDetectionService.factorFor` as a fourth frequency-factor copy to fold into the `InvestmentFrequency` enum. The recovered class has no factorFor method. Its only frequency logic is `frequenciesMatch` (lines 239 to 260), which compares two frequency strings and never holds a monthly factor. The enum work touches only the three live readers.

## What the 2025 attempt did

- The user typed paycheck details on the survey page (name, employer name, frequency, expected amount and an optional account id). Each entry was a `UserPaycheckConfig` row (see `PAYCHECK_DETECTION.md`, Key Components).
- A daily job, `detectPaychecksDaily` (lines 44 to 67), annotated `@Scheduled(cron = "0 0 2 * * ?")` with no zone (line 44), loaded every config not yet detected (line 50) and handed each one to `processPaycheckDetection` (lines 97 to 145), which, for users with a Plaid access token, decrypted the token and called `PlaidService.getRecurringIncome` (lines 112 to 113).
- `isPaycheckMatch` (lines 171 to 234) scored a Plaid recurring stream against the config on up to four criteria: frequency (with BIWEEKLY / BI_WEEKLY and SEMI_MONTHLY / SEMIMONTHLY aliases in `frequenciesMatch`, lines 239 to 260), amount within 20 percent of the typed amount, employer name contained in the stream name or the reverse, and an exact account id. Two matches were enough when three or more criteria applied; one match when fewer.
- On a match it set `paycheckDetected` and `paycheckDetectedAt` and stored the Plaid account id in `plaidStreamId` (the code comment at line 130 says it used the account id as the stream identifier). `setupWebhookForPaycheck` (lines 265 to 281) only set `webhookConfigured` and `webhookConfiguredAt`; the autowired `plaidWebhookService` (line 38) is never called anywhere in the class.
- Investment triggering was listed as future work (`PAYCHECK_DETECTION.md`, Overview item 5 and Future Enhancements) and was not part of this class.
- `TESTING_PAYCHECK_DETECTION.md` opens by saying a JVM version issue prevented the branch from compiling at the time, then lists the curl steps that were meant to exercise it through the dev auth endpoint (`/api/dev/authenticate-as-user` on the dev host).

## How the 2026 design differs

The 2026 spec asks no paycheck questions: after the bank link it reads Plaid Transactions plus the Recurring Transactions add-on, finds the primary paycheck stream with zero questions, and asks only for one "Looks good" tap and one authenticated "Authorize" tap (spec 1.2). It trusts a stream only after two on-cadence posted deposits, triggers only on a posted deposit matched to a trusted stream (never a prediction or a pending row), maps cadence onto the single `InvestmentFrequency` enum, and records a Reg E authorization. See spec sections 1.2, 4 and 7. Nothing in this folder is a source of truth for that work.

## Other files deleted in the same commit (not recovered here)

Run `git show '90a8cc1^:<path>'` if you need one of them:

- `backend/src/main/java/com/investingapp/backend/controller/IncomeController.java`
- `backend/src/main/java/com/investingapp/backend/controller/PaycheckDetectionController.java`
- `backend/src/main/java/com/investingapp/backend/controller/WebhookController.java`
- `backend/src/main/java/com/investingapp/backend/dto/PaycheckDetectionStatusDto.java`
- `backend/src/main/java/com/investingapp/backend/dto/PaycheckSourceDto.java`
- `backend/src/main/java/com/investingapp/backend/dto/SelectedPaycheckDto.java`
- `backend/src/main/java/com/investingapp/backend/model/UserPaycheckConfig.java`
- `backend/src/main/java/com/investingapp/backend/repository/UserPaycheckConfigRepository.java`
- `backend/src/main/java/com/investingapp/backend/service/PlaidWebhookService.java`
- `backend/src/main/java/com/investingapp/backend/service/UserFinancialConfigService.java`
- `backend/test_paycheck_detection.sh`

The same commit also deleted four unrelated frontend `*.old.*` files (three under `frontend/src/app/components/survey/`, one under `frontend/src/app/components/investment-schedule/`). The recurring-income readers removed from `PlaidService` are visible with `git show 90a8cc1 -- backend/src/main/java/com/investingapp/backend/service/PlaidService.java`.
```

- [ ] **Step 7: Run the README check to verify it passes**

Run the exact block from Step 5 again.
Expected: `README CHECK PASS`. If it prints `FAIL: README lacks: <needle>`, the README text drifted from Step 6; fix that line and rerun.

- [ ] **Step 8: Prove there is no code wiring**

Run:
```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
grep -rn "PaycheckDetectionService\|UserPaycheckConfig\|getRecurringIncome" backend/src frontend/src ; echo "tree grep exit $? (1 means no hits)"
grep -n "sourceSets\|srcDir\|FREDdocs" backend/build.gradle ; echo "gradle grep exit $? (1 means no hits)"
cd backend && ./gradlew compileJava -q ; echo "gradle exit $?"
```
Expected: the first grep prints nothing and then `tree grep exit 1`; the second grep prints nothing and then `gradle grep exit 1`; Gradle prints nothing at all (verified on 2026-10-20: zero bytes of output) and then `gradle exit 0`. This proves the archived class is outside every source set and nothing in either tree references it. The two greps are the gate for this task. If the compile fails, read the error paths: an error in any file under `backend/src` belongs to another session's work in progress on this shared tree, not to this task (the archive adds nothing under `backend/`); report it and continue, do not touch those files.

- [ ] **Step 9: Stage exactly the four new files and inspect the index**

Run:
```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git status --short FREDdocs/
git add FREDdocs/archive/2025-paycheck-detection/README.md \
        FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md \
        FREDdocs/archive/2025-paycheck-detection/TESTING_PAYCHECK_DETECTION.md \
        FREDdocs/archive/2025-paycheck-detection/PaycheckDetectionService.java
git diff --cached --name-only
git diff --cached --stat -- FREDdocs/archive/2025-paycheck-detection
```
Expected: the first `git status` shows `?? FREDdocs/archive/` alongside any other sessions' lines (at review time ` M FREDdocs/specs/2026-10-19-paycheck-autopilot-design.md` and `?? FREDdocs/metrics.md`); those other lines stay unstaged. `git diff --cached --name-only` normally lists exactly the four archive paths. If it lists any other path, another session staged it; leave it alone (do not run `git restore --staged`, `git reset` or anything that touches the index for that path), because Step 10 commits by pathspec and will not include it. The `--stat` line shows 187, 213 and 282 insertions for the three recovered files plus the README's line count, ending with `4 files changed`.

- [ ] **Step 10: Commit**

Run (the pathspec after `--` makes `git commit` use its default `--only` mode: it commits these four paths and disregards anything staged for other paths):
```bash
cd /Users/andrewtimmer/PersonalTypeshit/FRED
git commit -m "docs(archive): recover the 2025 paycheck detection attempt for reference" -- \
        FREDdocs/archive/2025-paycheck-detection/README.md \
        FREDdocs/archive/2025-paycheck-detection/PAYCHECK_DETECTION.md \
        FREDdocs/archive/2025-paycheck-detection/TESTING_PAYCHECK_DETECTION.md \
        FREDdocs/archive/2025-paycheck-detection/PaycheckDetectionService.java
git show --stat --format='%h %s' HEAD
```
Expected: the commit lands on `develop`, and `git show --stat` lists only the four files under `FREDdocs/archive/2025-paycheck-detection/` with `4 files changed`. The executing session appends its own attribution trailer; do not add one here.