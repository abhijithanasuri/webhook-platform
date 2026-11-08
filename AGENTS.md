# Project working preferences

- The daily runbook is the user's SOP: docs/13-daily-sop.md and the existing Notion page https://app.notion.com/p/4106baeb2fb0810882efc8544cbeda98.
- Update the SOP whenever setup/launcher commands change and at the end of each working session/day when wrapping up. Keep startup order, prerequisites, expected results, test selection, shutdown, and known issues current. Verify against scripts/dev.cmd. Consolidate obsolete instructions; keep history in implementation documents.
- Keep the Webhook Platform Notion home and affected documents current incrementally. Distinguish implemented, verified, planned, and unverified work; do not stack contradictory status summaries.
- Local docs are authoritative, with explicit reconciliation to Notion. Preserve user edits and the original transfer snapshot in outputs/webhook-platform.
- Never expose .env, demo passwords, signing keys, or access tokens. LocalStack persistence and AWS parity are unverified unless fresh evidence establishes otherwise.
