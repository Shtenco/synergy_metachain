use crate::Hash32;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ChainKind {
    Bitcoin,
    Ethereum,
    Solana,
    Synergy,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FinalityProof {
    pub chain: ChainKind,
    pub height: u64,
    pub header_hash: Hash32,
    pub state_root: Hash32,
    pub proof_bytes: Vec<u8>,
}

pub trait LightClientVerifier {
    /// Must fail closed. Implementations verify the source chain's actual consensus/finality rules.
    fn verify_finality(&self, proof: &FinalityProof) -> bool;
}

/// Explicit capability registry: a chain is bridge-enabled only after a concrete
/// verifier is installed. There is no "accept opaque proof" fallback.
pub struct LightClientRegistry<'a> {
    pub bitcoin: Option<&'a dyn LightClientVerifier>,
    pub ethereum: Option<&'a dyn LightClientVerifier>,
    pub solana: Option<&'a dyn LightClientVerifier>,
    pub synergy: Option<&'a dyn LightClientVerifier>,
}

impl<'a> LightClientRegistry<'a> {
    pub fn verifier(&self, chain: ChainKind) -> Option<&'a dyn LightClientVerifier> {
        match chain {
            ChainKind::Bitcoin => self.bitcoin,
            ChainKind::Ethereum => self.ethereum,
            ChainKind::Solana => self.solana,
            ChainKind::Synergy => self.synergy,
        }
    }
}
