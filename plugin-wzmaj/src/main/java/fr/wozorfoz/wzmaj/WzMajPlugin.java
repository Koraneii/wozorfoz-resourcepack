package fr.wozorfoz.wzmaj;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Met à jour, depuis GitHub, le resource pack (envoyé directement aux joueurs, même déjà connectés)
 * et les datapacks du monde principal (puis /reload). Aucun redémarrage, aucun FTP.
 */
public final class WzMajPlugin extends JavaPlugin implements Listener {

    /** Identifiant fixe du pack : un nouveau pack envoyé avec le même identifiant remplace l'ancien chez le joueur. */
    private static final UUID ID_PACK = UUID.nameUUIDFromBytes("wozorfoz-resourcepack".getBytes(StandardCharsets.UTF_8));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();

    private volatile String urlPack;
    private volatile byte[] shaPack;          // SHA-1 du pack actuellement envoyé (null : pas encore connu)
    private volatile boolean enCours;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("au-demarrage.pack", true)) {
            lancer(Bukkit.getConsoleSender(), true, getConfig().getBoolean("au-demarrage.datapacks", false));
        }
    }

    // ------------------------------------------------------------------ commande

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        String quoi = args.length == 0 ? "tout" : args[0].toLowerCase(Locale.ROOT);
        switch (quoi) {
            case "tout" -> lancer(s, true, true);
            case "pack" -> lancer(s, true, false);
            case "datapacks" -> lancer(s, false, true);
            case "info" -> {
                dire(s, "Pack : " + (shaPack == null ? "pas encore téléchargé" : hex(shaPack)), NamedTextColor.GOLD);
                dire(s, "Lien : " + getConfig().getString("pack.url"), NamedTextColor.GRAY);
            }
            case "recharger" -> { reloadConfig(); dire(s, "Configuration rechargée.", NamedTextColor.GOLD); }
            default -> dire(s, "/wzmaj [tout|pack|datapacks|info|recharger]", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        return args.length == 1 ? List.of("tout", "pack", "datapacks", "info", "recharger") : List.of();
    }

    private void lancer(CommandSender s, boolean pack, boolean datapacks) {
        if (enCours) { dire(s, "Une mise à jour est déjà en cours…", NamedTextColor.YELLOW); return; }
        enCours = true;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (pack) majPack(s);
                if (datapacks) majDatapacks(s);
            } catch (Exception e) {
                dire(s, "Échec de la mise à jour : " + e.getMessage(), NamedTextColor.RED);
                getLogger().warning("Mise à jour : " + e);
            } finally {
                enCours = false;
            }
        });
    }

    // ------------------------------------------------------------------ resource pack

    private void majPack(CommandSender s) throws Exception {
        String url = getConfig().getString("pack.url");
        dire(s, "Vérification du resource pack…", NamedTextColor.GRAY);
        byte[] sha = sha1(telecharger(url));
        if (shaPack != null && Arrays.equals(sha, shaPack) && url.equals(urlPack)) {
            dire(s, "Resource pack déjà à jour (" + hex(sha).substring(0, 10) + "…).", NamedTextColor.GREEN);
            return;
        }
        boolean premier = shaPack == null;
        urlPack = url;
        shaPack = sha;
        dire(s, "Nouveau resource pack : " + hex(sha), NamedTextColor.GREEN);
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) envoyerPack(p);
            if (!premier || !Bukkit.getOnlinePlayers().isEmpty())
                dire(s, "Envoyé aux " + Bukkit.getOnlinePlayers().size() + " joueur(s) en ligne.", NamedTextColor.GREEN);
        });
    }

    private void envoyerPack(Player p) {
        if (shaPack == null) return;
        // la ligne « message » est facultative : l'invite de Minecraft s'affiche quand le pack est obligatoire
        Component msg = Component.text(getConfig().getString("pack.message", "Pack de ressources"), NamedTextColor.GOLD);
        // même identifiant : le nouveau pack remplace l'ancien sans déconnexion
        p.setResourcePack(ID_PACK, urlPack, shaPack, msg, getConfig().getBoolean("pack.obligatoire", true));
    }

    @EventHandler
    public void quandConnexion(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) envoyerPack(p); }, 10L);
    }

    // ------------------------------------------------------------------ datapacks

    private void majDatapacks(CommandSender s) throws Exception {
        String base = getConfig().getString("datapacks.base");
        if (!base.endsWith("/")) base += "/";
        dire(s, "Vérification des datapacks…", NamedTextColor.GRAY);
        String liste = new String(telecharger(base + "datapacks.txt"), StandardCharsets.UTF_8);
        Path dossier = Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("datapacks");
        Files.createDirectories(dossier);
        List<String> changes = new ArrayList<>();
        for (String ligne : liste.split("\\R")) {
            String[] t = ligne.trim().split("\\s+");
            if (t.length < 2 || !t[0].endsWith(".zip") || t[0].contains("/") || t[0].contains("..")) continue;
            Path cible = dossier.resolve(t[0]);
            if (Files.exists(cible) && hex(sha1(Files.readAllBytes(cible))).equalsIgnoreCase(t[1])) continue;
            byte[] zip = telecharger(base + t[0]);
            if (!hex(sha1(zip)).equalsIgnoreCase(t[1]))
                throw new IOException(t[0] + " : fichier reçu différent de la liste (réessayer dans une minute)");
            Path tmp = dossier.resolve(t[0] + ".tmp");
            Files.write(tmp, zip);
            Files.move(tmp, cible, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            changes.add(t[0]);
        }
        if (changes.isEmpty()) { dire(s, "Datapacks déjà à jour.", NamedTextColor.GREEN); return; }
        dire(s, "Datapacks mis à jour : " + String.join(", ", changes) + ". Rechargement…", NamedTextColor.GREEN);
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
            for (String nom : changes)       // un datapack tout nouveau doit être activé une première fois
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "datapack enable \"file/" + nom + "\"");
            dire(s, "Datapacks rechargés.", NamedTextColor.GREEN);
        });
    }

    // ------------------------------------------------------------------ outils

    private byte[] telecharger(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()))
                .timeout(Duration.ofSeconds(90)).header("User-Agent", "WzMaj").header("Cache-Control", "no-cache").GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " pour " + url);
        try (InputStream in = r.body()) { return in.readAllBytes(); }
    }

    private static byte[] sha1(byte[] d) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(d);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void dire(CommandSender s, String texte, NamedTextColor couleur) {
        Bukkit.getScheduler().runTask(this, () -> s.sendMessage(Component.text("[WzMaj] " + texte, couleur)));
    }
}
