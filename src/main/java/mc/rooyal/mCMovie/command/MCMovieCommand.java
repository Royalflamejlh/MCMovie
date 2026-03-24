package mc.rooyal.mCMovie.command;

import mc.rooyal.mCMovie.MCMovie;
import mc.rooyal.mCMovie.listener.ScreenCreationListener;
import mc.rooyal.mCMovie.screen.Screen;
import mc.rooyal.mCMovie.screen.ScreenManager;
import mc.rooyal.mCMovie.video.VideoPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class MCMovieCommand implements CommandExecutor, TabCompleter {

    private final MCMovie plugin;
    private final ScreenManager screenManager;
    private final ScreenCreationListener creationListener;

    public MCMovieCommand(MCMovie plugin, ScreenManager screenManager, ScreenCreationListener creationListener) {
        this.plugin = plugin;
        this.screenManager = screenManager;
        this.creationListener = creationListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "screen":
                return handleScreen(sender, args);
            case "play":
                return handlePlay(sender, args);
            case "stop":
                return handleStop(sender, args);
            case "status":
                return handleStatus(sender, args);
            default:
                sendHelp(sender);
                return true;
        }
    }

    private boolean handleScreen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("[MCMovie] Usage: /mcmovie screen <create|delete|list> [args]", NamedTextColor.RED));
            return true;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "create":
                return handleScreenCreate(sender, args);
            case "delete":
                return handleScreenDelete(sender, args);
            case "list":
                return handleScreenList(sender);
            default:
                sender.sendMessage(Component.text("[MCMovie] Unknown screen action: " + action, NamedTextColor.RED));
                return true;
        }
    }

    private boolean handleScreenCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("[MCMovie] Only players can create screens.", NamedTextColor.RED));
            return true;
        }
        Player player = (Player) sender;

        String name;
        if (args.length >= 3) {
            name = args[2];
        } else {
            name = "screen-" + (screenManager.getAllScreens().size() + 1);
        }

        if (creationListener.isSelecting(player)) {
            creationListener.cancelSelection(player);
            player.sendMessage(Component.text("[MCMovie] Previous selection cancelled.", NamedTextColor.YELLOW));
        }

        creationListener.startSelection(player, name);
        return true;
    }

    private boolean handleScreenDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("[MCMovie] Usage: /mcmovie screen delete <id>", NamedTextColor.RED));
            return true;
        }
        String id = args[2];
        Screen screen = screenManager.getScreen(id);
        if (screen == null) {
            sender.sendMessage(Component.text("[MCMovie] No screen found with ID: " + id, NamedTextColor.RED));
            return true;
        }
        screenManager.deleteScreen(id);
        plugin.saveScreensNow();
        sender.sendMessage(Component.text("[MCMovie] Screen '" + id + "' deleted.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleScreenList(CommandSender sender) {
        Map<String, Screen> all = screenManager.getAllScreens();
        if (all.isEmpty()) {
            sender.sendMessage(Component.text("[MCMovie] No screens defined.", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("[MCMovie] Screens (" + all.size() + "):", NamedTextColor.AQUA));
        for (Screen s : all.values()) {
            String status = s.isPlaying() ? "PLAYING: " + s.getCurrentUrl() : "IDLE";
            sender.sendMessage(Component.text("  - " + s.getId() + " [" + s.getWidthMaps() + "x" +
                    s.getHeightMaps() + " maps, " + s.getFace().name() + "] " + status, NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean handlePlay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("[MCMovie] Usage: /mcmovie play <url|file>", NamedTextColor.RED));
            return true;
        }

        String url = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        Screen screen = findNearestScreenOrFirst(sender);
        if (screen == null) {
            sender.sendMessage(Component.text("[MCMovie] No screens found. Create one first with /mcmovie screen create.", NamedTextColor.RED));
            return true;
        }

        // Stop existing player
        if (screen.getVideoPlayer() != null) {
            screen.getVideoPlayer().stop();
        }

        VideoPlayer vp = new VideoPlayer(screen, plugin);
        screen.setVideoPlayer(vp);
        vp.play(url);

        sender.sendMessage(Component.text("[MCMovie] Starting playback on screen '" + screen.getId() + "': " + url, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        Screen screen = findNearestScreenOrFirst(sender);
        if (screen == null) {
            sender.sendMessage(Component.text("[MCMovie] No screens found.", NamedTextColor.RED));
            return true;
        }

        if (screen.getVideoPlayer() != null) {
            screen.getVideoPlayer().stop();
            screen.setVideoPlayer(null);
        }
        screen.setPlaying(false);
        sender.sendMessage(Component.text("[MCMovie] Stopped playback on screen '" + screen.getId() + "'.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        Screen screen = findNearestScreenOrFirst(sender);
        if (screen == null) {
            sender.sendMessage(Component.text("[MCMovie] No screens found.", NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("[MCMovie] Screen: " + screen.getId(), NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  Size: " + screen.getWidthMaps() + "x" + screen.getHeightMaps() + " maps ("
                + (screen.getWidthMaps() * 128) + "x" + (screen.getHeightMaps() * 128) + " px)", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  Face: " + screen.getFace().name(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  Playing: " + screen.isPlaying(), NamedTextColor.WHITE));
        if (screen.getCurrentUrl() != null) {
            sender.sendMessage(Component.text("  URL: " + screen.getCurrentUrl(), NamedTextColor.WHITE));
        }
        sender.sendMessage(Component.text("  VoiceChat: " + (plugin.isVoiceChatEnabled() ? "enabled" : "disabled"), NamedTextColor.WHITE));
        return true;
    }

    /**
     * Finds the nearest screen to a player sender, or the first screen if sender is console.
     */
    private Screen findNearestScreenOrFirst(CommandSender sender) {
        Map<String, Screen> all = screenManager.getAllScreens();
        if (all.isEmpty()) return null;

        if (!(sender instanceof Player)) {
            return all.values().iterator().next();
        }

        Player player = (Player) sender;
        Screen nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Screen s : all.values()) {
            if (!s.getWorld().equals(player.getWorld())) continue;
            double dist = s.getCenter().distanceSquared(player.getLocation());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = s;
            }
        }

        // If nothing in same world, just return first
        if (nearest == null) return all.values().iterator().next();

        // Only return if within 200 blocks
        if (nearestDist <= 200 * 200) return nearest;

        // Otherwise just return the closest even if far
        return nearest;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStart(args[0], Arrays.asList("screen", "play", "stop", "status"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("screen")) {
            return filterStart(args[1], Arrays.asList("create", "delete", "list"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("screen") && args[1].equalsIgnoreCase("delete")) {
            return filterStart(args[2], new ArrayList<>(screenManager.getAllScreens().keySet()));
        }
        return Collections.emptyList();
    }

    private List<String> filterStart(String prefix, List<String> options) {
        return options.stream()
                .filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("[MCMovie] Commands:", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  /mcmovie screen create [name] - Start screen creation", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  /mcmovie screen delete <id>   - Delete a screen", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  /mcmovie screen list           - List all screens", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  /mcmovie play <url|file>       - Play media on nearest screen", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  /mcmovie stop                  - Stop nearest screen", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("  /mcmovie status                - Show status of nearest screen", NamedTextColor.WHITE));
    }
}
