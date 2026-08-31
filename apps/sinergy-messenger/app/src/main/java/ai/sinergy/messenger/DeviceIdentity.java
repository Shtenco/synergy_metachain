package ai.sinergy.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

final class DeviceIdentity {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "sinergy_messenger_device_identity_v2";
    private static final String PREF = "sinergy_device_registration_v2";
    private final Context context;
    private final SharedPreferences prefs;

    DeviceIdentity(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    synchronized JSONObject ensureRegistered() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            prefs.edit().putString("challenge", Base64.encodeToString(challenge, Base64.NO_WRAP)).apply();
            try {
                createKey(challenge, hasStrongBox());
            } catch (StrongBoxUnavailableException e) {
                createKey(challenge, false);
            }
            prefs.edit().putLong("registered_at", System.currentTimeMillis()).apply();
        }

        Certificate[] chain = ks.getCertificateChain(ALIAS);
        Certificate leaf = ks.getCertificate(ALIAS);
        if (leaf == null) throw new IllegalStateException("Device identity certificate missing");
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update("SINERGY-DEVICE-ID-v2".getBytes(StandardCharsets.UTF_8));
        md.update(leaf.getEncoded());
        String deviceId = hex(md.digest());

        JSONArray certs = new JSONArray();
        if (chain != null) {
            for (Certificate c : chain) certs.put(Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP));
        }
        JSONObject o = new JSONObject();
        o.put("deviceId", deviceId);
        o.put("registeredAt", prefs.getLong("registered_at", 0L));
        o.put("attestationChallenge", prefs.getString("challenge", ""));
        o.put("certificateChain", certs);
        o.put("strongBoxRequested", hasStrongBox());
        o.put("oneTime", true);
        return o;
    }

    boolean isRegistered() {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            return ks.containsAlias(ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasStrongBox() {
        return Build.VERSION.SDK_INT >= 28 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    private void createKey(byte[] challenge, boolean strongBox) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setAttestationChallenge(challenge)
                .setUserAuthenticationRequired(false);
        if (Build.VERSION.SDK_INT >= 28 && strongBox) b.setIsStrongBoxBacked(true);
        kpg.initialize(b.build());
        kpg.generateKeyPair();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
