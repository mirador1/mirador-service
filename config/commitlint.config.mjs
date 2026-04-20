// =============================================================================
// commitlint config — Conventional Commits reference.
//
// NOTE: this file documents the intent. The actual enforcement is a pure-bash
// regex in .config/lefthook.yml (commit-msg → conventional-commits) so we
// don't need to install node_modules/ for a Java project. The two stay in
// sync by convention — if you change this file, mirror the regex in
// .config/lefthook.yml. (lefthook.yml moved from repo root on 2026-04-20
// per root-hygiene rule.)
//
// Run commitlint locally against a past commit (optional, requires npm install):
//   npx @commitlint/cli --from=HEAD~1
//
// Why?
//   - Enables automatic CHANGELOG + semver bump via release-please on `main`.
//   - Makes `git log --oneline` scannable: every line starts with a type.
//   - Aligns with Renovate's semanticCommits preset (renovate.json).
//
// Enforced format:
//   <type>(<optional scope>)!?: <subject>
//
// Accepted types:
//   feat     — new feature
//   fix      — bug fix
//   docs     — docs only
//   style    — formatting, no code change
//   refactor — refactor without feature or fix
//   perf     — performance improvement
//   test     — adds/fixes tests
//   build    — build system or dependency changes (Maven, npm, Docker)
//   ci       — CI config changes (.gitlab-ci.yml, lefthook, Renovate)
//   chore    — housekeeping (bumping deps, renaming files)
//   revert   — revert a previous commit
//
// The optional `!` marks a breaking change.
// Scope: optional. Use to narrow the area (e.g. `feat(customer): ...`).
// =============================================================================

export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat',
        'fix',
        'docs',
        'style',
        'refactor',
        'perf',
        'test',
        'build',
        'ci',
        'chore',
        'revert',
      ],
    ],
    // Subject line max 72 chars. Body lines can be longer.
    'header-max-length': [2, 'always', 72],
    // Subject in lowercase (avoid sentence-case).
    'subject-case': [2, 'always', ['lower-case', 'sentence-case']],
    // Don't end the subject with a period.
    'subject-full-stop': [2, 'never', '.'],
    // Body should be wrapped at 100 chars for good readability in terminals.
    'body-max-line-length': [1, 'always', 100],
  },
};
