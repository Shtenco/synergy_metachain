package ai.sinergy.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("deprecation")
final class PqCrypto {
    private static final String PREF = "sinergy_pq_identity_v2";
    private static final MLKEMParameters KEM_PARAMS = MLKEMParameters.ml_kem_1024;
    private static final MLDSAParameters SIG_PARAMS = MLDSAParameters.ml_dsa_87;
    private final SharedPreferences prefs;
    private final StrongBoxVault vault;
    private final SecureRandom random = new SecureRandom();

    PqCrypto(Context context, StrongBoxVault vault) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.vault = vault;
    }

    synchronized JSONObject ensureIdentity() throws Exception {
        if (!prefs.contains("kem_pub") || !prefs.contains("kem_priv") || !prefs.contains("sig_pub") || !prefs.contains("sig_priv")) {
            generateIdentity();
        }
        boolean kemPass = kemSelfTest();
        boolean sigPass = signatureSelfTest();
        JSONObject o = publicBundle();
        o.put("mlKemSelfTest", kemPass);
        o.put("mlDsaSelfTest", sigPass);
        o.put("suite", "ML-KEM-1024 + ML-DSA-87 + AES-256-GCM + SHA-512");
        return o;
    }

    private void generateIdentity() throws Exception {
        MLKEMKeyPairGenerator kemGen = new MLKEMKeyPairGenerator();
        kemGen.init(new MLKEMKeyGenerationParameters(random, KEM_PARAMS));
        AsymmetricCipherKeyPair kemPair = kemGen.generateKeyPair();
        MLKEMPublicKeyParameters kemPub = (MLKEMPublicKeyParameters) kemPair.getPublic();
        MLKEMPrivateKeyParameters kemPriv = (MLKEMPrivateKeyParameters) kemPair.getPrivate();

        MLDSAKeyPairGenerator sigGen = new MLDSAKeyPairGenerator();
        sigGen.init(new MLDSAKeyGenerationParameters(random, SIG_PARAMS));
        AsymmetricCipherKeyPair sigPair = sigGen.generateKeyPair();
        MLDSAPublicKeyParameters sigPub = (MLDSAPublicKeyParameters) sigPair.getPublic();
        MLDSAPrivateKeyParameters sigPriv = (MLDSAPrivateKeyParameters) sigPair.getPrivate();

        prefs.edit()
                .putString("kem_pub", b64(kemPub.getEncoded()))
                .putString("kem_priv", vault.sealBytes(kemPriv.getEncoded(), "PQ-KEM-PRIVATE-v2"))
                .putString("sig_pub", b64(sigPub.getEncoded()))
                .putString("sig_priv", vault.sealBytes(sigPriv.getEncoded(), "PQ-SIG-PRIVATE-v2"))
                .apply();
    }

    JSONObject publicBundle() throws Exception {
        String kemPub = prefs.getString("kem_pub", "");
        String sigPub = prefs.getString("sig_pub", "");
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update("SINERGY-PQ-ID-v2".getBytes(StandardCharsets.UTF_8));
        md.update(Base64.decode(kemPub, Base64.NO_WRAP));
        md.update(Base64.decode(sigPub, Base64.NO_WRAP));
        JSONObject o = new JSONObject();
        o.put("kem", "ML-KEM-1024");
        o.put("signature", "ML-DSA-87");
        o.put("kemPublic", kemPub);
        o.put("signaturePublic", sigPub);
        o.put("fingerprint", hex(md.digest()));
        return o;
    }

    synchronized String sealForPeer(String peerKemPublicB64, String plaintext) throws Exception {
        ensureIdentity();
        MLKEMPublicKeyParameters peerPub = new MLKEMPublicKeyParameters(KEM_PARAMS, Base64.decode(peerKemPublicB64, Base64.NO_WRAP));
        MLKEMGenerator gen = new MLKEMGenerator(random);
        SecretWithEncapsulation swe = gen.generateEncapsulated(peerPub);
        byte[] shared = swe.getSecret();
        byte[] kemCt = swe.getEncapsulation();
        byte[] aesKey = kdf(shared, kemCt);
        Arrays.fill(shared, (byte) 0);

        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD("SINERGY-SECURE-MESSAGE-v2".getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        Arrays.fill(aesKey, (byte) 0);

        byte[] signed = concat(kemCt, iv, ct);
        byte[] sig = sign(signed);
        JSONObject pub = publicBundle();
        JSONObject env = new JSONObject();
        env.put("v", 2);
        env.put("suite", "ML-KEM-1024/ML-DSA-87/AES-256-GCM");
        env.put("kemCt", b64(kemCt));
        env.put("iv", b64(iv));
        env.put("ciphertext", b64(ct));
        env.put("signature", b64(sig));
        env.put("senderSignaturePublic", pub.getString("signaturePublic"));
        env.put("senderFingerprint", pub.getString("fingerprint"));
        return env.toString();
    }

    synchronized String openEnvelope(String envelopeJson) throws Exception {
        ensureIdentity();
        JSONObject env = new JSONObject(envelopeJson);
        byte[] kemCt = Base64.decode(env.getString("kemCt"), Base64.NO_WRAP);
        byte[] iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP);
        byte[] ct = Base64.decode(env.getString("ciphertext"), Base64.NO_WRAP);
        byte[] sig = Base64.decode(env.getString("signature"), Base64.NO_WRAP);
        byte[] senderPub = Base64.decode(env.getString("senderSignaturePublic"), Base64.NO_WRAP);
        byte[] signed = concat(kemCt, iv, ct);
        if (!verify(signed, sig, senderPub)) throw new SecurityException("ML-DSA verification failed");

        MLKEMPrivateKeyParameters priv = loadKemPrivate();
        MLKEMExtractor ext = new MLKEMExtractor(priv);
        byte[] shared = ext.extractSecret(kemCt);
        byte[] aesKey = kdf(shared, kemCt);
        Arrays.fill(shared, (byte) 0);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD("SINERGY-SECURE-MESSAGE-v2".getBytes(StandardCharsets.UTF_8));
        byte[] pt = c.doFinal(ct);
        Arrays.fill(aesKey, (byte) 0);
        return new String(pt, StandardCharsets.UTF_8);
    }

    private boolean kemSelfTest() throws Exception {
        MLKEMPublicKeyParameters pub = loadKemPublic();
        MLKEMPrivateKeyParameters priv = loadKemPrivate();
        SecretWithEncapsulation swe = new MLKEMGenerator(random).generateEncapsulated(pub);
        byte[] b = new MLKEMExtractor(priv).extractSecret(swe.getEncapsulation());
        boolean ok = MessageDigest.isEqual(swe.getSecret(), b);
        Arrays.fill(b, (byte) 0);
        return ok;
    }

    private boolean signatureSelfTest() throws Exception {
        byte[] m = "SINERGY-PQ-SIGNATURE-SELFTEST-v2".getBytes(StandardCharsets.UTF_8);
        byte[] s = sign(m);
        return verify(m, s, Base64.decode(prefs.getString("sig_pub", ""), Base64.NO_WRAP));
    }

    private byte[] sign(byte[] message) throws Exception {
        MLDSASigner signer = new MLDSASigner();
        signer.init(true, loadSigPrivate());
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private boolean verify(byte[] message, byte[] signature, byte[] pubEncoded) {
        MLDSAPublicKeyParameters pub = new MLDSAPublicKeyParameters(SIG_PARAMS, pubEncoded);
        MLDSASigner verifier = new MLDSASigner();
        verifier.init(false, pub);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    private MLKEMPublicKeyParameters loadKemPublic() {
        return new MLKEMPublicKeyParameters(KEM_PARAMS, Base64.decode(prefs.getString("kem_pub", ""), Base64.NO_WRAP));
    }

    private MLKEMPrivateKeyParameters loadKemPrivate() throws Exception {
        return new MLKEMPrivateKeyParameters(KEM_PARAMS, vault.openBytes(prefs.getString("kem_priv", ""), "PQ-KEM-PRIVATE-v2"));
    }

    private MLDSAPrivateKeyParameters loadSigPrivate() throws Exception {
        return new MLDSAPrivateKeyParameters(SIG_PARAMS, vault.openBytes(prefs.getString("sig_priv", ""), "PQ-SIG-PRIVATE-v2"));
    }

    private static byte[] kdf(byte[] shared, byte[] kemCt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update("SINERGY-PQ-KDF-v2".getBytes(StandardCharsets.UTF_8));
        md.update(shared);
        md.update(kemCt);
        return Arrays.copyOf(md.digest(), 32);
    }

    private static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) n += a.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, out, p, a.length); p += a.length; }
        return out;
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
