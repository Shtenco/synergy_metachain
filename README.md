# SYNERGY Post-Quantum Metachain

`synergy_metachain` is the integration layer that federates the existing SYNERGY PQ-EVM, Economic OS evidence chain, NEXUS compute plane and external-chain bridges into one checkpointed metachain state.

## What exists in bootstrap v1

- fail-closed post-quantum authorization policies: `HybridOnline`, `HybridMax`, `PqOnly`;
- explicit signature-verifier provider boundary for ML-DSA-87 and SLH-DSA;
- BTC / ETH / SOL / SYNERGY light-client capability registry with no opaque-proof fallback;
- bridge proof pipeline requiring both source finality and source lock/burn-event verification;
- replay protection keyed by source chain + event commitment + nonce;
- parent-linked metachain checkpoints binding PQ, bridge, Economic OS, NEXUS and data-availability roots;
- exact upstream provenance pins for the PQ-EVM branch and the terminal local Economic testnet persistence/restart evidence branch.

## Upstream pins

PQ-EVM/NEXUS substrate:

- repo: `Shtenco/synergy_ai_blockchain`
- branch: `feat/pq-evm-nexus-v1`
- commit: `01dbc0fa901c8195bc37c646fa6617ada2b97a99`

Economic testnet evidence lineage:

- branch: `feat/economic-testnet-persistent-restart-evidence-v1-20260828`
- commit: `72a7853fba49d20c208bd2d9776d6c107648b882`
- execution evidence root: `7c533e6ebb3b3177ef9191d1951c930ddd9d6f49541b0fa3982924f2dce86680`
- exact current claim: `LOCAL_DISK_PERSISTENCE_RESTART_RECOVERY_VERIFIED_NOT_EXTERNAL_HOST_TESTNET`

## Security boundary

Bootstrap v1 deliberately does **not** pretend that provider traits are production cryptography or that an opaque bridge blob is a proof.

Production activation remains fail-closed until all of the following exist and pass reproducible tests:

1. standards-conformant ML-DSA-87 (FIPS 204) implementation;
2. standards-conformant SLH-DSA (FIPS 205) implementation;
3. ML-KEM-1024 transport/rekey provider where encrypted peer transport requires it;
4. bit-exact cross-client vectors for transaction/precompile/checkpoint encoding;
5. concrete Bitcoin header/PoW light client with reorg-depth policy;
6. concrete Ethereum consensus/finality light client;
7. concrete Solana commitment/finality verifier;
8. source-chain event inclusion verifiers for bridge lock/burn semantics;
9. persistent validator network with external-host evidence;
10. actual EVM-client integration (`reth`/`revm` or equivalent) for PQ precompiles and transaction admission;
11. independent security audit before mainnet value is accepted.

## Architecture

```text
BTC light client -----\
ETH light client ------> bridge verifier --\
SOL light client -----/                    \
                                           +--> MetachainCheckpoint(epoch N)
PQ-EVM auth/precompile --------------------/        |
Economic OS checkpoint -------------------/         v
NEXUS deterministic compute root --------/    epoch N+1 parent
Data availability root -----------------/
```

The metachain checkpoint is a bounded commitment layer. Bulk external-chain history, AI model data and payment-provider receipts do not become serial consensus state.

## Immediate next implementation generation

`bootstrap v2` should replace capability boundaries one at a time with executable adapters, starting with a real FIPS-204 ML-DSA-87 verifier and deterministic test vectors, then SLH-DSA, then an Ethereum light-client adapter. No adapter is marked production-ready merely because it compiles.

## Test

```bash
cargo test --all-targets
```
