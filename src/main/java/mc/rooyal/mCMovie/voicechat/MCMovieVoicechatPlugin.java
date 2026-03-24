package mc.rooyal.mCMovie.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import mc.rooyal.mCMovie.MCMovie;

public class MCMovieVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "mcmovie";
    }

    @Override
    public void initialize(VoicechatApi api) {
        MCMovie instance = MCMovie.getInstance();
        if (instance != null) {
            if (api instanceof VoicechatServerApi serverApi) {
                instance.setVoicechatServerApi(serverApi);
                instance.getLogger().info("[MCMovie] SimpleVoiceChat server API initialized successfully.");
            } else {
                instance.getLogger().warning("[MCMovie] VoicechatApi is not a VoicechatServerApi, audio will not work.");
            }
        }
    }
}
