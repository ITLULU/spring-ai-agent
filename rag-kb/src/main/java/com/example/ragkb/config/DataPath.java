package com.example.ragkb.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据文件相对路径的统一解析。
 * 背景：改造成多模块后，IDEA / mvn spring-boot:run 的工作目录通常是聚合根
 * （spring-ai-agent/），而 data/、output/ 都在 rag-kb/ 模块下，
 * 直接 Path.of("data/docs/...") 会解析到聚合根下面 → NoSuchFileException。
 * 解析优先级：
 *  1. 系统属性 -Drag.base.dir=... 或环境变量 RAG_BASE_DIR（显式指定，绝对/相对均可）；
 *  2. 自动探测：当前目录存在 data/ → 以当前目录为数据根（模块目录直启、mvn test 均走这里，行为不变）；
 *     否则存在 rag-kb/data/ → 以 rag-kb 为数据根（从聚合根启动的多模块场景）；
 *  3. 都找不到则维持当前目录，让报错信息暴露真实路径。
 * 绝对路径一律原样通过。懒加载探测一次后缓存。
 */
public final class DataPath {

    private static volatile Path baseDir;

    private DataPath() {
    }

    /** 把（可能相对的）路径解析到数据根目录下；绝对路径原样返回 */
    public static Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : baseDir().resolve(p).normalize();
    }

    /** 当前生效的数据根目录（诊断/日志用） */
    public static Path baseDir() {
        Path b = baseDir;
        if (b == null) {
            synchronized (DataPath.class) {
                b = baseDir;
                if (b == null) {
                    b = detect();
                    baseDir = b;
                }
            }
        }
        return b;
    }

    private static Path detect() {
        String override = System.getProperty("rag.base.dir", System.getenv("RAG_BASE_DIR") == null
                ? "" : System.getenv("RAG_BASE_DIR"));
        if (!override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        if (Files.isDirectory(Path.of("data"))) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        if (Files.isDirectory(Path.of("rag-kb", "data"))) {
            return Path.of("rag-kb").toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }
}
