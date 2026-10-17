package io.mirage.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * MID（Mirage Intermediate Description）磁盘缓存。
 *
 * <p>每个 NeoForge mod jar 第一次被识别后，把 {@link NeoForgeModDetector.Detected}
 * 连同校对元数据写到 {@code <gameDir>/.mid-cache/<mcVersion>/<modId>+<jarHash16>.json}。
 * 第二次启动命中缓存且三件套（agVersion / jarSha256 / mcVersion）全过，
 * 就跳过 ASM 扫 class，直接用缓存里的 Detected。
 *
 * <p>校对失败的条目会被自动删除并重转。缓存目录写不进去 / JSON 损坏时，
 * 本类全部降级为"未命中"，不抛异常阻断启动。
 */
public final class MidCache {

    /** 缓存文件头 + 载荷。Gson 可序列化。 */
    public static final class Entry {
        public String specVersion;        // 固定 "0.1"
        public String agVersion;          // = NeoForgeModDetector.AG_VERSION
        public String mcVersion;          // 如 "26.2"
        public String jarSha256;          // mod jar 全文件 SHA-256（hex）
        public long   cachedAt;           // epoch millis
        public NeoForgeModDetector.Detected detected;
    }

    private final Path cacheRoot;
    private final String mcVersion;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param gameDir 游戏目录（Launcher 从 --gameDir 解析得到）
     * @param mcVersion 当前 MC 版本（先硬编码 "26.2"，第二步改成读 SharedConstants）
     */
    public MidCache(Path gameDir, String mcVersion) {
        this.mcVersion = mcVersion;
        this.cacheRoot = gameDir.resolve(".mid-cache").resolve(mcVersion);
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException e) {
            System.err.println("[MidCache] 无法创建缓存目录 " + cacheRoot + ": " + e
                    + "（本次启动全部走现场识别，不报错）");
        }
    }

    /**
     * 尝试读缓存。
     *
     * @param jar       mod jar 路径
     * @param modIdHint mod id（用于定位缓存文件名；为 null 时遍历目录按 jarSha256 匹配）
     * @param forceRegen 为 true 时直接返回 null（强制重转）
     * @return 命中且校对通过的 Entry；未命中/校对失败/IO 异常时返回 null
     */
    public Entry lookup(Path jar, String modIdHint, boolean forceRegen) {
        if (forceRegen) return null;
        if (!Files.isDirectory(cacheRoot)) return null;

        String jarSha;
        try {
            jarSha = sha256(jar);
        } catch (IOException e) {
            return null;
        }

        try (Stream<Path> files = Files.list(cacheRoot)) {
            for (Path p : (Iterable<Path>) files.filter(this::isCacheFile)::iterator) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Entry e = gson.fromJson(r, Entry.class);
                    if (e == null || e.detected == null) continue;
                    // 三件套校对
                    if (!NeoForgeModDetector.AG_VERSION.equals(e.agVersion)) continue;
                    if (!jarSha.equals(e.jarSha256)) continue;
                    if (!mcVersion.equals(e.mcVersion)) continue;
                    // modIdHint 若提供，必须匹配
                    if (modIdHint != null && !modIdHint.equals(e.detected.id)) continue;
                    return e;
                } catch (Exception corrupt) {
                    // JSON 损坏：删掉这个坏文件，下次重转
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                }
            }
        } catch (IOException e) {
            // 列目录失败，降级
        }
        return null;
    }

    /** 写缓存。文件名 = &lt;modId&gt;+&lt;jarSha256前16位&gt;.json。 */
    public void store(Path jar, NeoForgeModDetector.Detected d) throws IOException {
        if (!Files.isDirectory(cacheRoot)) return;
        String jarSha = sha256(jar);
        Entry e = new Entry();
        e.specVersion = "0.1";
        e.agVersion = NeoForgeModDetector.AG_VERSION;
        e.mcVersion = mcVersion;
        e.jarSha256 = jarSha;
        e.cachedAt = System.currentTimeMillis();
        e.detected = d;

        String safeId = d.id.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path out = cacheRoot.resolve(safeId + "+" + jarSha.substring(0, 16) + ".json");
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            gson.toJson(e, w);
        }
    }

    private boolean isCacheFile(Path p) {
        return p.getFileName().toString().endsWith(".json");
    }

    /** 算文件 SHA-256（hex）。 */
    private static String sha256(Path jar) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(jar)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
