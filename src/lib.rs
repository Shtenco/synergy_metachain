use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum FinalityStatus {
    PENDING,
    FINAL,
    REORGED,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ChainCommitmentReceipt {
    pub close_id: String,
    pub chain_id: String,
    pub block_height: u64,
    pub tx_hash: String,
    pub commitment_hash: String,
    pub finality_status: FinalityStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum VerificationMethod {
    LIGHT_CLIENT,
    FINALITY_PROOF,
    BRIDGE_PROOF,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum VerificationStatus {
    VERIFIED,
    REJECTED,
    PENDING,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MetaChainCheckpointProof {
    pub source_chain_id: String,
    pub source_block_height: u64,
    pub source_commitment_hash: String,
    pub verification_method: VerificationMethod,
    pub verification_status: VerificationStatus,
    pub proof_ref: String,
    pub security_assumptions: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VerificationError {
    SourceNotFinal,
    InvalidCommitmentHash,
    MissingProofReference,
}

fn is_sha256_hex(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

pub fn verify_checkpoint(
    receipt: &ChainCommitmentReceipt,
    verification_method: VerificationMethod,
    proof_ref: impl Into<String>,
    security_assumptions: Vec<String>,
) -> Result<MetaChainCheckpointProof, VerificationError> {
    if receipt.finality_status != FinalityStatus::FINAL {
        return Err(VerificationError::SourceNotFinal);
    }
    if !is_sha256_hex(&receipt.commitment_hash) {
        return Err(VerificationError::InvalidCommitmentHash);
    }
    let proof_ref = proof_ref.into();
    if proof_ref.is_empty() {
        return Err(VerificationError::MissingProofReference);
    }
    Ok(MetaChainCheckpointProof {
        source_chain_id: receipt.chain_id.clone(),
        source_block_height: receipt.block_height,
        source_commitment_hash: receipt.commitment_hash.clone(),
        verification_method,
        verification_status: VerificationStatus::VERIFIED,
        proof_ref,
        security_assumptions,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn final_receipt() -> ChainCommitmentReceipt {
        ChainCommitmentReceipt {
            close_id: "close-1".into(),
            chain_id: "synergy-testnet".into(),
            block_height: 42,
            tx_hash: "0xabc".into(),
            commitment_hash: "a".repeat(64),
            finality_status: FinalityStatus::FINAL,
        }
    }

    #[test]
    fn verifies_only_final_source_commitments() {
        let proof = verify_checkpoint(
            &final_receipt(),
            VerificationMethod::LIGHT_CLIENT,
            "proof:42",
            vec!["testnet-finality".into()],
        )
        .unwrap();
        assert_eq!(proof.source_commitment_hash, "a".repeat(64));
        assert_eq!(proof.verification_status, VerificationStatus::VERIFIED);
    }

    #[test]
    fn pending_source_is_rejected() {
        let mut receipt = final_receipt();
        receipt.finality_status = FinalityStatus::PENDING;
        assert_eq!(
            verify_checkpoint(&receipt, VerificationMethod::LIGHT_CLIENT, "proof", vec![]),
            Err(VerificationError::SourceNotFinal)
        );
    }
}
