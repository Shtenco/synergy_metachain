#[cfg(feature = "experimental-rustcrypto-pq")]
use crate::pq::{AuthScheme, SignatureVerifier};

/// Experimental pure-Rust FIPS 204/205 verifier.
///
/// Security note: the upstream RustCrypto crates are standards-oriented but are
/// not independently audited as of this integration generation. This adapter is
/// therefore feature-gated and MUST NOT be interpreted as a production audit claim.
#[cfg(feature = "experimental-rustcrypto-pq")]
#[derive(Clone, Copy, Debug, Default)]
pub struct RustCryptoPqVerifier;

#[cfg(feature = "experimental-rustcrypto-pq")]
impl SignatureVerifier for RustCryptoPqVerifier {
    fn verify(
        &self,
        scheme: AuthScheme,
        public_key: &[u8],
        message: &[u8],
        signature: &[u8],
    ) -> bool {
        match scheme {
            AuthScheme::MlDsa87 => verify_ml_dsa_87(public_key, message, signature),
            AuthScheme::SlhDsa => verify_slh_dsa_shake_256s(public_key, message, signature),
            // Classical lanes belong in an independently selected compatibility
            // provider. Failing closed prevents an accidental hybrid bypass.
            AuthScheme::Secp256k1 | AuthScheme::Ed25519 => false,
        }
    }
}

#[cfg(feature = "experimental-rustcrypto-pq")]
fn verify_ml_dsa_87(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    use ml_dsa::{KeyInit, MlDsa87, Signature, Verifier, VerifyingKey};

    let Ok(vk) = VerifyingKey::<MlDsa87>::new_from_slice(public_key) else {
        return false;
    };
    let Ok(sig) = Signature::<MlDsa87>::try_from(signature) else {
        return false;
    };
    vk.verify(message, &sig).is_ok()
}

#[cfg(feature = "experimental-rustcrypto-pq")]
fn verify_slh_dsa_shake_256s(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    use slh_dsa::{Shake256s, Signature, VerifyingKey};
    use slh_dsa::signature::Verifier;

    let Ok(vk) = VerifyingKey::<Shake256s>::try_from(public_key) else {
        return false;
    };
    let Ok(sig) = Signature::<Shake256s>::try_from(signature) else {
        return false;
    };
    vk.verify(message, &sig).is_ok()
}
