#![forbid(unsafe_code)]

pub mod bridge;
pub mod checkpoint;
pub mod light_client;
pub mod pq;
pub mod providers;
pub mod upstream;

pub type Hash32 = [u8; 32];

pub trait HashProvider {
    fn hash(&self, domain: &[u8], payload: &[u8]) -> Hash32;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MetachainError {
    InvalidPostQuantumAuthorization,
    InvalidSourceFinality,
    Replay,
    WrongTargetChain,
    InvalidCheckpointParent,
    InvalidCheckpointEpoch,
    InvalidUpstreamPin,
}
