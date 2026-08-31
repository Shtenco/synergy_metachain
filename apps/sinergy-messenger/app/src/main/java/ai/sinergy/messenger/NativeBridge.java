package ai.sinergy.messenger;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

public final class NativeBridge {
    private final Context context;
    private final StrongBoxVault vault;
    private final DeviceIdentity deviceIdentity;
    private final PqCrypto pq;

    NativeBridge(Context context) {
        this.context = context.getApplicationContext();
        this.vault = new StrongBoxVault(this.context);
        this.deviceIdentity = new DeviceIdentity(this.context);
        this.pq = new PqCrypto(this.context, vault);
    }

    @JavascriptInterface
    public String securityStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("nativeShell", "WebViewAssetLoader");
            o.put("origin", "https://appassets.androidplatform.net");
            o.put("strongBoxAvailable", vault.strongBoxAvailable());
            o.put("deviceRegistered", deviceIdentity.isRegistered());
            o.put("rootCommitment", vault.rootCommitment());
            o.put("tdlib", TdlibBridge.smokeTest());
            o.put("mesh", MeshService.storedStatus(context));
            return ok(o);
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JavascriptInterface
    public String registerDevice() {
        try { return ok(deviceIdentity.ensureRegistered()); }
        catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String pqEnsureIdentity() {
        try { return ok(pq.ensureIdentity()); }
        catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String pqPublicBundle() {
        try { pq.ensureIdentity(); return ok(pq.publicBundle()); }
        catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String pqSeal(String peerKemPublicBase64, String plaintext) {
        try {
            JSONObject o = new JSONObject();
            o.put("envelope", pq.sealForPeer(peerKemPublicBase64, plaintext));
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String pqOpen(String envelopeJson) {
        try {
            JSONObject o = new JSONObject();
            o.put("plaintext", pq.openEnvelope(envelopeJson));
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String vaultSave(String name, String plaintext) {
        try {
            vault.saveNamed(safeName(name), plaintext == null ? "" : plaintext);
            JSONObject o = new JSONObject();
            o.put("saved", true);
            o.put("name", safeName(name));
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String vaultLoad(String name) {
        try {
            JSONObject o = new JSONObject();
            String v = vault.loadNamed(safeName(name));
            o.put("value", v == null ? JSONObject.NULL : v);
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String meshSend(String payload) {
        try {
            String id = MeshService.enqueue(context, payload == null ? "" : payload);
            JSONObject o = new JSONObject();
            o.put("queued", !id.isEmpty());
            o.put("frameId", id);
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String meshDrain() {
        try {
            JSONObject o = new JSONObject();
            o.put("frames", MeshService.drainInbox(context));
            return ok(o);
        } catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String meshStatus() {
        try { return ok(MeshService.storedStatus(context)); }
        catch (Throwable t) { return error(t); }
    }

    @JavascriptInterface
    public String tdlibStatus() {
        try { return ok(TdlibBridge.smokeTest()); }
        catch (Throwable t) { return error(t); }
    }

    private static String safeName(String name) {
        String s = name == null ? "default" : name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (s.length() > 64) s = s.substring(0, 64);
        if (s.isEmpty()) s = "default";
        return s;
    }

    private static String ok(JSONObject payload) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", payload);
            return o.toString();
        } catch (Throwable t) { return "{\"ok\":false,\"error\":\"json\"}"; }
    }

    private static String error(Throwable t) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            return o.toString();
        } catch (Throwable ignored) { return "{\"ok\":false,\"error\":\"native\"}"; }
    }
}
