package ai.sinergy.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class StrongBoxVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "sinergy_messenger_root_wrap_v2";
    private static final String PREFS = "sinergy_native_vault_v2";
    private static final String ROOT_BLOB = "root_blob";
    private static final int GCM_BITS = 128;
    private final Context context;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    StrongBoxVault(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean strongBoxAvailable() {
        return Build.VERSION.SDK_INT >= 28 && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    }

    synchronized SecretKey ensureWrappingKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(WRAP_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        try {
            return generateWrappingKey(strongBoxAvailable());
        } catch (StrongBoxUnavailableException e) {
            return generateWrappingKey(false);
        }
    }

    private SecretKey generateWrappingKey(boolean useStrongBox) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true);
        if (Build.VERSION.SDK_INT >= 28 && useStrongBox) {
            b.setIsStrongBoxBacked(true);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    synchronized byte[] rootSecret() throws Exception {
        String stored = prefs.getString(ROOT_BLOB, null);
        SecretKey wrapping = ensureWrappingKey();
        if (stored == null) {
            byte[] root = new byte[32];
            random.nextBytes(root);
            prefs.edit().putString(ROOT_BLOB, encryptWithKey(wrapping, root)).apply();
            return root;
        }
        return decryptWithKey(wrapping, stored);
    }

    synchronized String sealString(String plaintext, String aad) throws Exception {
        return encryptWithRawKey(rootSecret(), plaintext.getBytes(StandardCharsets.UTF_8), aad);
    }

    synchronized String openString(String sealed, String aad) throws Exception {
        return new String(decryptWithRawKey(rootSecret(), sealed, aad), StandardCharsets.UTF_8);
    }

    synchronized String sealBytes(byte[] plaintext, String aad) throws Exception {
        return encryptWithRawKey(rootSecret(), plaintext, aad);
    }

    synchronized byte[] openBytes(String sealed, String aad) throws Exception {
        return decryptWithRawKey(rootSecret(), sealed, aad);
    }

    synchronized void saveNamed(String name, String plaintext) throws Exception {
        prefs.edit().putString("data_" + name, sealString(plaintext, "SINERGY-V2:" + name)).apply();
    }

    synchronized String loadNamed(String name) throws Exception {
        String s = prefs.getString("data_" + name, null);
        if (s == null) return null;
        return openString(s, "SINERGY-V2:" + name);
    }

    String rootCommitment() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        md.update("SINERGY-MESSENGER-ROOT-v2".getBytes(StandardCharsets.UTF_8));
        md.update(rootSecret());
        return hex(md.digest());
    }

    private String encryptWithKey(SecretKey key, byte[] plaintext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plaintext);
        byte[] out = new byte[1 + iv.length + ct.length];
        out[0] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 1, iv.length);
        System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private byte[] decryptWithKey(SecretKey key, String sealed) throws Exception {
        byte[] in = Base64.decode(sealed, Base64.NO_WRAP);
        int n = in[0] & 0xff;
        byte[] iv = Arrays.copyOfRange(in, 1, 1 + n);
        byte[] ct = Arrays.copyOfRange(in, 1 + n, in.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_BITS, iv));
        return c.doFinal(ct);
    }

    private String encryptWithRawKey(byte[] keyBytes, byte[] plaintext, String aad) throws Exception {
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_BITS, iv));
        if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plaintext);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private byte[] decryptWithRawKey(byte[] keyBytes, String sealed, String aad) throws Exception {
        byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
        byte[] iv = Arrays.copyOfRange(all, 0, 12);
        byte[] ct = Arrays.copyOfRange(all, 12, all.length);
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_BITS, iv));
        if (aad != null) c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return c.doFinal(ct);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
}
