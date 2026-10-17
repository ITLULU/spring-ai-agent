package com.example.ragkb.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.ragkb.chunking.Chunk;

/**
 * ChunkStore 的进程内实现（默认，{@code rag.chunk-store.type=memory} 或缺省）。
 * 零基建、clone 即跑，代价是重启即空——需重新入库；生产切换 postgres 持久化。
 */
@Component
@ConditionalOnProperty(name = "rag.chunk-store.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryChunkStore implements ChunkStore {

    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();

    @Override
    public void putAll(Collection<Chunk> newChunks) {
        newChunks.forEach(c -> chunks.put(c.id(), c));
    }

    @Override
    public Chunk get(String id) {
        return chunks.get(id);
    }

    @Override
    public List<Chunk> all() {
        return List.copyOf(chunks.values());
    }

    @Override
    public List<Chunk> removeByIds(List<String> ids) {
        return ids.stream().map(chunks::remove).filter(Objects::nonNull).toList();
    }

    @Override
    public List<Chunk> removeBySource(String source) {
        List<Chunk> removed = chunks.values().stream()
                .filter(c -> Objects.equals(c.source(), source)).toList();
        removed.forEach(c -> chunks.remove(c.id()));
        return List.copyOf(removed);
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public String backend() {
        return "memory";
    }
}
