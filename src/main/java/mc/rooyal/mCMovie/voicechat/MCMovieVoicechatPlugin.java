package mc.rooyal.mCMovie.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import mc.rooyal.mCMovie.MCMovie;

public class MCMovieVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "mcmovie";
    }

    @Override
    public void initialize(VoicechatApi api) {
        // On a Bukkit server, the api passed here is already a VoicechatServerApi.
        // Try to grab it immediately so we don't depend on the event firing later.
        if (api instanceof VoicechatServerApi serverApi) {
            MCMovie instance = MCMovie.getInstance();
            if (instance != null) {
                instance.setVoicechatServerApi(serverApi);
                instance.getLogger().info("[MCMovie] SimpleVoiceChat server API acquired in initialize()");
            }
        } else {
            MCMovie instance = MCMovie.getInstance();
            if (instance != null) {
                instance.getLogger().warning("[MCMovie] VoicechatApi in initialize() is NOT a VoicechatServerApi (type="
                        + api.getClass().getName() + ") — will wait for VoicechatServerStartedEvent");
            }
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        MCMovie instance = MCMovie.getInstance();
        if (instance != null) {
            instance.getLogger().info("[MCMovie] registerEvents() called — registering VoicechatServerStartedEvent listener");
        }
        // Fallback: if initialize() didn't get a VoicechatServerApi, this event will.
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi serverApi = event.getVoicechat();
        MCMovie instance = MCMovie.getInstance();
        if (instance != null) {
            instance.setVoicechatServerApi(serverApi);
            instance.getLogger().info("[MCMovie] SimpleVoiceChat server API acquired via VoicechatServerStartedEvent");
        }
    }
}
