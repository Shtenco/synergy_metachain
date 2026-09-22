use crate::MetachainError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthScheme {
    Secp256k1,
    Ed25519,
    MlDsa87,
    SlhDsa,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthPolicy {
    HybridOnline,
    HybridMax,
    PqOnly,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SignatureEvidence {
    pub scheme: AuthScheme,
    pub public_key: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Authorization {
    pub policy: AuthPolicy,
    pub evidence: Vec<SignatureEvidence>,
}

pub trait SignatureVerifier {
    fn verify(
        &self,
        scheme: AuthScheme,
        public_key: &[u8],
        message: &[u8],
        signature: &[u8],
    ) -> bool;
}

impl Authorization {
    pub fn required_schemes(&self) -> &'static [AuthScheme] {
        const ONLINE: &[AuthScheme] = &[AuthScheme::Secp256k1, AuthScheme::MlDsa87];
        const MAX: &[AuthScheme] = &[
            AuthScheme::Secp256k1,
            AuthScheme::MlDsa87,
            AuthScheme::SlhDsa,
        ];
        const PQ_ONLY: &[AuthScheme] = &[AuthScheme::MlDsa87, AuthScheme::SlhDsa];
        match self.policy {
            AuthPolicy::HybridOnline => ONLINE,
            AuthPolicy::HybridMax => MAX,
            AuthPolicy::PqOnly => PQ_ONLY,
        }
    }

    pub fn verify<V: SignatureVerifier>(
        &self,
        verifier: &V,
        message: &[u8],
    ) -> Result<(), MetachainError> {
        let ok = self.required_schemes().iter().all(|required| {
            self.evidence
                .iter()
                .find(|e| &e.scheme == required)
                .map(|e| verifier.verify(e.scheme, &e.public_key, message, &e.signature))
                .unwrap_or(false)
        });
        if ok {
            Ok(())
        } else {
            Err(MetachainError::InvalidPostQuantumAuthorization)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Allow;
    impl SignatureVerifier for Allow {
        fn verify(&self, _: AuthScheme, _: &[u8], _: &[u8], _: &[u8]) -> bool { true }
    }

    #[test]
    fn pq_only_is_fail_closed() {
        let auth = Authorization {
            policy: AuthPolicy::PqOnly,
            evidence: vec![SignatureEvidence {
                scheme: AuthScheme::MlDsa87,
                public_key: vec![1],
                signature: vec![2],
            }],
        };
        assert_eq!(auth.verify(&Allow, b"x"), Err(MetachainError::InvalidPostQuantumAuthorization));
    }
}
