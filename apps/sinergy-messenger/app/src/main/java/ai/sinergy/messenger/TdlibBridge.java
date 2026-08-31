package ai.sinergy.messenger;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

final class TdlibBridge {
    private TdlibBridge() {}

    static JSONObject smokeTest() {
        JSONObject o = new JSONObject();
        try {
            TdApi.Object r = Client.execute(new TdApi.GetTextEntities("SINERGY @nexus https://telegram.org"));
            o.put("jniLoaded", true);
            o.put("javaApi", "org.drinkless.tdlib.Client");
            o.put("nativeLibrary", "tdjni");
            o.put("probe", r == null ? "null" : r.getClass().getSimpleName());
            o.put("readyForAuthorization", true);
        } catch (Throwable t) {
            try {
                o.put("jniLoaded", false);
                o.put("readyForAuthorization", false);
                o.put("error", t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            } catch (Exception ignored) {}
        }
        return o;
    }
}
