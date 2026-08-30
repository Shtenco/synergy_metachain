use crate::{Hash32, HashProvider, MetachainError};

pub const CHECKPOINT_DOMAIN: &[u8] = b"SYNERGY-METACHAIN-CHECKPOINT/1";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MetachainCheckpoint {
    pub epoch: u64,
    pub parent: Hash32,
    pub pq_state_root: Hash32,
    pub bridge_state_root: Hash32,
    pub economic_state_root: Hash32,
    pub nexus_state_root: Hash32,
    pub data_availability_root: Hash32,
}

impl MetachainCheckpoint {
    pub fn canonical_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(8 + 32 * 6 + CHECKPOINT_DOMAIN.len());
        out.extend_from_slice(CHECKPOINT_DOMAIN);
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&self.parent);
        out.extend_from_slice(&self.pq_state_root);
        out.extend_from_slice(&self.bridge_state_root);
        out.extend_from_slice(&self.economic_state_root);
        out.extend_from_slice(&self.nexus_state_root);
        out.extend_from_slice(&self.data_availability_root);
        out
    }

    pub fn digest<H: HashProvider>(&self, hasher: &H) -> Hash32 {
        hasher.hash(CHECKPOINT_DOMAIN, &self.canonical_bytes())
    }

    pub fn verify_extension<H: HashProvider>(
        &self,
        prior: &MetachainCheckpoint,
        hasher: &H,
    ) -> Result<(), MetachainError> {
        if self.epoch != prior.epoch + 1 {
            return Err(MetachainError::InvalidCheckpointEpoch);
        }
        if self.parent != prior.digest(hasher) {
            return Err(MetachainError::InvalidCheckpointParent);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestHash;
    impl HashProvider for TestHash {
        fn hash(&self, domain: &[u8], payload: &[u8]) -> Hash32 {
            let mut out = [0u8; 32];
            for (i, b) in domain.iter().chain(payload).enumerate() {
                out[i % 32] = out[i % 32].wrapping_add(*b).rotate_left((i % 7) as u32);
            }
            out
        }
    }

    fn cp(epoch: u64, parent: Hash32) -> MetachainCheckpoint {
        MetachainCheckpoint {
            epoch,
            parent,
            pq_state_root: [1; 32],
            bridge_state_root: [2; 32],
            economic_state_root: [3; 32],
            nexus_state_root: [4; 32],
            data_availability_root: [5; 32],
        }
    }

    #[test]
    fn extension_binds_exact_parent() {
        let h = TestHash;
        let first = cp(1, [0; 32]);
        let second = cp(2, first.digest(&h));
        assert_eq!(second.verify_extension(&first, &h), Ok(()));
        let bad = cp(2, [9; 32]);
        assert_eq!(bad.verify_extension(&first, &h), Err(MetachainError::InvalidCheckpointParent));
    }
}
