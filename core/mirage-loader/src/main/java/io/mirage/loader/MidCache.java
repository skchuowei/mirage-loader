package io.mirage.loader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.mirage.api.log.MirageLog;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *
 * <p><b>MSR P2</b>：
 * <ul>
 *   <li>写盘改原子（{@code .json.tmp} → {@code ATOMIC_MOVE}）：launcher 中途崩溃不会留下
 *       半截 {@code .json}（{@code .json.tmp} 不会被 {@link #lookup} 读到，下次启动直接覆盖）；</li>
 *   <li>{@link #storePassive}：真实加载窗口采集到的数据入库，与已有沙盒数据**合并**，
 *       无变化则不写盘；</li>
 *   <li>{@link #peek}：拿"这份 jar 上一次的录制结果"，供被动捕获做二启 diff。</li>
 * </ul>
 */
public final class MidCache {

    /** 当前缓存格式版本。0.4 = P2（source / complete + 原子写）。 */
    public static final String SPEC_VERSION = "0.4";

    /** 缓存文件头 + 载荷。Gson 可序列化。 */
    public static final class Entry {
        public String specVersion;        // 见 {@link #SPEC_VERSION}
        public String agVersion;          // = NeoForgeModDetector.AG_VERSION
        public String mcVersion;          // 如 "26.2"
        public String jarSha256;          // mod jar 全文件 SHA-256（hex）
        public long   cachedAt;           // epoch millis
        public NeoForgeModDetector.Detected detected;

        /** 录制结果（MSR）。null = 没录过 / 录制未完成被丢弃。 */
        public Recorded recorded;

        /**
         * 录制时的 fake 表面指纹（{@code SandboxRecorder.fakeVersion()}）。null = 0.2 老缓存。
         * 只在 {@link #recorded} 非 null 时校对：检测结果本身不依赖 fake 表面，不该被连坐失效。
         */
        public String fakeVersion;
    }

    /**
     * 录制载荷（MSR）。Gson 可序列化，所有字段可空/缺省。
     * 写进 {@link Entry#recorded} 可选子对象，与老字段共存。
     */
    public static final class Recorded {
        public String recorded_loader_version;  // mod 声明期望的 NeoForge 版本区间，如 "[26.1.0.1-beta,)"
        public String recorded_mc_version;      // mod 声明期望的 MC 版本区间，如 "[26.1,27)"
        public java.util.List<String> touched_apis;      // 构造/<clinit> 触达的 API 全限定名（类名+方法名）
        public java.util.List<String> registered_events; // 注册上去的事件类全限定名
        public int deferred_register_count;     // 构造里 create 了几个 DeferredRegister
        public long record_duration_ms;         // 录制耗时
        public boolean failed;                  // 构造是否崩 / 超时
        public String error;                    // failed=true 时填异常类名 + message；否则 null
        /**
         * 录制未完成（沙盒超时中断 / 被动窗口内的 mod 初始化抛错）。
         * true 时 {@code touched_apis} 等一律为空 —— 半成品不入库。
         */
        public boolean incomplete;

        /** 数据来源：{@code sandbox} / {@code passive} / {@code sandbox+passive}（P2）。 */
        public String source;

        /** 该 mod 的采集窗口是否完整走完（P2）。只有 complete=true 的被动数据才入库。 */
        public boolean complete;

        /** 参与"是否有变化"判断的字段签名（不含时间类字段）。 */
        String signature() {
            return source + '|' + complete + '|' + failed + '|' + incomplete + '|'
                    + deferred_register_count + '|' + touched_apis + '|' + registered_events;
        }
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
            MirageLog.MIDCACHE.warn("无法创建缓存目录 " + cacheRoot
                    + "（本次启动全部走现场识别，功能不受影响）", e);
        }
    }

    /** 缓存目录（{@code <gameDir>/.mid-cache/<mcVersion>}），供 diff 报告等同目录产物使用。 */
    public Path root() {
        return cacheRoot;
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
                    // 格式版本：schema 变了（字段语义变了）就整体作废，不让老数据假装还成立
                    if (!SPEC_VERSION.equals(e.specVersion)) continue;
                    // 三件套校对
                    if (!NeoForgeModDetector.AG_VERSION.equals(e.agVersion)) continue;
                    if (!jarSha.equals(e.jarSha256)) continue;
                    if (!mcVersion.equals(e.mcVersion)) continue;
                    // 第四件套：掺了沙盒数据的录制结果还要跟 fake 表面版本对齐（表面扩充后作废重录）。
                    // fakeVersion == null 表示这份 recorded 全是被动采集的，不依赖 fake 表面，不校对。
                    if (e.recorded != null && e.fakeVersion != null
                            && !io.mirage.loader.sandbox.SandboxRecorder.fakeVersion().equals(e.fakeVersion)) {
                        MirageLog.MIDCACHE.debug("fake 表面已变更（缓存 " + e.fakeVersion
                                + " ≠ 当前 " + io.mirage.loader.sandbox.SandboxRecorder.fakeVersion()
                                + "），重录: " + e.detected.id);
                        continue;
                    }
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

    /**
     * 只按文件名读这份 jar 的缓存条目，**不做** AG / fake 表面校对。
     *
     * <p>用途：二启 diff 需要"上一次的录制结果"作基线，这份基线哪怕格式过时也仍有比较价值；
     * 而 {@link #lookup} 会因为校对失败返回 null，正好把基线丢掉。
     *
     * @return 条目；文件不存在 / JSON 损坏 / jar 已变（sha 不同）时返回 null
     */
    public Entry peek(Path jar) {
        if (!Files.isDirectory(cacheRoot)) return null;
        try {
            String jarSha = sha256(jar);
            Path out = fileFor(null, jar, jarSha);
            if (!Files.isRegularFile(out)) return null;
            try (Reader r = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
                Entry e = gson.fromJson(r, Entry.class);
                if (e == null || e.detected == null) return null;
                // jar 变了 = 基线无意义（内容已经换了）
                if (!jarSha.equals(e.jarSha256)) return null;
                return e;
            }
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 写缓存。文件名 = &lt;modId&gt;+&lt;jarSha256前16位&gt;.json。 */
    public void store(Path jar, NeoForgeModDetector.Detected d) throws IOException {
        store(jar, d, null);
    }

    /**
     * 写缓存，可带录制载荷（沙盒路径）。
     * @param recorded 录制结果；null 时只写 detected（等价于旧行为）。
     */
    public void store(Path jar, NeoForgeModDetector.Detected d, Recorded recorded) throws IOException {
        if (!Files.isDirectory(cacheRoot)) return;
        if (recorded != null) {
            // 沙盒数据的来源与完整性在这里补齐（SandboxRecorder 不关心落盘语义）
            recorded.source = "sandbox";
            recorded.complete = !recorded.failed && !recorded.incomplete;
        }
        write(jar, d, recorded);
    }

    /**
     * 被动捕获结果入库（MSR P2）：与磁盘上已有条目合并，无变化不写。
     *
     * <p>只有 {@code passive.complete == true} 才允许调用 —— 半成品不入库是 P1 定下的规矩，
     * 被动侧同样适用（mod 初始化半路抛错时采集到的东西不代表它的完整 API 面）。
     *
     * @return 合并后的录制结果（供 diff / 日志用）；未写盘时返回合并结果本身（不落盘）
     */
    public Recorded storePassive(Path jar, NeoForgeModDetector.Detected d, Recorded passive) throws IOException {
        Objects.requireNonNull(passive, "passive");
        if (!passive.complete) {
            MirageLog.MIDCACHE.debug("被动捕获不完整，不入库: " + (d != null ? d.id : jar.getFileName()));
            return passive;
        }
        if (!Files.isDirectory(cacheRoot)) return passive;

        String jarSha = sha256(jar);
        Entry existing = peek(jar);
        Recorded prev = (existing != null) ? existing.recorded : null;
        Recorded merged = merge(prev, passive);

        NeoForgeModDetector.Detected detected = (existing != null && existing.detected != null)
                ? existing.detected : d;
        if (detected == null) return merged;

        if (prev != null && prev.signature().equals(merged.signature())) {
            // 数据没变：不写盘（避免每次启动都改文件时间戳）
            return merged;
        }
        write(jar, detected, merged);
        return merged;
    }

    /**
     * 合并两次采集：API / 事件取并集，DeferredRegister 计数取大值。
     *
     * <p>取并集而不是"后者覆盖"，是因为两条链路看到的**不是同一个集合**：
     * 沙盒看的是 fake 表面被调用的方法，被动捕获看的是真实桥接的入口。
     * 谁都不是全集，合并比替换更接近真相。
     */
    public static Recorded merge(Recorded prev, Recorded passive) {
        if (prev == null) {
            Recorded r = passive;
            r.source = (r.source == null) ? "passive" : r.source;
            return r;
        }
        Recorded out = new Recorded();
        out.recorded_loader_version = firstNonBlank(passive.recorded_loader_version, prev.recorded_loader_version);
        out.recorded_mc_version = firstNonBlank(passive.recorded_mc_version, prev.recorded_mc_version);
        out.touched_apis = union(prev.touched_apis, passive.touched_apis);
        out.registered_events = union(prev.registered_events, passive.registered_events);
        out.deferred_register_count = Math.max(prev.deferred_register_count, passive.deferred_register_count);
        out.record_duration_ms = prev.record_duration_ms;
        // 被动侧跑通了就以它为准：沙盒里的 failed 通常只是 fake 表面缺口，不是 mod 的问题
        out.failed = passive.failed;
        out.error = passive.failed ? passive.error : null;
        out.incomplete = false;
        out.complete = passive.complete;
        String prevSrc = prev.source == null ? "" : prev.source;
        out.source = prevSrc.contains("sandbox") ? "sandbox+passive" : "passive";
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static List<String> union(List<String> a, List<String> b) {
        Set<String> s = new LinkedHashSet<>();
        if (a != null) s.addAll(a);
        if (b != null) s.addAll(b);
        List<String> out = new ArrayList<>(s);
        out.sort(null);
        return out;
    }

    // ------------------------------------------------------------------ 落盘

    private void write(Path jar, NeoForgeModDetector.Detected d, Recorded recorded) throws IOException {
        String jarSha = sha256(jar);
        Entry e = new Entry();
        e.specVersion = SPEC_VERSION;
        e.agVersion = NeoForgeModDetector.AG_VERSION;
        e.mcVersion = mcVersion;
        e.jarSha256 = jarSha;
        e.cachedAt = System.currentTimeMillis();
        e.detected = d;
        e.recorded = recorded;
        // fake 指纹只对"掺了沙盒数据"的条目有意义：纯被动数据不依赖 fake 表面，
        // 记上它只会让 fake 表面上一次变动就把好好的被动条目连坐失效。
        boolean sandboxInvolved = recorded != null && recorded.source != null && recorded.source.contains("sandbox");
        e.fakeVersion = sandboxInvolved ? io.mirage.loader.sandbox.SandboxRecorder.fakeVersion() : null;

        Path out = fileFor(d.id, jar, jarSha);
        writeAtomic(out, e);
    }

    /**
     * 原子写：先写 {@code <name>.json.tmp}，再 {@code ATOMIC_MOVE} 覆盖。
     *
     * <p>中途被 kill -9 只会留下一个 {@code .json.tmp}：它不匹配 {@link #isCacheFile}，
     * 永远不会被当成有效缓存读进来；下次写同名文件直接覆盖。
     */
    private void writeAtomic(Path out, Entry e) throws IOException {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            gson.toJson(e, w);
            w.flush();
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            // 极端文件系统（网络盘）不支持原子移动：退化为普通替换，并如实告警
            MirageLog.MIDCACHE.warn("缓存目录不支持原子移动，退化为普通替换: " + out);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path fileFor(String modId, Path jar, String jarSha) {
        // modId 未知时（peek 场景）从目录里按 sha 后缀找
        if (modId != null) {
            return cacheRoot.resolve(safeId(modId) + "+" + jarSha.substring(0, 16) + ".json");
        }
        if (Files.isDirectory(cacheRoot)) {
            try (Stream<Path> files = Files.list(cacheRoot)) {
                return files.filter(this::isCacheFile)
                        .filter(p -> p.getFileName().toString().endsWith("+" + jarSha.substring(0, 16) + ".json"))
                        .findFirst()
                        .orElse(cacheRoot.resolve("unknown+" + jarSha.substring(0, 16) + ".json"));
            } catch (IOException ignore) {
                // 落下去走默认路径
            }
        }
        return cacheRoot.resolve("unknown+" + jarSha.substring(0, 16) + ".json");
    }

    private static String safeId(String modId) {
        return modId.replaceAll("[^a-zA-Z0-9_.-]", "_");
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
