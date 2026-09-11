# LEGACY DISTILLATION V2 — Import Queue

Canonical owner: `Shtenco/synergy_metachain`

Nothing in this queue is auto-promoted to production. Every mechanism must pass the listed gate in this repository authority boundary.

| Priority | ID | Family | Action | Occurrences | Test gate | Representative |
|---|---|---|---|---:|---|---|
| P0_REWRITE | M022 | BRIDGE_CROSSCHAIN | REWRITE_REQUIRED | 140 | `lock_burn_mint_conservation` | onlyBridge |
| P0_REWRITE | M023 | BRIDGE_CROSSCHAIN | REWRITE_REQUIRED | 134 | `lock_burn_mint_conservation` | receiveBridgeFee |
| P1_PROMOTE_WITH_GATE | M024 | BRIDGE_CROSSCHAIN | PROMOTION_CANDIDATE | 36 | `lock_burn_mint_conservation` | BridgeTx |
| P1_PROMOTE_WITH_GATE | M025 | BRIDGE_CROSSCHAIN | PROMOTION_CANDIDATE | 24 | `lock_burn_mint_conservation` | queueEmergencyWithdraw |
| P1_PROMOTE_WITH_GATE | M026 | BRIDGE_CROSSCHAIN | PROMOTION_CANDIDATE | 20 | `lock_burn_mint_conservation` | constructor |
| P1_PROMOTE_WITH_GATE | M027 | BRIDGE_CROSSCHAIN | PROMOTION_CANDIDATE | 13 | `lock_burn_mint_conservation` | updateCheckpoint |
| P1_PROMOTE_WITH_GATE | M028 | BRIDGE_CROSSCHAIN | PROMOTION_CANDIDATE | 4 | `lock_burn_mint_conservation` | _recover |
