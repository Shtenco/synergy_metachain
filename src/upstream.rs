/// Pinned upstream lineage. These are provenance pins, not runtime authority.
pub const PQ_EVM_REPO: &str = "Shtenco/synergy_ai_blockchain";
pub const PQ_EVM_BRANCH: &str = "feat/pq-evm-nexus-v1";
pub const PQ_EVM_COMMIT: &str = "01dbc0fa901c8195bc37c646fa6617ada2b97a99";

pub const ECONOMIC_REPO: &str = "Shtenco/synergy_ai_blockchain";
pub const ECONOMIC_BRANCH: &str = "feat/economic-testnet-persistent-restart-evidence-v1-20260828";
pub const ECONOMIC_COMMIT: &str = "72a7853fba49d20c208bd2d9776d6c107648b882";
pub const ECONOMIC_EXECUTION_EVIDENCE_ROOT: &str =
    "7c533e6ebb3b3177ef9191d1951c930ddd9d6f49541b0fa3982924f2dce86680";

pub const ECONOMIC_CLAIM: &str =
    "LOCAL_DISK_PERSISTENCE_RESTART_RECOVERY_VERIFIED_NOT_EXTERNAL_HOST_TESTNET";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct UpstreamPin {
    pub repo: &'static str,
    pub branch: &'static str,
    pub commit: &'static str,
}

pub const PQ_PIN: UpstreamPin = UpstreamPin {
    repo: PQ_EVM_REPO,
    branch: PQ_EVM_BRANCH,
    commit: PQ_EVM_COMMIT,
};

pub const ECONOMIC_PIN: UpstreamPin = UpstreamPin {
    repo: ECONOMIC_REPO,
    branch: ECONOMIC_BRANCH,
    commit: ECONOMIC_COMMIT,
};
