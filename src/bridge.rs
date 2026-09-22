use std::collections::BTreeSet;

use crate::{light_client::{ChainKind, FinalityProof, LightClientRegistry}, Hash32, MetachainError};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BridgeProof {
    pub source: ChainKind,
    pub target: ChainKind,
    pub source_finality: FinalityProof,
    pub asset_id: Hash32,
    pub amount_be: [u8; 32],
    pub recipient: Hash32,
    pub nonce: u64,
    pub event_commitment: Hash32,
}

pub trait BridgeEventVerifier {
    /// Verify source-chain inclusion/semantics of the lock/burn event against the
    /// finalized source state. Finality alone is not sufficient.
    fn verify_event(&self, proof: &BridgeProof) -> bool;
}

#[derive(Default)]
pub struct ReplayGuard {
    consumed: BTreeSet<(ChainKind, Hash32, u64)>,
}

impl ReplayGuard {
    pub fn consume(
        &mut self,
        metachain: ChainKind,
        clients: &LightClientRegistry<'_>,
        event_verifier: &dyn BridgeEventVerifier,
        proof: &BridgeProof,
    ) -> Result<(), MetachainError> {
        if proof.target != metachain {
            return Err(MetachainError::WrongTargetChain);
        }
        if proof.source_finality.chain != proof.source {
            return Err(MetachainError::InvalidSourceFinality);
        }
        let verifier = clients
            .verifier(proof.source)
            .ok_or(MetachainError::InvalidSourceFinality)?;
        if !verifier.verify_finality(&proof.source_finality) || !event_verifier.verify_event(proof) {
            return Err(MetachainError::InvalidSourceFinality);
        }
        let key = (proof.source, proof.event_commitment, proof.nonce);
        if !self.consumed.insert(key) {
            return Err(MetachainError::Replay);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::light_client::LightClientVerifier;

    struct Yes;
    impl LightClientVerifier for Yes {
        fn verify_finality(&self, _: &FinalityProof) -> bool { true }
    }
    impl BridgeEventVerifier for Yes {
        fn verify_event(&self, _: &BridgeProof) -> bool { true }
    }

    fn proof() -> BridgeProof {
        BridgeProof {
            source: ChainKind::Ethereum,
            target: ChainKind::Synergy,
            source_finality: FinalityProof {
                chain: ChainKind::Ethereum,
                height: 1,
                header_hash: [1; 32],
                state_root: [2; 32],
                proof_bytes: vec![3],
            },
            asset_id: [4; 32],
            amount_be: [0; 32],
            recipient: [5; 32],
            nonce: 7,
            event_commitment: [6; 32],
        }
    }

    #[test]
    fn replay_is_rejected() {
        let yes = Yes;
        let clients = LightClientRegistry { bitcoin: None, ethereum: Some(&yes), solana: None, synergy: None };
        let mut guard = ReplayGuard::default();
        let p = proof();
        assert_eq!(guard.consume(ChainKind::Synergy, &clients, &yes, &p), Ok(()));
        assert_eq!(guard.consume(ChainKind::Synergy, &clients, &yes, &p), Err(MetachainError::Replay));
    }
}
